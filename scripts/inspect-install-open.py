"""Disposable-cloud installation checks. Never connect a user's device."""
from pathlib import Path
import hashlib
import json
import os
import re
import time
import zipfile

PACKAGE = "io.github.gravere.bybus"
MAIN = PACKAGE + "/com.example.limbuszhcn.MainActivity"
PROBE = PACKAGE + "/com.example.limbuszhcn.InstallFlowProbeActivity"
CERT = "d50bbcfb234692d5d5f90427499158608c5a4c13adb7f99a78d9b5f1af44d55b"


def inspect_release_update(adb, command, hierarchy, locate, tap, report, out, release, request, verifier):
    """Observe the previous unmodified release updating itself through its real UI."""
    if os.environ.get("GITHUB_ACTIONS") != "true" or adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Release update inspection requires the disposable CI emulator")
    if release["draft"] or release["prerelease"]:
        raise RuntimeError("The historical app must see the actual public update, not a draft")

    def version():
        return int(re.search(r"\bversionCode=(\d+)\b", adb("shell", "dumpsys", "package", PACKAGE)).group(1))

    def texts(tree):
        return {n.get(key, "") for n in tree.iter("node") for key in ("text", "content-desc")}

    def wait_label(label, stem, seconds=60):
        deadline = time.monotonic() + seconds
        while True:
            tree = hierarchy(stem)
            labels = texts(tree)
            if any(message in value for message in ("GitHub 暂时限制", "连接超时", "暂时无法连接 GitHub", "下载文件校验失败")
                   for value in labels):
                raise RuntimeError("Real update UI reported a network or verification error; see " + stem + ".xml")
            try:
                locate(tree, label)
                return tree
            except RuntimeError:
                if time.monotonic() >= deadline:
                    raise RuntimeError("Real update UI did not reach " + label)
                time.sleep(4)

    target_code = version()
    releases = json.loads(request("https://api.github.com/repos/gravere/BYBUS/releases?per_page=20", 1024 * 1024))
    older = [(int(match.group(1)), item) for item in releases
             if not item["draft"] and not item["prerelease"]
             and (match := re.fullmatch(r"v[\d.]+-build\.(\d+)", item["tag_name"]))
             and int(match.group(1)) < target_code]
    if not older:
        raise RuntimeError("No retained historical release available")
    base_code, previous = max(older, key=lambda item: item[0])
    assets = [a for a in previous["assets"] if re.fullmatch(r"BYBUS-v[\d.]+-arm64-v8a\.apk", a["name"])]
    if len(assets) != 1:
        raise RuntimeError("Previous release has no unambiguous full APK")
    asset = assets[0]
    body = request(asset["url"], 200 * 1024 * 1024, asset=True)
    digest = hashlib.sha256(body).hexdigest()
    if asset.get("digest") != "sha256:" + digest or len(body) != asset["size"]:
        raise RuntimeError("Historical release digest mismatch")
    base = Path(os.environ["RUNNER_TEMP"]) / "previous-BYBUS.apk"
    base.write_bytes(body)
    del body
    signatures = command(str(verifier), "verify", "--print-certs", str(base), timeout=60)
    if set(re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)", signatures)) != {CERT}:
        raise RuntimeError("Historical release signer mismatch")
    adb("uninstall", PACKAGE)
    if "Success" not in adb("install", "--no-streaming", str(base), timeout=150) or version() != base_code:
        raise RuntimeError("Could not install historical production APK")
    observation = dict(from_code=base_code, to_code=target_code, source_tag=previous["tag_name"],
        source_sha256=digest, source="unmodified historical Release APK, actual MainActivity update UI",
        game_launched=False, account_accessed=False, debug_fixture_used=False,
        installer_confirmed=False, installed=False, open_clicked=False, stable_home=False)
    report["release_update"] = observation
    adb("shell", "appops", "set", PACKAGE, "REQUEST_INSTALL_PACKAGES", "allow")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", MAIN, timeout=60)
    tap("设置与工具", wait_label("设置与工具", "release-old-home"))
    tap("更新", wait_label("更新", "release-old-settings"))
    tap("检查应用更新", wait_label("检查应用更新", "release-old-updates"))
    tap("下载更新", wait_label("下载更新", "release-update-found", 90))
    tree = wait_label("安装更新", "release-update-downloaded", 240)
    tasks = adb("shell", "dumpsys", "activity", "activities")
    (out / "release-update-before-tasks.txt").write_text(tasks, encoding="utf-8")
    top = next(line for line in tasks.splitlines() if "topResumedActivity" in line or "mResumedActivity" in line)
    observation["source_task"] = int(re.search(r"\bt(\d+)\b", top).group(1))
    tap("安装更新", tree)

    deadline = time.monotonic() + 100
    installed_at = None
    while time.monotonic() < deadline:
        tree = hierarchy("release-system-installer")
        labels = texts(tree)
        buttons = {n.get("text", "").lower(): n for n in tree.iter("node")
                   if n.get("clickable") == "true" and n.get("enabled") == "true"}
        if not observation["installer_confirmed"]:
            confirm = next((word for word in ("update", "install") if word in buttons), None)
            if confirm:
                tasks = adb("shell", "dumpsys", "activity", "activities")
                (out / "release-system-installer-tasks.txt").write_text(tasks, encoding="utf-8")
                top = next(line for line in tasks.splitlines() if "topResumedActivity" in line or "mResumedActivity" in line)
                observation["installer_task"] = int(re.search(r"\bt(\d+)\b", top).group(1))
                tap(buttons[confirm].get("text"), tree)
                observation["installer_confirmed"] = True
                continue
        else:
            if version() == target_code:
                observation["installed"] = True
                if installed_at is None:
                    installed_at = time.monotonic()
            if "open" in buttons:
                tap(buttons["open"].get("text"), tree)
                observation["open_clicked"] = True
                try:
                    wait_label("设置与工具", "release-update-opened", 30)
                    time.sleep(5)
                    locate(hierarchy("release-update-stable"), "设置与工具")
                    tasks = adb("shell", "dumpsys", "activity", "activities")
                    (out / "release-update-after-tasks.txt").write_text(tasks, encoding="utf-8")
                    observation["stable_home"] = any(MAIN in line for line in tasks.splitlines()
                        if "topResumedActivity" in line or "mResumedActivity" in line)
                except RuntimeError:
                    observation["stable_home"] = False
                break
            if installed_at is not None and time.monotonic() - installed_at >= 20:
                break
        time.sleep(2)
    observation["installed_code"] = version()
    (out / "release-update-end-tasks.txt").write_text(adb("shell", "dumpsys", "activity", "activities"), encoding="utf-8")
    observation["outcome"] = (
        "open_reached_stable_home" if observation["stable_home"] else
        "open_did_not_reach_stable_home" if observation["open_clicked"] else
        "installer_open_missing_after_replacement" if observation["installed"] else "installation_not_completed")
    report["result"] = "historical production update observed: " + observation["outcome"]
    if not observation["installer_confirmed"] or not observation["installed"]:
        raise RuntimeError("Historical production update did not reach package replacement")


def build_external_fixture(adb, command):
    sdk = Path(os.environ["ANDROID_HOME"])
    build = sdk / "build-tools/35.0.0"
    android = sdk / "platforms/android-35/android.jar"
    work = Path(os.environ["RUNNER_TEMP"]) / "bybus-installer-probe"
    work.mkdir(exist_ok=True)
    classes = work / "classes"
    classes.mkdir(exist_ok=True)
    source = Path("scripts/installer-probe")
    command("javac", "-source", "17", "-target", "17", "-classpath", str(android),
            "-d", str(classes), str(source / "InstallProbe.java"))
    command(str(build / "d8"), "--lib", str(android), "--output", str(work),
            *map(str, classes.rglob("*.class")))
    unsigned = work / "unsigned.apk"
    command(str(build / "aapt2"), "link", "-I", str(android), "--manifest",
            str(source / "AndroidManifest.xml"), "-o", str(unsigned))
    with zipfile.ZipFile(unsigned, "a") as archive:
        archive.write(work / "classes.dex", "classes.dex")
    key = work / "temporary.jks"
    command("keytool", "-genkeypair", "-keystore", str(key), "-alias", "probe", "-keyalg", "RSA",
            "-storepass", "temporary", "-keypass", "temporary", "-dname", "CN=Disposable probe", "-validity", "1")
    probe = work / "probe.apk"
    command(str(build / "apksigner"), "sign", "--ks", str(key), "--ks-pass", "pass:temporary",
            "--out", str(probe), str(unsigned))
    fixture = "io.github.gravere.bybus.installprobe"
    if "Success" not in adb("install", "--no-streaming", str(probe), timeout=90):
        raise RuntimeError("Installer fixture installation failed")
    adb("shell", "appops", "set", fixture, "REQUEST_INSTALL_PACKAGES", "allow")
    return fixture


def inspect_external_install(apk, adb, command, hierarchy, locate, tap, report, out):
    """Install the full release from an ordinary external caller, before any BYBUS launch."""
    if os.environ.get("GITHUB_ACTIONS") != "true" or adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("External installation requires the disposable CI emulator")

    def version():
        match = re.search(r"\bversionCode=(\d+)\b", adb("shell", "dumpsys", "package", PACKAGE))
        return int(match.group(1)) if match else None

    target_code = version()
    if not target_code:
        raise RuntimeError("Release version is unknown")
    adb("uninstall", PACKAGE)
    fixture = build_external_fixture(adb, command)
    adb("shell", "am", "start", "-W", "-n", fixture + "/.InstallProbe", "--ez", "request_result", "false", timeout=60)
    adb("root")
    adb("wait-for-device", timeout=30)
    directory = "/data/user/0/" + fixture + "/cache"
    owner = adb("shell", "stat", "-c", "%u:%g", directory).strip()
    if not re.fullmatch(r"[1-9][0-9]{4,}:[1-9][0-9]{4,}", owner):
        raise RuntimeError("External caller cache ownership is invalid")
    adb("push", str(apk), directory + "/update.apk", timeout=120)
    adb("shell", "chown", owner, directory + "/update.apk")
    adb("shell", "restorecon", "-F", directory + "/update.apk")
    adb("unroot")
    adb("wait-for-device", timeout=30)
    adb("logcat", "-c")
    cases = []
    report["external_full_install"] = cases
    for mode in ("fresh", "full-overwrite"):
        case = dict(mode=mode, target_code=target_code, prior_installed_code=version(),
            source="external ordinary startActivity with original full Release APK",
            confirmed=False, open_clicked=False, stable_home=False,
            game_launched=False, account_accessed=False)
        cases.append(case)
        if mode == "full-overwrite":
            tap("设置与工具", hierarchy("full-overwrite-home"))
            tap("更新", hierarchy("full-overwrite-settings"))
            locate(hierarchy("full-overwrite-source-page"), "检查应用更新")
            # Keep the production update sheet in the task history during replacement.
            adb("shell", "am", "start", "-W", "-n", fixture + "/.InstallProbe", "--ez", "request_result", "false", timeout=60)
        tap("Install BYBUS", hierarchy(mode + "-external-caller"))
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            tree = hierarchy(mode + "-system-installer")
            buttons = {n.get("text", "").lower(): n for n in tree.iter("node")
                       if n.get("clickable") == "true" and n.get("enabled") == "true"}
            if not case["confirmed"]:
                confirm = next((word for word in ("install", "update") if word in buttons), None)
                if confirm:
                    (out / (mode + "-installer-tasks.txt")).write_text(adb("shell", "dumpsys", "activity", "activities"), encoding="utf-8")
                    tap(buttons[confirm].get("text"), tree)
                    case["confirmed"] = True
            elif "open" in buttons:
                tap(buttons["open"].get("text"), tree)
                case["open_clicked"] = True
                home_deadline = time.monotonic() + 30
                while time.monotonic() < home_deadline:
                    try:
                        locate(hierarchy(mode + "-opened"), "设置与工具")
                        time.sleep(5)
                        locate(hierarchy(mode + "-stable"), "设置与工具")
                        tasks = adb("shell", "dumpsys", "activity", "activities")
                        (out / (mode + "-opened-tasks.txt")).write_text(tasks, encoding="utf-8")
                        case["stable_home"] = any(MAIN in line for line in tasks.splitlines()
                            if "topResumedActivity" in line or "mResumedActivity" in line)
                        break
                    except RuntimeError:
                        time.sleep(3)
                break
            time.sleep(2)
        case["installed_code"] = version()
        (out / (mode + "-end-tasks.txt")).write_text(adb("shell", "dumpsys", "activity", "activities"), encoding="utf-8")
        if not case["confirmed"] or case["installed_code"] != target_code:
            raise RuntimeError("External full installation did not complete: " + mode)
        case["outcome"] = "stable_home" if case["stable_home"] else "no_stable_home_after_install"
        if not case["stable_home"]:
            report["result"] = "external full installation observed: " + mode + " did not reach stable home"
            return
    report["result"] = "external fresh/full overwrite Open reached stable home in this emulator"


def inspect(apk, adb, command, hierarchy, locate, tap, report, out,
            release, request, verifier, install_probe):
    if os.environ.get("GITHUB_ACTIONS") != "true" or adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Installation fixtures are restricted to the disposable CI emulator")

    def version():
        text = adb("shell", "dumpsys", "package", PACKAGE)
        return int(re.search(r"\bversionCode=(\d+)\b", text).group(1))

    def root_shell():
        adb("root")
        adb("wait-for-device", timeout=30)
        if adb("shell", "id", "-u").strip() != "0":
            raise RuntimeError("Cannot read disposable emulator evidence")

    def provide_fixture_apk(package):
        # Android 11 scoped storage blocks shell access to Android/data. Seed only an
        # existing fixture cache and restore its real owner/label before app code reads it.
        if package not in (PACKAGE, "io.github.gravere.bybus.installprobe"):
            raise RuntimeError("Unknown fixture package")
        root_shell()
        directory = "/data/user/0/" + package + "/cache"
        adb("shell", "test", "-d", directory)
        owner = adb("shell", "stat", "-c", "%u:%g", directory).strip()
        if not re.fullmatch(r"[1-9][0-9]{4,}:[1-9][0-9]{4,}", owner):
            raise RuntimeError("Fixture cache was not created by the application UID")
        path = directory + "/update.apk"
        adb("push", str(apk), path, timeout=120)
        adb("shell", "chown", owner, path)
        adb("shell", "restorecon", "-F", path)

    def wait_home(stem):
        deadline = time.monotonic() + 35
        while True:
            try:
                locate(hierarchy(stem), "设置与工具")
                break
            except RuntimeError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(1)
        tasks = adb("shell", "dumpsys", "activity", "activities")
        (out / (stem + "-tasks.txt")).write_text(tasks, encoding="utf-8")
        resumed = [line for line in tasks.splitlines() if "ResumedActivity" in line or "topResumedActivity" in line]
        if not any(MAIN in line for line in resumed):
            raise RuntimeError("BYBUS homepage is not resumed")

    def drive_installer(stem, cancel=False):
        deadline = time.monotonic() + 100
        confirmed = False
        while time.monotonic() < deadline:
            tree = hierarchy(stem)
            buttons = {n.get("text", "").lower(): n for n in tree.iter("node")
                       if n.get("clickable") == "true" and n.get("enabled") == "true"}
            if any("problem parsing" in n.get("text", "").lower() for n in tree.iter("node")):
                (out / (stem + "-error.txt")).write_text(adb("logcat", "-d", "-t", "800",
                    "PackageInstaller:I", "InstallStart:I", "InstallStaging:I", "PackageUtil:I",
                    "PackageInstallerActivity:I", "BYBUSInstallProbe:I", "AndroidRuntime:E", "*:S"), encoding="utf-8")
                raise RuntimeError("Installer could not parse the fixture-provided APK")
            if not confirmed:
                action = next((name for name in ("update", "install") if name in buttons), None)
                if action:
                    tasks = adb("shell", "dumpsys", "activity", "activities")
                    (out / (stem + "-tasks.txt")).write_text(tasks, encoding="utf-8")
                    if stem.startswith("in-app"):
                        top = next(line for line in tasks.splitlines() if "topResumedActivity" in line or "mResumedActivity" in line)
                        task = int(re.search(r"\bt(\d+)\b", top).group(1))
                        if task == report["in_app_source_task"]:
                            raise RuntimeError("Self-update installer still shares the caller's task")
                        report["in_app_installer_task"] = task
                    if cancel:
                        tap(buttons["cancel"].get("text"), tree)
                        return
                    tap(buttons[action].get("text"), tree)
                    confirmed = True
                    continue
            elif "open" in buttons:
                tap(buttons["open"].get("text"), tree)
                wait_home(stem + "-opened")
                return
            time.sleep(1)
        report["installer_failure"] = dict(stage=stem, installed_code=version(), confirmed=confirmed)
        raise RuntimeError("Installer did not complete " + stem)

    target_code = version()
    adb("logcat", "-c")
    # Exercise the real implicit call; never replace this check with an explicit component.
    result = adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-c",
                 "android.intent.category.LAUNCHER", "-p", PACKAGE, timeout=60)
    if "Error" in result or "unable to resolve" in result:
        raise RuntimeError("Implicit MAIN/LAUNCHER entry did not resolve")
    wait_home("implicit-main")
    adb("shell", "am", "start", "-W", "-n", MAIN, timeout=60)
    wait_home("explicit-main")
    report["launcher_entries"] = dict(implicit=True, explicit=True)

    # Root reads fixture evidence only in this empty emulator, never in a user's environment.
    root_shell()

    previous = None
    if release["draft"]:
        previous = json.loads(request("https://api.github.com/repos/gravere/BYBUS/releases/latest", 512 * 1024))
        assets = {a["name"]: a for a in previous["assets"]}
        sums = request(assets["SHA256SUMS.txt"]["url"], 32 * 1024, asset=True).decode()
        digest = re.findall(r"^([a-f0-9]{64})\s+\*?BYBUS-Android\.apk\s*$", sums, re.M)
        data = request(assets["BYBUS-Android.apk"]["url"], 200 * 1024 * 1024, asset=True)
        if len(digest) != 1 or hashlib.sha256(data).hexdigest() != digest[0] or len(data) != assets["BYBUS-Android.apk"]["size"]:
            raise RuntimeError("Previous release APK digest mismatch")
        base = Path(os.environ["RUNNER_TEMP"]) / "previous-BYBUS.apk"
        base.write_bytes(data)
        del data
        signatures = command(str(verifier), "verify", "--print-certs", str(base), timeout=60)
        if set(re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)", signatures)) != {CERT}:
            raise RuntimeError("Previous release signer mismatch")
        adb("uninstall", PACKAGE)
        if "Success" not in adb("install", "--no-streaming", str(base), timeout=150):
            raise RuntimeError("Could not install the previous official release")
        report["previous_release"] = dict(tag=previous["tag_name"], sha256=digest[0])

    base_code = version()
    if previous and base_code >= target_code:
        raise RuntimeError("Cross-version inspection requires a newer candidate")
    adb("shell", "am", "start", "-W", "-n", MAIN, timeout=60)
    wait_home("before-external-update")
    sentinel = Path(os.environ["RUNNER_TEMP"]) / "upgrade-sentinel.txt"
    sentinel.write_text("BYBUS_CLOUD_UPGRADE_SENTINEL", encoding="ascii")
    # Use an existing app-created directory; adb push must never create root-owned app directories.
    sentinel_path = "/data/user/0/" + PACKAGE + "/no_backup/diagnostics/upgrade-sentinel.txt"
    adb("shell", "test", "-d", str(Path(sentinel_path).parent))
    adb("push", str(sentinel), sentinel_path)
    fixture = build_external_fixture(adb, command)
    adb("shell", "am", "start", "-W", "-n", fixture + "/.InstallProbe", timeout=60)
    provide_fixture_apk(fixture)
    tree = hierarchy("external-caller")
    label = next(n.get("text") for n in tree.iter("node") if n.get("text", "").lower() == "install bybus")
    tap(label, tree)
    drive_installer("external-update")
    root_shell()
    if version() != target_code or adb("shell", "cat", sentinel_path).strip() != sentinel.read_text():
        raise RuntimeError("External replacement did not preserve version or fixture data")
    report["install_open"] = dict(passed=True, from_code=base_code, to_code=target_code,
        external_caller=True, actual_installer_ui=True, same_version_replacement=base_code == target_code,
        data_preserved=True, bybus_main_resumed=True, game_launched=False, vivo_installer_tested=False)

    if release["draft"]:
        adb("uninstall", PACKAGE)
        fixture_digest = install_probe(release, verifier, "bybus-install-base.apk")
        fixture_code = version()
        if fixture_code >= target_code:
            raise RuntimeError("In-app fixture must have a lower package version")
        adb("shell", "appops", "set", PACKAGE, "REQUEST_INSTALL_PACKAGES", "allow")
        adb("shell", "am", "start", "-W", "-n", PROBE, timeout=60)
        adb("shell", "test", "-d", str(Path(sentinel_path).parent))
        adb("push", str(sentinel), sentinel_path)
        provide_fixture_apk(PACKAGE)

        def tap_fixture(stem):
            tree = hierarchy(stem)
            tasks = adb("shell", "dumpsys", "activity", "activities")
            top = next(line for line in tasks.splitlines() if "topResumedActivity" in line or "mResumedActivity" in line)
            report["in_app_source_task"] = int(re.search(r"\bt(\d+)\b", top).group(1))
            label = next(n.get("text") for n in tree.iter("node")
                         if n.get("text", "").lower() == "install verified update")
            tap(label, tree)

        tap_fixture("in-app-caller")
        drive_installer("in-app-cancel", cancel=True)
        root_shell()
        deadline = time.monotonic() + 20
        cancelled_path = "/data/user/0/" + PACKAGE + "/files/install-returned.txt"
        while True:
            cancelled = adb("shell", "cat", cancelled_path, check=False)
            if "phase=RETURNED" in cancelled:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("Installer cancellation was not delivered")
            time.sleep(1)
        if "outcome=not-updated" not in cancelled or version() != fixture_code:
            raise RuntimeError("Cancellation was incorrectly reported as installed")
        (out / "cancelled-install.txt").write_text(cancelled, encoding="utf-8")

        tap_fixture("in-app-retry")
        drive_installer("in-app-update")
        receipt_path = "/data/user/0/" + PACKAGE + "/no_backup/diagnostics/app-install.properties"
        deadline = time.monotonic() + 15
        while True:
            receipt = adb("shell", "cat", receipt_path)
            if re.search(r"firstHomeFrameAt=[1-9][0-9]*", receipt):
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("New process did not record the committed home frame")
            time.sleep(1)
        if version() != target_code or f"observedCode={target_code}" not in receipt or adb("shell", "cat", sentinel_path).strip() != sentinel.read_text():
            raise RuntimeError("In-app replacement lost version, receipt or fixture data")
        (out / "completed-install.properties").write_text(receipt, encoding="utf-8")
        report["in_app_update"] = dict(passed=True, from_code=fixture_code, to_code=target_code,
            source="current-source Debug fixture with lower package version", fixture_sha256=fixture_digest,
            cancel_returned=True, retry_installed=True, data_preserved=True, new_process_first_frame=True,
            installer_in_separate_task=True)

        inspect_startup_records(adb, hierarchy, locate, report, out, release, verifier, install_probe,
                                home_present=True, expected_phase="INSTALLER_HANDOFF")

    (out / "installer-log.txt").write_text(adb("logcat", "-d", "-t", "1500", "ActivityTaskManager:I",
        "ActivityManager:I", "PackageInstaller:I", "InstallSuccess:I", "LimbusZhCNApp:I", "LimbusZhCN:I",
        "AndroidRuntime:E", "*:S"), encoding="utf-8")
    report["checks"].append("Implicit and explicit launcher entries, actual system installer Open and retained fixture data")
    report["result"] = "installation checks completed; OEM behavior and 70036 not tested"


def inspect_startup_records(adb, hierarchy, locate, report, out, release, verifier, install_probe,
                            home_present=False, expected_phase="HOME_FRAME"):
    """Check only the public startup/exit APIs after installation flows have passed."""
    if os.environ.get("GITHUB_ACTIONS") != "true" or adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Startup fixture is restricted to the disposable CI emulator")
    if not home_present:
        adb("shell", "am", "start", "-W", "-n", MAIN, timeout=60)
        locate(hierarchy("startup-home"), "设置与工具")
    # Replacing a foreground app on Android 17 starts a SystemUI package-update
    # activity that may relaunch Main and race the probe. Stop the fixture first.
    adb("shell", "am", "force-stop", PACKAGE)
    diagnostic_digest = install_probe(release, verifier)
    adb("root")
    adb("wait-for-device", timeout=30)
    # This Activity intentionally finishes before drawing a window. am -W would
    # wait for a frame that does not exist; wait for its result file instead.
    launch = adb("shell", "am", "start", "-n", PROBE, "--es", "mode", "diagnostics",
                 "--es", "private_fixture", "BYBUS_SHOULD_NOT_BE_EXPORTED", timeout=30)
    (out / "startup-probe-launch.txt").write_text(launch, encoding="utf-8")
    deadline = time.monotonic() + 30
    while True:
        raw = adb("shell", "cat", "/data/user/0/" + PACKAGE + "/files/startup-probe.json", check=False)
        if raw.strip().startswith("{"):
            result = json.loads(raw)
            break
        if time.monotonic() >= deadline:
            raise RuntimeError("Startup diagnostic probe did not write its result")
        time.sleep(1)
    (out / "startup-diagnostics.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    if "BYBUS_SHOULD_NOT_BE_EXPORTED" in json.dumps(result):
        raise RuntimeError("Startup diagnostics leaked Intent extras")
    if result["sdk"] >= 35:
        if "state=first-frame-drawn" not in result["starts"] or "mainComponent=true" not in result["starts"]:
            raise RuntimeError("Own startup history is missing the rendered main entry")
    elif "unavailable: startup history requires" not in result["starts"]:
        raise RuntimeError("Older Android did not report the API fallback")
    if not any(expected_phase in e["checkpoint"] for e in result["exits"]):
        raise RuntimeError("The previous process checkpoint was not retained")
    report["startup_diagnostics"] = dict(passed=True, startup_api=result["sdk"] >= 35,
        exit_checkpoint=True, checkpoint_phase=expected_phase, intent_extras_exported=False, probe_sha256=diagnostic_digest)
    report["checks"].append("Own startup history, first frame, historical exit checkpoint and Intent filtering")
    report["result"] = "startup diagnostics completed; no game or account accessed"
