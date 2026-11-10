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


def inspect(apk, adb, command, hierarchy, locate, tap, report, out,
            release, request, verifier, install_probe):
    if os.environ.get("GITHUB_ACTIONS") != "true" or adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Installation fixtures are restricted to the disposable CI emulator")

    def version():
        text = adb("shell", "dumpsys", "package", PACKAGE)
        return int(re.search(r"\bversionCode=(\d+)\b", text).group(1))

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
            if not confirmed:
                action = next((name for name in ("update", "install") if name in buttons), None)
                if action:
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
    adb("root")
    adb("wait-for-device", timeout=30)
    if adb("shell", "id", "-u").strip() != "0":
        raise RuntimeError("Cannot read disposable emulator evidence")

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
    sentinel_path = "/data/user/0/" + PACKAGE + "/files/upgrade-sentinel.txt"
    adb("push", str(sentinel), sentinel_path)
    fixture = build_external_fixture(adb, command)
    adb("shell", "am", "start", "-W", "-n", fixture + "/.InstallProbe", timeout=60)
    adb("push", str(apk), "/sdcard/Android/data/" + fixture + "/files/update.apk", timeout=120)
    tree = hierarchy("external-caller")
    label = next(n.get("text") for n in tree.iter("node") if n.get("text", "").lower() == "install bybus")
    tap(label, tree)
    drive_installer("external-update")
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
        adb("push", str(apk), "/sdcard/Android/data/" + PACKAGE + "/files/update.apk", timeout=120)
        adb("push", str(sentinel), sentinel_path)

        def tap_fixture(stem):
            tree = hierarchy(stem)
            label = next(n.get("text") for n in tree.iter("node")
                         if n.get("text", "").lower() == "install verified update")
            tap(label, tree)

        tap_fixture("in-app-caller")
        drive_installer("in-app-cancel", cancel=True)
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
            cancel_returned=True, retry_installed=True, data_preserved=True, new_process_first_frame=True)

        diagnostic_digest = install_probe(release, verifier)
        adb("shell", "am", "start", "-W", "-n", PROBE, "--es", "mode", "diagnostics",
            "--es", "private_fixture", "BYBUS_SHOULD_NOT_BE_EXPORTED", timeout=60)
        result = json.loads(adb("shell", "cat", "/data/user/0/" + PACKAGE + "/files/startup-probe.json"))
        if "BYBUS_SHOULD_NOT_BE_EXPORTED" in json.dumps(result):
            raise RuntimeError("Startup diagnostics leaked Intent extras")
        if result["sdk"] >= 35:
            if "state=first-frame-drawn" not in result["starts"] or "mainComponent=true" not in result["starts"]:
                raise RuntimeError("Own startup history is missing the rendered main entry")
        elif "unavailable: startup history requires" not in result["starts"]:
            raise RuntimeError("Older Android did not report the API fallback")
        if not any("INSTALLER_HANDOFF" in e["checkpoint"] for e in result["exits"]):
            raise RuntimeError("Process replacement did not retain the installer checkpoint")
        (out / "startup-diagnostics.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        report["startup_diagnostics"] = dict(passed=True, startup_api=result["sdk"] >= 35,
            exit_checkpoint=True, intent_extras_exported=False, probe_sha256=diagnostic_digest)

    (out / "installer-log.txt").write_text(adb("logcat", "-d", "-t", "1500", "ActivityTaskManager:I",
        "ActivityManager:I", "PackageInstaller:I", "InstallSuccess:I", "LimbusZhCNApp:I", "LimbusZhCN:I",
        "AndroidRuntime:E", "*:S"), encoding="utf-8")
    report["checks"].append("Implicit and explicit launcher entries, actual system installer Open and retained fixture data")
    report["result"] = "installation checks completed; OEM behavior and 70036 not tested"
