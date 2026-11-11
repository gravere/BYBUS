"""Inspect an unmodified public APK; no Google account, game data, or purchase."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import struct
import time
import urllib.request
import urllib.error
import urllib.parse
import xml.etree.ElementTree as ET

PACKAGE = "io.github.gravere.bybus"
NAMESPACE = "com.example.limbuszhcn"
CERT = "d50bbcfb234692d5d5f90427499158608c5a4c13adb7f99a78d9b5f1af44d55b"
OUT = Path("runtime-output")
OUT.mkdir(exist_ok=True)
REPORT = {"checks": [], "payment_runtime": "not tested", "native_arm64_16k": "not tested"}
REPORT["profile"] = os.environ.get("RUNTIME_PROFILE", "android11")


def command(*args, timeout=30, binary=False, check=True):
    result = subprocess.run(args, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"{args[0]} exited {result.returncode}: "
                           + result.stderr.decode("utf-8", "replace")[-1200:])
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def adb(*args, **kwargs):
    return command("adb", "-s", "emulator-5554", *args, **kwargs)


def request(url, maximum, asset=False):
    headers = {"User-Agent": "BYBUS-Runtime-Inspection"}
    if url.startswith("https://api.github.com/repos/gravere/BYBUS/"):
        token = os.environ.get("GITHUB_TOKEN")
        if token:
            headers["Authorization"] = "Bearer " + token
        headers["Accept"] = "application/octet-stream" if asset else "application/vnd.github+json"

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, hdrs, newurl):
            return None

    req = urllib.request.Request(url, headers=headers)
    try:
        response = urllib.request.build_opener(NoRedirect).open(req, timeout=30)
    except urllib.error.HTTPError as redirect:
        if redirect.code not in (301, 302, 303, 307, 308):
            raise
        destination = urllib.parse.urljoin(url, redirect.headers["Location"])
        parsed = urllib.parse.urlparse(destination)
        if parsed.scheme != "https" or parsed.hostname not in ("github.com", "release-assets.githubusercontent.com"):
            raise RuntimeError("Unexpected release download redirect")
        # The signed asset URL needs no bearer token. Never forward repository credentials.
        return request(destination, maximum)
    with response:
        body = response.read(maximum + 1)
    if len(body) > maximum:
        raise RuntimeError("Download exceeds the expected limit")
    return body


def ready():
    deadline = time.monotonic() + 300
    streak = 0
    last_state = {}
    while time.monotonic() < deadline:
        try:
            user = adb("shell", "am", "get-started-user-state", "0", timeout=12).strip()
            services = [adb("shell", "service", "check", s, timeout=8) for s in ("package", "activity", "window")]
            last_state = dict(user=user, services=services)
            ok = user == "RUNNING_UNLOCKED" and all("found" in s.lower() and "not found" not in s.lower() for s in services)
            streak = streak + 1 if ok else 0
            if streak == 2:
                REPORT["user_state"] = user
                return
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            last_state = dict(error=str(error))
            streak = 0
        time.sleep(3)
    REPORT["boot_last_state"] = last_state
    for name, args in (
        ("boot-devices.txt", ("adb", "devices", "-l")),
        ("boot-properties.txt", ("adb", "-s", "emulator-5554", "shell", "getprop")),
        ("boot-log.txt", ("adb", "-s", "emulator-5554", "logcat", "-d", "-b", "all", "-t", "300")),
    ):
        try:
            (OUT / name).write_text(command(*args, timeout=10, check=False), encoding="utf-8")
        except subprocess.TimeoutExpired:
            pass
    raise RuntimeError("Android did not reach a stable unlocked state within five minutes")


def hierarchy(name):
    adb("shell", "uiautomator", "dump", "/sdcard/bybus-window.xml", timeout=35)
    raw = adb("shell", "cat", "/sdcard/bybus-window.xml")
    raw = raw[raw.index("<?xml"):]
    (OUT / f"{name}.xml").write_text(raw, encoding="utf-8")
    return ET.fromstring(raw)


def locate(tree, value):
    for node in tree.iter("node"):
        labels = [node.get("text", ""), node.get("content-desc", "")]
        if any(value == label or value in label.splitlines() for label in labels):
            bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if bounds:
                x1, y1, x2, y2 = map(int, bounds.groups())
                if x2 > x1 and y2 > y1:
                    return (x1 + x2) // 2, (y1 + y2) // 2
    raise RuntimeError(f"Visible UI target missing: {value}")


def tap(value, tree):
    x, y = locate(tree, value)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1)


def clickable_bounds(tree, label, allow_disabled=False):
    parents = {child: parent for parent in tree.iter() for child in parent}
    target = next(n for n in tree.iter("node") if any(label in n.get(key, "").splitlines()
                  for key in ("text", "content-desc")))
    def is_control(node):
        return node.get("clickable") == "true" or (allow_disabled and node.get("class") == "android.widget.Button")
    while not is_control(target) and target in parents:
        target = parents[target]
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", target.get("bounds", ""))
    if not bounds or not is_control(target):
        raise RuntimeError("Missing clickable bounds: " + label)
    return tuple(map(int, bounds.groups()))


def inspect_home_alignment(tree, width, height, density=1, landscape=False):
    action = clickable_bounds(tree, "检测游戏")
    status = clickable_bounds(tree, "本地汉化")
    project = clickable_bounds(tree, "都市零协会")
    header = clickable_bounds(tree, "设置与工具")
    footer = clickable_bounds(tree, "使用说明")
    if abs((action[0] + action[2]) / 2 - width / 2) > 2 or action[3] > height:
        raise RuntimeError("Primary action is not centered and fully visible")
    if action[2] - action[0] >= width * .9:
        raise RuntimeError("Primary action still spans the entire screen")
    if abs((status[2] - status[0]) - (project[2] - project[0])) > 1:
        raise RuntimeError("Summary card widths differ")
    if landscape:
        if abs(status[1] - project[1]) > 1 or abs(status[3] - project[3]) > 1:
            raise RuntimeError("Landscape summary cards do not share top and bottom edges")
        if not status[2] < action[0] < action[2] < project[0]:
            raise RuntimeError("Launch action is not between landscape cards")
        middle_error = abs((action[1] + action[3] - status[1] - status[3]) / 2) / density
    else:
        if not status[3] < action[1] < action[3] < project[1]:
            raise RuntimeError("Launch action is not between portrait cards")
        middle_error = abs((action[1] + action[3] - status[3] - project[1]) / 2) / density
    if middle_error > 2:
        raise RuntimeError("Launch action is not centered in the space between cards")
    above = (status[1] - header[3]) / density
    below = (footer[1] - project[3]) / density
    if not landscape:
        card_gap = (project[1] - status[3]) / density
        if card_gap > 400 or min(above, below) < 0 or abs(above - below - 12) > 2:
            raise RuntimeError(f"Portrait spacing is not bounded and balanced: {above=} {below=} {card_gap=}")
        if os.environ.get("RUNTIME_MODE") in ("button-ui", "detail-rest") and not 120 <= card_gap <= 320:
            raise RuntimeError(f"Compact portrait cards leave an excessive launch gap: {card_gap=}")
    REPORT.setdefault("home_layouts", []).append(dict(width=width, height=height, middle_error_dp=middle_error,
        horizontal=landscape,
        status_bounds=status, project_bounds=project, action_bounds=action,
        above_group_dp=above, below_group_dp=below, usage_bounds=footer))


def inspect_automatic_notice():
    # Run before opening the update page. Two real responses prove foreground polling,
    # independently of the explicit check button and without inventing a future release.
    deadline = time.monotonic() + 75
    while True:
        logs = adb("logcat", "-d", "-s", "BYBUSUpdate:I", "*:S")
        codes = re.findall(r"Notice checked: (\d+)", logs)
        if len(codes) >= 2:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError("Automatic notice did not refresh while remaining in the foreground")
        time.sleep(5)
    (OUT / "automatic-update-log.txt").write_text(logs, encoding="utf-8")
    REPORT["automatic_notice"] = dict(successful_checks=len(codes), version_codes=codes,
                                     manual_check_triggered=False)
    REPORT["checks"].append("automatic public metadata check on entry and during foreground stay")


def element_bounds(tree, label):
    node = next(n for n in tree.iter("node") if any(label in n.get(k, "").splitlines()
                for k in ("text", "content-desc")))
    return tuple(map(int, re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", "")).groups()))


def inspect_update_progress():
    home = hierarchy("before-update-controls")
    tap("设置与工具", home)
    parent = hierarchy("tools-before-update-controls")
    # Emulator-only cellular shaping keeps the real check busy long enough to inspect
    # the shared busy/progress footer. No production hooks or fake download percentages.
    try:
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "enable")
        # The cellular default route can take several seconds to replace Wi-Fi.
        # Shape it only after Android reports an active default network.
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            connectivity = adb("shell", "dumpsys", "connectivity")
            if re.search(r"Active default network:\s*\d+", connectivity) and "VALIDATED" in connectivity:
                break
            time.sleep(2)
        time.sleep(5)
        adb("emu", "network", "speed", "gsm")
        adb("emu", "network", "delay", "3000")
        tap("更新", parent)
        before = capture("update-progress")
        network_error = any("暂时无法连接 GitHub" in n.get("text", "") for n in before.iter("node"))
        if network_error:
            # A real network failure is not evidence of missing busy UI. Preserve
            # the limitation explicitly, then still inspect the independent tabs.
            REPORT["update_progress"] = "not observed: cellular network check failed before capture"
            original_state = "更新未完成"
            app_tab = before
        else:
            locate(before, "应用更新进度")
            first = element_bounds(before, "应用更新操作")
            adb("shell", "input", "swipe", "360", str(first[1] - 30), "360", "600", "500")
            after = capture("update-progress-scrolled")
            locate(after, "应用更新进度")
            if element_bounds(after, "应用更新操作") != first:
                raise RuntimeError("Update progress footer moved with the scrolling content")
            tap("取消更新", after)
            app_tab = capture("update-cancelled")
            original_state = "已取消，可重试"
            locate(app_tab, original_state)
            REPORT["update_progress"] = "real check progress and cancel observed"
            REPORT["checks"].append("real check progress/cancel remain fixed while content scrolls; no APK download performed")
        tap("更新类别：汉化资源", app_tab)
        translation = capture("update-translation")
        locate(translation, "尚未准备")
        locate(translation, "检查汉化更新")
        if any("检查应用更新" == n.get("content-desc") for n in translation.iter("node")):
            raise RuntimeError("Application actions leaked into the translation tab")
        tap("更新类别：BYBUS", translation)
        restored = hierarchy("update-app-restored")
        locate(restored, original_state)
        REPORT["checks"].append("update categories isolate controls and preserve app operation state")
    finally:
        adb("emu", "network", "speed", "full", check=False)
        adb("emu", "network", "delay", "none", check=False)
        adb("shell", "svc", "wifi", "enable", check=False)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(.5)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(.5)


def capture(name):
    foreground = adb("shell", "dumpsys", "activity", "activities")
    focused = [line for line in foreground.splitlines() if "mResumedActivity" in line or "topResumedActivity" in line]
    if not any(PACKAGE in line for line in focused):
        raise RuntimeError("BYBUS is not the resumed activity")
    tree = hierarchy(name)
    png = adb("exec-out", "screencap", "-p", binary=True)
    if not png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("Invalid screenshot output")
    (OUT / f"{name}.png").write_bytes(png)
    return tree


def inspect_google(parent):
    """Open the actual bundled container component, without an account or transaction."""
    tap("Google 账号与付款", parent)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        time.sleep(5)
        tree = hierarchy("google-settings")
        for label in ("登录 Google 账号", "管理 Google 账号"):
            if any(n.get("text") == label and n.get("enabled") == "true" for n in tree.iter("node")):
                tap(label, tree)
                break
        if any(n.get("package") == "com.google.android.gms" for n in tree.iter("node")):
            capture("google-settings")
            REPORT["google_component_ui"] = "container Google settings opened; no account or billing request"
            return
        if any("准备未完成" in n.get("text", "") for n in tree.iter("node")):
            for node in tree.iter("node"):
                if "准备未完成" in node.get("text", ""):
                    tap(node.get("text"), tree)
                    detail = capture("google-error-detail")
                    REPORT["google_component_error"] = [n.get("text") for n in detail.iter("node") if n.get("text")]
                    break
            break
    capture("google-settings-blocked")
    REPORT["google_component_ui"] = "did not reach container Google settings within 60 seconds"
    logs = adb("logcat", "-d", "-s", "LimbusContainer:V", "NativeEngine:V", "AndroidRuntime:V", "libc:V", timeout=15, check=False)
    (OUT / "google-component-log.txt").write_text(logs, encoding="utf-8")
    print("::warning::Container Google settings did not open; payment runtime remains unverified")


def inspect_tablet(focused=False):
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.MainActivity", timeout=30)
    tree = hierarchy("before-tablet")
    try:
        tap("关闭面板", tree)
    except RuntimeError:
        pass
    # Android 11 caps forced width at twice the AVD's initial width (720 * 2).
    # Use 160 dpi to exercise tablet dp sizes without hitting that platform clamp.
    adb("shell", "wm", "density", "160")
    for name, size, landscape, font_scale in [("tablet-small", "600x960", False, "1.0"),
                                   ("tablet-portrait", "800x1280", False, "1.0"),
                                   ("tablet-landscape", "1280x800", True, "1.0"),
                                   ("tablet-large-text", "1280x800", True, "1.5")]:
        adb("shell", "wm", "size", size)
        adb("shell", "settings", "put", "system", "font_scale", font_scale)
        time.sleep(3)
        tree = capture(name)
        actual_size = struct.unpack(">II", (OUT / f"{name}.png").read_bytes()[16:24])
        if actual_size != tuple(map(int, size.split("x"))):
            raise RuntimeError(f"Android constrained requested display {size} to {actual_size}")
        inspect_home_alignment(tree, *actual_size, landscape=landscape)
        game = locate(tree, "本地汉化")
        project = locate(tree, "都市零协会")
        if landscape:
            if project[0] <= game[0]:
                raise RuntimeError("Wide landscape did not put the project card in the right column")
        elif project[1] <= game[1]:
            raise RuntimeError("Portrait did not place the project card below the game controls")
        REPORT["checks"].append(f"{name} layout at {size}, 160 dpi, font scale {font_scale}")
    if os.environ.get("RUNTIME_MODE") in ("button-ui", "detail-rest"):
        tap("设置与工具", tree)
        tap("游戏更新", hierarchy("tablet-detail-parent"))
        detail = capture("tablet-game-update")
        locate(detail, "返回上一页")
        REPORT.setdefault("detail_pages", []).append("tablet-game-update")
        tap("关闭面板", detail)
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    time.sleep(2)
    if focused:
        return
    home = hierarchy("tablet-before-details")
    header_position = None
    for label, name in [("运行状态", "tablet-status"), ("都市零协会", "tablet-project"),
                        ("设置与工具", "tablet-settings")]:
        tap(label, home)
        detail = capture(name)
        current = (locate(detail, "关闭面板"), locate(detail, "下拉关闭面板"))
        if header_position is not None and current != header_position:
            raise RuntimeError("Tablet detail panels have inconsistent header geometry")
        header_position = current
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(.5)
        home = hierarchy("tablet-detail-return")
    REPORT["checks"].append("tablet status/project/settings use matching dialog geometry")


def visible_navigation_frames(windows, width, height):
    frames = []
    for line in windows.splitlines():
        source = re.search(r"(?:mType|type)=navigationBars\b([^}\n]*)", line)
        if not source or not re.search(r"(?:mVisible|visible)=true", source[1]):
            continue
        match = re.search(r"(?:mFrame|frame)=\[\s*(\d+),\s*(\d+)\]\[\s*(\d+),\s*(\d+)\]", source[1])
        if not match:
            match = re.search(r"(?:mFrame|frame)=Rect\(\s*(\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)", source[1])
        if match:
            frames.append(tuple(map(int, match.groups())))
    # Android 15's normal window dump exposes the visible bar window and its
    # provided-inset types rather than the expanded InsetsState sources.
    for block in re.split(r"(?m)^  Window #\d+ ", windows):
        if "type=navigationBars" not in block or not re.search(r"(?m)^\s+isVisible=true\s*$", block):
            continue
        match = re.search(r"(?m)^\s+Frames:.*?\bframe=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", block)
        if match:
            x1, y1, x2, y2 = map(int, match.groups())
            # Tablet taskbars have a larger transparent window for animations. Only
            # their declared provided inset is occupied, not that entire window.
            provider = re.search(r"type=navigationBars\b[^\n]*?insetsSize=Insets\{left=(\d+), top=(\d+), right=(\d+), bottom=(\d+)\}", block)
            if provider:
                left, top, right, bottom = map(int, provider.groups())
                if left: frames.append((x1, y1, min(x2, x1+left), y2))
                if top: frames.append((x1, y1, x2, min(y2, y1+top)))
                if right: frames.append((max(x1, x2-right), y1, x2, y2))
                if bottom: frames.append((x1, max(y1, y2-bottom), x2, y2))
            else:
                frames.append((x1, y1, x2, y2))
    return [f for f in frames if 0 <= f[0] < f[2] <= width and 0 <= f[1] < f[3] <= height]


def inspect_account_registry(release, verifier):
    digest = install_component_probe(release, verifier)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.AccountRegistryProbeActivity", timeout=90)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        tree = hierarchy("account-registry")
        labels = [node.get("text", "") for node in tree.iter("node")]
        if "ACCOUNT_REGISTRY_PASS" in labels:
            REPORT["account_registry"] = dict(first_silent_install=True, fresh_cache=True,
                server_restart=False, account_access=False, probe_sha256=digest)
            REPORT["checks"].append("Google authenticator registered on first silent install without server restart")
            return
        failures = [text for text in labels if text.startswith("ACCOUNT_REGISTRY_FAIL")]
        if failures:
            raise RuntimeError(failures[0])
        time.sleep(3)
    raise RuntimeError("First-install account registry probe timed out")


def install_component_probe(release, verifier, name="bybus-ui-probe.apk"):
    """Only used after the signed release screenshots have been saved."""
    if not release["draft"] or os.environ.get("RUNTIME_CANDIDATE") != "true":
        raise RuntimeError("Component rendering requires a private-to-collaborators release draft")
    asset = next(a for a in release["assets"] if a["name"] == name)
    data = request(asset["url"], 200 * 1024 * 1024, asset=True)
    digest = hashlib.sha256(data).hexdigest()
    if len(data) != asset["size"] or asset.get("digest") != "sha256:" + digest:
        raise RuntimeError("Component rendering APK does not match the draft asset digest")
    probe = Path(os.environ["RUNNER_TEMP"]) / name
    probe.write_bytes(data)
    del data
    signatures = command(str(verifier), "verify", "--print-certs", str(probe), timeout=60)
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", signatures)
    if {c.lower() for c in certs} != {CERT}:
        raise RuntimeError("Component rendering APK signature mismatch")
    if "Success" not in adb("install", "--no-streaming", "-r", str(probe), timeout=150):
        raise RuntimeError("Component rendering APK installation failed")
    return digest


def inspect_permissions(release, verifier, package_queries=False):
    """Run production permission services under an ordinary app UID with synthetic packages."""
    # Root is used only by this disposable emulator's shell to retrieve the private result.
    adb("root")
    adb("wait-for-device", timeout=30)
    if adb("shell", "id", "-u").strip() != "0":
        raise RuntimeError("The isolated emulator cannot read the private probe result")
    adb("shell", "am", "force-stop", PACKAGE)
    digest = install_component_probe(release, verifier)
    stem = "package-query" if package_queries else "permission"
    receiver = "PackageQueryProbeReceiver" if package_queries else "PermissionProbeReceiver"
    result_file = f"/data/user/0/{PACKAGE}/files/{stem}-result.json"
    adb("shell", "rm", "-f", result_file)
    started = adb("shell", "am", "broadcast", "--receiver-foreground", "-n",
                  f"{PACKAGE}/{NAMESPACE}.{receiver}", timeout=45)
    (OUT / f"{stem}-start.txt").write_text(started, encoding="utf-8")
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        raw = adb("shell", "cat", result_file, check=False)
        if raw.lstrip().startswith("{"):
            try:
                result = json.loads(raw)
            except json.JSONDecodeError:
                time.sleep(.5)
                continue
            (OUT / f"{stem}.json").write_text(raw, encoding="utf-8")
            REPORT["package_queries" if package_queries else "permissions"] = dict(result, probe_sha256=digest)
            if not result.get("passed"):
                raise RuntimeError("Permission checks failed: " + str(result.get("error")))
            expected_sdk = {"android11": 30, "android17": 37}[REPORT["profile"]]
            if result.get("sdk") != expected_sdk or result.get("check_count") != (40 if package_queries else 22):
                raise RuntimeError("Permission check coverage differs from the expected platform scope")
            if not all(result.get(key) for key in ("ordinary_app_uid", "production_package_proxies" if package_queries else "production_permission_services",
                                                  "synthetic_package_registry", "registry_restored")):
                raise RuntimeError("Permission check environment or registry cleanup failed")
            if not all(result["checks"].values()):
                raise RuntimeError("Not all permission checks passed")
            if package_queries:
                if not result.get("permission_metadata_consistent"):
                    raise RuntimeError("Package permission metadata checks did not pass")
                inspect_framework_permissions()
                REPORT["checks"].append("40 production package proxy/registry checks including permission metadata; synthetic packages, no real game")
                REPORT["result"] = "package query inspection completed; game and 70036 outcome not tested"
                return
            if len(result.get("authenticator_checks", {})) != 4 or not all(result["authenticator_checks"].values()):
                raise RuntimeError("Scoped framework authenticator checks did not pass")
            REPORT["checks"].append("22 production permission checks with real host grants and temporary virtual packages")
            REPORT["checks"].append("framework authenticator Proxy/Stub: ordinary caller denied, exact server callback preserved, wrong UID/PID denied")
            REPORT["result"] = "permission contract inspection completed; game and 70036 outcome not tested"
            return
        time.sleep(1)
    raise RuntimeError("Permission contract inspection timed out")


def inspect_framework_permissions(package_jni=False):
    """Use public Android APIs in a real guest process; keep the temporary registry in the server."""
    def probe(receiver, stem, stage=None):
        result_file = f"/data/user/0/{PACKAGE}/files/{stem}-result.json"
        adb("shell", "rm", "-f", result_file)
        args = ["shell", "am", "broadcast", "--receiver-foreground", "-n", f"{PACKAGE}/{NAMESPACE}.{receiver}"]
        if stage:
            args += ["--es", "stage", stage]
        adb(*args, timeout=45)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            raw = adb("shell", "cat", result_file, check=False)
            if raw.lstrip().startswith("{"):
                try:
                    result = json.loads(raw)
                except json.JSONDecodeError:
                    time.sleep(.5)
                    continue
                (OUT / f"{stem}.json").write_text(raw, encoding="utf-8")
                if not result.get("passed"):
                    raise RuntimeError(f"{stem}: {result.get('error')}")
                return result
            time.sleep(.5)
        raise RuntimeError(f"{stem} timed out")

    try:
        prepared = probe("PackageQueryProbeReceiver", "framework-prepare", "framework-prepare")
        if not prepared.get("prepared"):
            raise RuntimeError("Framework permission fixture was not prepared")
        result = probe("FrameworkPermissionProbeReceiver", "framework-permission", "package-jni" if package_jni else None)
        REPORT["jni_package_queries" if package_jni else "framework_permissions"] = result
        if package_jni:
            expected = {"android11": (30, 8), "android17": (37, 13)}[REPORT["profile"]]
            if (result.get("sdk"), result.get("check_count")) != expected or result.get("game_started") is not False:
                raise RuntimeError("JNI package API fixture scope differs from the expected platform")
            if not all(result.get(key) for key in ("ordinary_app_uid", "server_over_binder", "native_attached_thread", "client_restored")):
                raise RuntimeError("JNI fixture did not use the required process boundary")
            if not all(result["checks"].values()):
                raise RuntimeError("JNI package API checks failed")
            return
        expected = {"android11": (30, 13), "android17": (37, 15)}[REPORT["profile"]]
        if (result.get("sdk"), result.get("check_count")) != expected:
            raise RuntimeError("Public framework permission coverage differs from the expected scope")
        if not all(result.get(key) for key in ("public_framework_api", "separate_guest_process",
                                               "server_over_binder", "ordinary_app_uid", "client_restored")):
            raise RuntimeError("Public permission checks did not use the required runtime boundary")
        if not all(result["checks"].values()):
            raise RuntimeError("Public permission checks failed")
    finally:
        cleanup = probe("PackageQueryProbeReceiver", "framework-cleanup", "framework-cleanup")
        REPORT["framework_permission_registry_restored"] = cleanup.get("registry_restored", False)
        if not REPORT["framework_permission_registry_restored"]:
            raise RuntimeError("Framework permission fixture cleanup failed")
    REPORT["checks"].append("Public Android package/context permission queries from separate guest process agree with server metadata; no real game")


def inspect_service_callback(release, verifier):
    """Exercise the platform AIDL Proxy/Stub and production delegate, not a mocked ABI."""
    # Only this disposable emulator: probe builds intentionally retain debuggable=false.
    # The probe still runs under the ordinary app UID; shell root reads only its test result.
    adb("root")
    adb("wait-for-device", timeout=30)
    if adb("shell", "id", "-u").strip() != "0":
        raise RuntimeError("The isolated emulator cannot read the private probe result")
    adb("shell", "am", "force-stop", PACKAGE)
    digest = install_component_probe(release, verifier)
    result_file = f"/data/user/0/{PACKAGE}/files/service-callback-result.json"
    adb("shell", "rm", "-f", result_file)
    started = adb("shell", "am", "broadcast", "--receiver-foreground", "-n",
                  f"{PACKAGE}/{NAMESPACE}.ServiceConnectionProbeReceiver", timeout=45)
    (OUT / "service-callback-start.txt").write_text(started, encoding="utf-8")
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        raw = adb("shell", "cat", result_file, check=False)
        if raw.lstrip().startswith("{"):
            try:
                result = json.loads(raw)
            except json.JSONDecodeError:
                time.sleep(.5)
                continue
            (OUT / "service-callback.json").write_text(raw, encoding="utf-8")
            REPORT["service_callback"] = dict(result, probe_sha256=digest)
            if not result.get("passed"):
                raise RuntimeError("Platform service callback failed: " + str(result.get("error")))
            expected = {"android15": (35, 3), "android17": (37, 4)}[REPORT["profile"]]
            if (result["sdk"], result["framework_parameters"]) != expected:
                raise RuntimeError("The image does not exercise the expected framework signature")
            required = ("platform_proxy_and_stub", "component_preserved", "service_binder_preserved",
                        "null_binding", "dead_flag", "legacy_entry_points")
            if result["callbacks"] != 5 or not all(result[key] for key in required):
                raise RuntimeError("Service callback preservation checks did not all pass")
            if expected[1] == 4 and not result["session_preserved"]:
                raise RuntimeError("Framework service session was not preserved")
            REPORT["checks"].append("platform callback ABI, Binder/session, null binding and dead flag preserved")
            REPORT["result"] = "service callback inspection completed; game runtime not tested"
            return
        time.sleep(1)
    raise RuntimeError("Platform service callback inspection timed out")


def inspect_migration(release, verifier):
    """Exercise the production provider/decoder with synthetic data, then install upstream beside BYBUS."""
    legacy = "com.example.limbuszhcn"
    install_component_probe(release, verifier, "bybus-migration-probe.apk")
    relative = "virtual/data/user/0/com.ProjectMoon.LimbusCompany/files/bybus-migration-fixture.bin"
    fixture = bytes(range(256)) * 16385
    expected = hashlib.sha256(fixture).hexdigest()
    fixtures = {relative: fixture, "files/translation-cache/fixture.json": b'{"migration":true}',
                "virtual/storage/emulated/0/Android/data/com.ProjectMoon.LimbusCompany/cache/UnityCache/Shared/fixture/__data": b"portable-game-cache",
                "virtual/data/app/system/bybus-migration-fixture.secret": b"not portable",
                "files/translation-cache/redirect-gate.pid": b"12345"}
    for path, body in fixtures.items():
        adb("shell", "run-as", legacy, "mkdir", "-p", str(Path(path).parent))
        # These are fixed, synthetic paths; no private device data or arbitrary shell input.
        result = subprocess.run(["adb", "-s", "emulator-5554", "shell", "run-as", legacy,
                                 "sh", "-c", "'cat > " + path + "'"], input=body, capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError("Cannot seed synthetic legacy files")
    # Cover a real signed release upgrade over populated old-package data.
    install_component_probe(release, verifier, "BYBUS.apk")
    denied = subprocess.run(["adb", "-s", "emulator-5554", "shell", "content", "call", "--uri",
        f"content://{legacy}.bybus.migration", "--method", "summary"], capture_output=True, timeout=30)
    denial = (denied.stdout + denied.stderr).decode("utf-8", "replace")
    if not any(word in denial for word in ("SecurityException", "Permission Denial", "not authorized")):
        raise RuntimeError("Migration provider did not reject the shell UID")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.MainActivity", timeout=90)
    time.sleep(3)
    tap("返回上一页", hierarchy("migration-defer"))
    # Return through the old app, then enter migration from the new app's persistent menu.
    adb("shell", "am", "start", "-W", "-n", f"{legacy}/{legacy}.MainActivity", timeout=30)
    time.sleep(2); tap("打开新版 BYBUS", hierarchy("migration-helper"))
    tap("设置与工具", hierarchy("migration-main"))
    tap("数据迁移", hierarchy("migration-tools"))
    locate(hierarchy("migration-reopened"), "数据迁移")
    capture("migration-reopened")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.MigrationProbeActivity", timeout=90)
    time.sleep(3)
    tap("开始迁移", hierarchy("migration-ready"))
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        view = hierarchy("migration-progress")
        if any(node.get("text") == "数据已就位" for node in view.iter("node")):
            capture("migration-complete")
            break
        time.sleep(2)
    else:
        raise RuntimeError("Synthetic migration did not complete")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.MigrationProbeActivity",
        "--ez", "verify_fixture", "true", timeout=90)
    time.sleep(2)
    view = hierarchy("migration-verified")
    if not any(node.get("text") == "MIGRATION_PASS " + expected for node in view.iter("node")):
        raise RuntimeError("Imported fixture differs, completion marker missing, or excluded data copied")
    install_component_probe(release, verifier, "bybus-migration-probe.apk")
    original = adb("exec-out", "run-as", legacy, "cat", relative, binary=True)
    if hashlib.sha256(original).hexdigest() != expected:
        raise RuntimeError("Legacy data changed during migration")
    if "Success" not in adb("uninstall", legacy):
        raise RuntimeError("Cannot remove synthetic legacy helper")
    upstream_sha = "4233a4ad131dcc5f898e007ad787cdbcb3231bb506c28b0a3d4988a459b24200"
    upstream = json.loads(request("https://api.github.com/repos/BBBEANNN/limbusZhCN/releases/tags/v1.5.2", 512*1024))
    asset = next(a for a in upstream["assets"] if a.get("digest") == "sha256:" + upstream_sha)
    data = request(asset["browser_download_url"], 200*1024*1024)
    if hashlib.sha256(data).hexdigest() != upstream_sha:
        raise RuntimeError("Upstream release hash changed")
    apk = Path(os.environ["RUNNER_TEMP"])/"upstream.apk"; apk.write_bytes(data)
    certs = command(str(verifier), "verify", "--print-certs", str(apk), timeout=60)
    if "df648cd25ec9661b06a6c2d8ddf6ef8e7c3864b6b02c94a258a82c871e18705b" not in certs:
        raise RuntimeError("Unexpected upstream signer")
    if "Success" not in adb("install", "--no-streaming", str(apk), timeout=150):
        raise RuntimeError("Upstream cannot be installed alongside BYBUS")
    for package in (PACKAGE, legacy):
        if not adb("shell", "pm", "path", package).startswith("package:"):
            raise RuntimeError("Coexisting package missing: " + package)
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.MainActivity", timeout=90)
    time.sleep(3); locate(hierarchy("coexisting-home"), "设置与工具")
    REPORT["migration"] = dict(synthetic_bytes=len(fixture), sha256=expected, source_preserved=True, reopen_after_defer=True, game_cache_preserved=True,
        provider_rejects_shell=True, skipped_install_registry=True, stale_pid_excluded=True,
        target_preparation="fixture skips official game installation; real accounts not tested")
    REPORT["coexistence"] = dict(packages=[PACKAGE, legacy], upstream="v1.5.2", upstream_sha256=upstream_sha)
    REPORT["checks"].append("signed helper update retains synthetic files; private import hashes match; actual upstream APK coexists")


def scroll_to_control(tree, label, down=True):
    for _ in range(6):
        try:
            bounds = clickable_bounds(tree, label)
            if bounds[3] - bounds[1] >= 96:
                return tree
        except (RuntimeError, StopIteration):
            pass
        scroll = next(n for n in tree.iter("node") if n.get("scrollable") == "true")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", scroll.get("bounds", "")))
        start, end = (y2 - 24, y1 + 24) if down else (y1 + 24, y2 - 24)
        adb("shell", "input", "swipe", str((x1+x2)//2), str(start), str((x1+x2)//2), str(end), "350")
        tree = hierarchy("disclosure-scroll")
    raise RuntimeError("Cannot reach control: " + label)


def inspect_detail_redesign(parent):
    """Read actual release subpages and disclosures; no game or account actions."""
    pages = ["game-update-detail"]
    tap("更新", parent)
    update = capture("app-update-detail")
    pages.append("app-update-detail")
    tap("更新类别：汉化资源", update)
    capture("translation-detail")
    pages.append("translation-detail")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    parent = hierarchy("details-parent")
    locate(parent, "设置与工具")
    parent = scroll_to_control(parent, "使用说明与版本")
    tap("使用说明与版本", parent)
    about = capture("about-detail")
    pages.append("about-detail")
    locate(about, "返回上一页")
    about = scroll_to_control(about, "游戏更新与数据保存")
    tap("游戏更新与数据保存", about)
    expanded = capture("about-expanded")
    pages.append("about-expanded")
    locate(expanded, "覆盖更新 BYBUS 会保留数据。游戏新版本可能需要新的兼容修复。")
    tap("游戏更新与数据保存", scroll_to_control(expanded, "游戏更新与数据保存", down=False))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    parent = hierarchy("about-parent")
    locate(parent, "设置与工具")
    parent = scroll_to_control(parent, "数据迁移", down=False)
    tap("数据迁移", parent)
    migration = capture("migration-menu")
    pages.append("migration-menu")
    tap("返回上一页", migration)
    migration_parent = hierarchy("migration-parent")
    locate(migration_parent, "设置与工具")
    locate(migration_parent, "关闭面板")
    if not any(n.get("text") == "设置与工具" for n in migration_parent.iter("node")):
        raise RuntimeError("Migration did not restore the settings panel")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    home = hierarchy("details-home")
    REPORT["detail_pages"] = pages
    REPORT["checks"].append("release update/about subpages and migration return preserve the settings parent")
    inspect_remaining_details(home)


def inspect_remaining_details(home):
    pages = REPORT.setdefault("detail_pages", [])
    tap("运行状态", home)
    capture("runtime-detail")
    pages.append("runtime-detail")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    home = hierarchy("runtime-home")
    tap("都市零协会", home)
    tap("项目来源：翻译进度", hierarchy("project-tabs"))
    project = capture("project-progress")
    pages.append("project-progress")
    project = scroll_to_control(project, "这些统计代表什么？")
    tap("这些统计代表什么？", project)
    capture("project-explanation")
    pages.append("project-explanation")
    # Reopen at the top rather than overscrolling the body into its pull-to-dismiss gesture.
    tap("关闭面板", hierarchy("project-restore"))
    tap("都市零协会", hierarchy("project-reopen"))
    tap("项目来源：GitHub 更新", hierarchy("project-source-restore"))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    for name, night, font in [("about-dark", "yes", "1.0"), ("about-large-text", "no", "1.6")]:
        adb("shell", "cmd", "uimode", "night", night)
        adb("shell", "settings", "put", "system", "font_scale", font)
        time.sleep(2)
        tap("使用说明", hierarchy(name + "-home"))
        about = capture(name)
        pages.append(name)
        locate(about, "关闭面板")
        if font != "1.0":
            about = scroll_to_control(about, "游戏更新与数据保存")
            tap("游戏更新与数据保存", about)
            capture("about-large-expanded")
            pages.append("about-large-expanded")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    REPORT["detail_pages"] = pages
    REPORT["checks"].append("release subpages, expandable explanations, parent navigation, dark and enlarged text")


def inspect_migration_screen(release, verifier, focused=False, probe_digest=None):
    """Presentation fixtures only; real release navigation was checked before installing them."""
    digest = probe_digest or install_component_probe(release, verifier)
    cases = [("migration-ready", "ready", "720x1560", "1.0", "no"),
             ("migration-update", "update", "720x1560", "1.0", "no"),
             ("migration-copy", "copy", "720x1560", "1.0", "no"),
             ("migration-merge", "merge", "720x1560", "1.0", "no"),
             ("migration-done", "complete", "720x1560", "1.0", "no"),
             ("migration-error", "error", "720x1560", "1.0", "no"),
             ("migration-dark", "ready", "720x1560", "1.0", "yes"),
             ("migration-large-text", "ready", "640x1136", "1.6", "no"),
             ("migration-landscape", "ready", "1920x1200", "1.0", "no"),
             ("migration-portrait", "ready", "1200x1920", "1.0", "no")]
    if focused:
        cases = [case for case in cases if case[0] in (
            "migration-ready", "migration-large-text", "migration-landscape", "migration-portrait")]
    rendered = []
    disclosures = []
    overlays = adb("shell", "cmd", "overlay", "list")
    original_nav = re.search(r"\[x\]\s+(com\.android\.internal\.systemui\.navbar\.\S+)", overlays)
    try:
        for name, fixture, size, font, night in cases:
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "wm", "size", size)
            adb("shell", "wm", "density", "320")
            adb("shell", "settings", "put", "system", "font_scale", font)
            adb("shell", "cmd", "uimode", "night", night)
            if focused:
                adb("shell", "cmd", "overlay", "enable-exclusive", "--category",
                    "com.android.internal.systemui.navbar." + ("threebutton" if font != "1.0" else "gestural"))
            adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.MigrationScreenProbeActivity",
                "--es", "state", fixture, timeout=40)
            time.sleep(1)
            tree = capture(name)
            locate(tree, "数据迁移")
            if fixture in ("copy", "merge"):
                locate(tree, "50%")
                if fixture == "merge":
                    locate(tree, "16 / 32 个文件")
                back = next(n for n in tree.iter("node") if n.get("content-desc") == "返回上一页")
                parents = {child: parent for parent in tree.iter() for child in parent}
                while back.get("clickable") != "true" and back in parents:
                    back = parents[back]
                if back.get("enabled") != "false":
                    raise RuntimeError("Active migration permits leaving the page")
            elif fixture == "error":
                locate(tree, "迁移尚未完成")
                locate(tree, "继续迁移")
                if any(n.get("text") == "选择资源文件" for n in tree.iter("node")):
                    raise RuntimeError("Interrupted migration exposes a second import")
            elif fixture == "complete":
                locate(tree, "数据已就位")
                clickable_bounds(tree, "进入 BYBUS")
            elif fixture == "update":
                clickable_bounds(tree, "打开旧版 BYBUS")
            elif fixture == "ready" and font == "1.0":
                button = clickable_bounds(tree, "开始迁移")
                if button[3] - button[1] < 112:
                    raise RuntimeError("Migration primary action lost its minimum touch height")
            if name == "migration-large-text":
                # The fixed header remains reachable while a short enlarged window scrolls.
                adb("shell", "input", "swipe", "320", "900", "320", "260", "500")
                scrolled = capture("migration-large-text-scrolled")
                locate(scrolled, "返回上一页")
                clickable_bounds(scrolled, "选择资源文件")
            if name in ("migration-landscape", "migration-portrait"):
                left = element_bounds(tree, "旧版 BYBUS")
                right = element_bounds(tree, "资源文件")
                if name.endswith("landscape") and abs(left[1] - right[1]) > 4:
                    raise RuntimeError("Landscape source cards do not align")
                if name.endswith("portrait") and right[1] <= left[3]:
                    raise RuntimeError("Portrait migration should use one column")
                if name.endswith("landscape"):
                    primary = clickable_bounds(tree, "开始迁移")
                    secondary = clickable_bounds(tree, "选择资源文件")
                    if abs(primary[3] - secondary[3]) > 4:
                        raise RuntimeError("Landscape actions do not align at the bottom")
            if focused:
                tree = scroll_to_control(tree, "迁移须知")
                tap("迁移须知", tree)
                expanded = capture(name + "-expanded")
                first = element_bounds(expanded, "旧版迁移包含资源与账号配置；文件导入只补充缺少的资源。")
                last = element_bounds(expanded, "资源版本无需相同，进入游戏后会校验更新。")
                header = clickable_bounds(expanded, "返回上一页")
                viewport = next(n for n in expanded.iter("node") if n.get("class") == "android.widget.ScrollView")
                visible = tuple(map(int, re.findall(r"\d+", viewport.get("bounds", ""))))
                # Gesture and three-button navigation reserve different real insets.
                if not (first[1] >= max(header[3], visible[1]) and last[1] >= first[3] and last[3] <= visible[3] and
                        last[3]-last[1] >= 32*float(font)):
                    raise RuntimeError("Expanded notes are obscured or overlap navigation: " + name)
                collapse = scroll_to_control(expanded, "收起说明", down=False)
                tap("收起说明", collapse)
                tree = hierarchy(name + "-collapsed")
                if any(n.get("text") == "资源版本无需相同，进入游戏后会校验更新。" for n in tree.iter("node")):
                    raise RuntimeError("Collapsed notes remain in the accessibility tree")
                disclosures.append(name + "-expanded")
            rendered.append(dict(name=name, state=fixture, size=size, font_scale=font, night=night))
        # A single callback check uses the production button with a synthetic model.
        tree = scroll_to_control(tree, "开始迁移", down=False)
        tap("开始迁移", tree)
        started = hierarchy("migration-action")
        locate(started, "正在迁移数据")
        locate(started, "正在准备游戏…")
        if element_bounds(started, "正在迁移数据")[1] > clickable_bounds(started, "返回上一页", allow_disabled=True)[3] + 300:
            raise RuntimeError("Migration progress inherited the old source-page scroll")
    finally:
        adb("shell", "wm", "size", "720x1560", check=False)
        adb("shell", "settings", "put", "system", "font_scale", "1.0", check=False)
        adb("shell", "cmd", "uimode", "night", "no", check=False)
        if original_nav:
            adb("shell", "cmd", "overlay", "enable-exclusive", "--category", original_nav[1], check=False)
    REPORT["migration_screen"] = dict(apk_sha256=digest, fixtures=rendered, start_callback=True,
        progress_units=None if focused else True, pending_guard=None if focused else True, portrait_single_column=True,
        landscape_aligned=True, large_text_scroll=True, real_data_transfer=False,
        disclosure_fixtures=disclosures, disclosure_collapses=focused, status_resets_scroll=True)


def inspect_launch_control(release, verifier, probe_digest=None, feedback_only=False):
    """Render production controls with debug-only fixtures after all release screenshots."""
    digest = probe_digest or install_component_probe(release, verifier)
    adb("shell", "wm", "size", "720x1560")
    adb("shell", "wm", "density", "320")
    cases = [("launch-stage-0", 0, "no", "1.0"), ("launch-stage-1", 1, "no", "1.0"),
             ("launch-stage-2", 2, "no", "1.0"), ("launch-dark", 1, "yes", "1.0"),
             ("launch-large-text", 1, "no", "2.0"), ("launch-idle", -1, "no", "1.0"),
             ("launch-generic-busy", -2, "no", "1.0")]
    cases = [(*case, "") for case in cases] + [
        ("launch-detect", -1, "no", "1.0", "detect"),
        ("launch-detecting", -1, "no", "1.0", "detecting"),
        ("launch-compatibility", -1, "no", "1.6", "compatibility"),
        ("launch-retry", -1, "no", "1.0", "retry")]
    if feedback_only:
        cases = []
    rendered = []
    try:
        for name, step, night, font, fixture in cases:
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "cmd", "uimode", "night", night)
            adb("shell", "settings", "put", "system", "font_scale", font)
            adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.LaunchControlProbeActivity",
                "--ei", "step", str(step), "--es", "state", fixture or "default", timeout=40)
            time.sleep(2)
            tree = capture(name)
            title = {0: "准备游戏", 1: "检查汉化", 2: "正在启动", -1: "启动游戏", -2: "正在处理"}[step]
            title = {"detect": "检测游戏", "detecting": "正在检测", "compatibility": "查看设备兼容性", "retry": "重新启动"}.get(fixture, title)
            button = clickable_bounds(tree, title, allow_disabled=True)
            if button[3] - button[1] < 112:
                raise RuntimeError("Launch control clips its minimum touch height: " + name)
            if os.environ.get("RUNTIME_MODE") in ("button-ui", "detail-rest"):
                text = element_bounds(tree, title)
                # The 22dp symbol and 10dp gap belong to the centered content group.
                if abs((text[0] - 64 + text[2]) - (button[0] + button[2])) > 4 or abs((text[1] + text[3]) - (button[1] + button[3])) > 4:
                    raise RuntimeError("Launch icon and title group is not centered: " + name)
                if font == "1.0" and (abs(button[2] - button[0] - 464) > 4 or abs(button[3] - button[1] - 112) > 4):
                    raise RuntimeError("Launch control did not restore its compact size: " + name)
            progress = [n for n in tree.iter("node") if n.get("content-desc") == "启动进度"]
            if bool(progress) != (step in (0, 1, 2)):
                raise RuntimeError("Stage progress appears outside real launch stages: " + name)
            if fixture in ("detect", "compatibility", "retry"):
                if fixture == "retry" and clickable_bounds(tree, "查看失败原因")[1] < button[3]:
                    raise RuntimeError("Failure details overlap the launch button")
                tap(title, tree)
                locate(hierarchy(name + "-callback"), {"detect": "检测回调已触发", "compatibility": "详情回调已触发", "retry": "正在启动"}[fixture])
            rendered.append(dict(name=name, step=step, state=fixture, night=night, font_scale=font, button_bounds=button))
        # Observe each real stage, separately from the frozen references.
        motion_stages = []
        for stage in ([0, 1, 2] if os.environ.get("RUNTIME_MODE") in ("button-ui", "detail-rest") else [1]):
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.LaunchControlProbeActivity",
                "--ei", "step", str(stage), "--ez", "animate", "true", timeout=40)
            time.sleep(2)
            prefix = {0: "launch-prepare-motion", 1: "launch-motion", 2: "launch-start-motion"}[stage]
            locate(hierarchy(prefix), {0: "准备游戏", 1: "检查汉化", 2: "正在启动"}[stage])
            # Hierarchy dumps can sample the same phase. Capture adjacent frames directly.
            digests = []
            for name, pause in [("a", 0), ("b", .173), ("c", .347)]:
                time.sleep(pause)
                frame = adb("exec-out", "screencap", "-p", binary=True)
                if not frame.startswith(b"\x89PNG\r\n\x1a\n"):
                    raise RuntimeError("Invalid animation screenshot")
                (OUT / f"{prefix}-{name}.png").write_bytes(frame)
                digests.append(hashlib.sha256(frame).hexdigest())
            if len(set(digests)) == 1:
                raise RuntimeError("Animated launch control produced identical frames at stage " + str(stage))
            motion_stages.append(dict(stage=stage, frame_sha256=digests))
        # Exercise the real completion timer and action routing, with no game operation.
        adb("shell", "settings", "put", "secure", "accessibility_non_interactive_ui_timeout_ms", "10000")
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.LaunchControlProbeActivity",
            "--ez", "sync", "true", timeout=40)
        before = hierarchy("sync-before")
        origin = clickable_bounds(before, "准备游戏", allow_disabled=True)
        tap("完成同步", before)
        complete = capture("sync-complete")
        if clickable_bounds(complete, "游戏已同步") != origin:
            raise RuntimeError("Completion feedback moved the primary button")
        time.sleep(10.2)
        restored = hierarchy("sync-restored")
        if clickable_bounds(restored, "启动游戏") != origin:
            raise RuntimeError("Completion timeout changed button layout")
        # Repeat once to verify that the completion state remains an actionable launch control.
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.LaunchControlProbeActivity",
            "--ez", "sync", "true", timeout=40)
        tap("完成同步", hierarchy("sync-action-before"))
        tap("游戏已同步", hierarchy("sync-action-ready"))
        locate(hierarchy("sync-action-started"), "正在启动")
    finally:
        adb("shell", "settings", "delete", "secure", "accessibility_non_interactive_ui_timeout_ms", check=False)
        adb("shell", "settings", "put", "system", "font_scale", "1.0", check=False)
        adb("shell", "cmd", "uimode", "night", "no", check=False)
    REPORT["launch_component"] = dict(apk_sha256=digest, fixtures=rendered, animated_frames_differ=True,
        completion_restores=True, completion_keeps_bounds=True, completion_action=True,
        action_routes=not feedback_only, real_startup=False, readme_uses_component_fixtures=False,
        motion_frame_sha256=next(item["frame_sha256"] for item in motion_stages if item["stage"] == 1),
        motion_stages=motion_stages)
    REPORT["checks"].append("production launch control: motion and completion feedback" if feedback_only else
        "production launch control: 3 stage fixtures, dark/large text/idle/generic busy and moving highlight")


def inspect_resource_import():
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.ResourceImportProbeActivity", timeout=40)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        tree = hierarchy("resource-import")
        labels = [node.get("text", "") for node in tree.iter("node")]
        if "RESOURCE_IMPORT_PASS" in labels:
            REPORT["resource_import"] = dict(production_model=True, synthetic_archive=True,
                preserves_existing=True, source_preserved=True, pending_guard_cleared=True, real_account=False)
            return
        failure = next((label for label in labels if label.startswith("RESOURCE_IMPORT_FAIL")), None)
        if failure:
            raise RuntimeError(failure)
        time.sleep(1)
    raise RuntimeError("Resource archive import probe timed out")


def inspect_update_controls(release, verifier):
    digest = install_component_probe(release, verifier)
    cases = [("update-download", "download", "no", "1.0", "720x1560", 320),
             ("update-dark", "download", "yes", "1.0", "720x1560", 320),
             ("update-large-text", "download", "no", "2.0", "720x1560", 320),
             ("update-tablet", "download", "no", "1.0", "1280x800", 160),
             ("update-available", "available", "no", "1.0", "720x1560", 320),
             ("update-ready", "ready", "no", "1.0", "720x1560", 320),
             ("update-latest", "latest", "no", "1.0", "720x1560", 320),
             ("update-error", "error", "no", "1.0", "720x1560", 320),
             ("update-translation-active", "translation", "no", "1.0", "720x1560", 320)]
    rendered = []
    try:
        for name, case, night, font, size, density in cases:
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "wm", "size", size)
            adb("shell", "wm", "density", str(density))
            adb("shell", "cmd", "uimode", "night", night)
            adb("shell", "settings", "put", "system", "font_scale", font)
            adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.UpdateControlProbeActivity",
                "--es", "case", case, timeout=40)
            time.sleep(2)
            tree = capture(name)
            progress_label = "汉化更新进度" if case == "translation" else "应用更新进度"
            progress_nodes = [n for n in tree.iter("node") if n.get("content-desc") == progress_label]
            if bool(progress_nodes) != (case in ("download", "translation")):
                raise RuntimeError("Update track does not follow the active operation: " + name)
            if case == "download":
                locate(tree, "46%")
                action = clickable_bounds(tree, "取消更新")
                if min(action[2] - action[0], action[3] - action[1]) < 48 * density / 160:
                    raise RuntimeError("Cancel touch target is clipped: " + name)
                if name == "update-download":
                    tap("取消更新", tree)
                    cancelled = capture("update-component-cancelled")
                    locate(cancelled, "已取消，可重试")
                    if any(n.get("content-desc") == "应用更新进度" for n in cancelled.iter("node")):
                        raise RuntimeError("Progress did not clear after fixture cancellation")
            elif case in ("available", "error", "ready"):
                label = "安装更新" if case == "ready" else "下载更新"
                action = clickable_bounds(tree, label)
                if action[3] - action[1] < 52 * density / 160:
                    raise RuntimeError("Update action height is clipped: " + name)
            rendered.append(dict(name=name, case=case, night=night, font_scale=font, size=size))
    finally:
        adb("shell", "settings", "put", "system", "font_scale", "1.0", check=False)
        adb("shell", "cmd", "uimode", "night", "no", check=False)
        adb("shell", "wm", "size", "720x1560", check=False)
        adb("shell", "wm", "density", "320", check=False)
    REPORT["update_component"] = dict(apk_sha256=digest, fixtures=rendered,
        cancellation_callback=True, real_download=False, readme_uses_component_fixtures=False)
    REPORT["checks"].append("shared update action card states, cancel callback, dark/large text/tablet rendering")


def inspect_system_navigation():
    """Exercise real system navigation modes; never replace system chrome in screenshots."""
    overlays = adb("shell", "cmd", "overlay", "list")
    original = re.search(r"\[x\]\s+(com\.android\.internal\.systemui\.navbar\.\S+)", overlays)
    original_rotation = adb("shell", "wm", "user-rotation").strip().split()
    if not original_rotation or original_rotation[0] not in ("free", "lock"):
        raise RuntimeError("Could not read emulator rotation mode")
    prefix = "com.android.internal.systemui.navbar."
    for mode in ("gestural", "threebutton"):
        if prefix + mode not in overlays:
            raise RuntimeError("Emulator navigation overlay is unavailable: " + mode)
    home = hierarchy("before-system-navigation")
    tap("设置与工具", home)
    tap("更新", hierarchy("tools-system-navigation"))
    tap("更新类别：汉化资源", hierarchy("update-system-navigation"))
    cases = [("nav-gesture", "gestural", "no", "720x1560"),
             ("nav-three-button", "threebutton", "no", "720x1560"),
             ("nav-three-button-dark", "threebutton", "yes", "720x1560"),
             ("nav-three-button-landscape", "threebutton", "no", "720x1280")]
    try:
        for name, mode, night, size in cases:
            adb("shell", "cmd", "overlay", "enable-exclusive", "--category", prefix + mode)
            adb("shell", "cmd", "uimode", "night", night)
            adb("shell", "wm", "size", size)
            landscape = name.endswith("landscape")
            adb("shell", "wm", "user-rotation", "lock", "1" if landscape else "0")
            time.sleep(3)
            if landscape:
                content_tree = capture(name + "-content")
                locate(content_tree, "都市零协会")
                # Compact panels scroll their tabs and actions with the body, keeping
                # the header fixed. Reach the real footer without hiding missing text.
                tree = content_tree
                for _ in range(5):
                    try:
                        action = clickable_bounds(tree, "检查汉化更新", allow_disabled=True)
                        if action[3] - action[1] >= 96:  # 48dp at this profile's 320dpi.
                            break
                    except (RuntimeError, StopIteration):
                        pass
                    scroll = next(n for n in tree.iter("node") if n.get("scrollable") == "true")
                    bounds = tuple(map(int, re.findall(r"\d+", scroll.get("bounds", ""))))
                    x = (bounds[0] + bounds[2]) // 2
                    adb("shell", "input", "swipe", str(x), str(bounds[3] - 16),
                        str(x), str(bounds[1] + 16), "400")
                    time.sleep(1)
                    tree = hierarchy(name + "-scroll")
                locate(tree, "检查汉化更新")
                action = clickable_bounds(tree, "检查汉化更新", allow_disabled=True)
                if action[3] - action[1] < 96:
                    raise RuntimeError("Compact update action remains clipped after scrolling")
            tree = capture(name)
            width, height = struct.unpack(">II", (OUT / f"{name}.png").read_bytes()[16:24])
            windows = adb("shell", "dumpsys", "window", "windows")
            (OUT / f"{name}-window.txt").write_text(windows, encoding="utf-8")
            nav_frames = visible_navigation_frames(windows, width, height)
            if not nav_frames:
                raise RuntimeError("Could not observe visible navigation bar bounds: " + name)
            nav = max(nav_frames, key=lambda f: (f[2] - f[0]) * (f[3] - f[1]))
            controls = {}
            for label in ("关闭面板", "返回上一页", "检查汉化更新"):
                bounds = clickable_bounds(tree, label, allow_disabled=True)
                if bounds[0] < nav[2] and bounds[2] > nav[0] and bounds[1] < nav[3] and bounds[3] > nav[1]:
                    raise RuntimeError("Panel control overlaps system navigation: " + label)
                controls[label] = bounds
            REPORT.setdefault("system_navigation", []).append(dict(name=name, mode=mode, night=night,
                size=f"{width}x{height}", navigation_bounds=nav, controls=controls,
                compact_content_visible=landscape))
    finally:
        adb("shell", "cmd", "uimode", "night", "no", check=False)
        adb("shell", "wm", "size", "720x1560", check=False)
        adb("shell", "wm", "user-rotation", *original_rotation, check=False)
        adb("shell", "cmd", "overlay", "enable-exclusive", "--category",
            original[1] if original else prefix + "gestural", check=False)
        time.sleep(2)
        tap("关闭面板", hierarchy("navigation-restored"))
    REPORT["checks"].append("gesture and three-button navigation: light/dark/landscape controls avoid actual system bar bounds")


def inspect_detail_scroll(parent):
    """Record real edge gestures on the release, preserving frames for visual review."""
    # Settings is deliberately scrollable on a compact phone. Find its lower entry first.
    for attempt in range(3):
        try:
            locate(parent, "使用说明与版本")
            break
        except RuntimeError:
            if attempt == 2:
                raise
            adb("shell", "input", "swipe", "580", "1320", "580", "800", "400")
            time.sleep(1)
            parent = hierarchy("settings-scrolled")
    tap("使用说明与版本", parent)
    before = capture("about-before-scroll")
    locate(before, "关于")
    recording = subprocess.Popen(["adb", "-s", "emulator-5554", "shell", "screenrecord",
                                  "--time-limit", "10", "/sdcard/bybus-scroll.mp4"],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(1)
        for _ in range(5):
            adb("shell", "input", "swipe", "580", "1320", "580", "640", "300")
        recording.wait(timeout=15)
    finally:
        if recording.poll() is None:
            recording.terminate()
            recording.wait(timeout=5)
    if recording.returncode != 0:
        raise RuntimeError("Detail scroll video recording failed")
    adb("pull", "/sdcard/bybus-scroll.mp4", str(OUT / "detail-scroll.mp4"))
    after = capture("about-after-scroll")
    locate(after, "关于")
    locate(after, "非官方社区启动器 · LocalizeLimbusCompany")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    restored = hierarchy("settings-after-scroll")
    locate(restored, "使用说明与版本")
    REPORT["checks"].append("five upward detail gestures, footer visible, back restores parent; video requires visual review")
    tap("使用说明与版本", restored)
    child = hierarchy("about-before-pull")
    x, y = locate(child, "下拉关闭面板")
    # Upward header drags cannot lift the whole panel, and a short pull must settle back.
    for name, distance in [("header-up", -100), ("header-short-pull", 48)]:
        adb("shell", "input", "swipe", str(x), str(y), str(x), str(y + distance), "450")
        time.sleep(.5)
        current = hierarchy(name)
        if locate(current, "下拉关闭面板") != (x, y):
            raise RuntimeError("Header drag did not return to its expanded position")
        locate(current, "关于")
    # At a lower scroll position, pulling the body scrolls it rather than dismissing.
    adb("shell", "input", "swipe", "580", "1000", "580", "1100", "500")
    time.sleep(.5)
    current = hierarchy("body-scroll-back")
    if locate(current, "下拉关闭面板") != (x, y):
        raise RuntimeError("Body drag moved the panel before reaching the top")
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y + 500), "500")
    time.sleep(.5)
    home = hierarchy("home-after-header-pull")
    locate(home, "检测游戏")
    if any(n.get("content-desc") == "下拉关闭面板" for n in home.iter("node")):
        raise RuntimeError("Header pull did not dismiss the detail panel")
    # A fresh detail page starts at the top, where the body can pull the panel closed.
    tap("使用说明", home)
    adb("shell", "input", "swipe", "580", "850", "580", "1320", "500")
    time.sleep(.5)
    home = hierarchy("home-after-body-pull")
    locate(home, "检测游戏")
    if any(n.get("content-desc") == "下拉关闭面板" for n in home.iter("node")):
        raise RuntimeError("Body pull from the top did not dismiss the detail panel")
    REPORT["checks"].append("short pull restores, upward pull stays fixed, scrolled body stays inside, header and top-body pulls dismiss")


def inspect_detail_controls(release, verifier):
    inspect_tablet(focused=True)
    inspect_launch_control(release, verifier)
    # Only the notes layout needs a fixture; the draft is not publicly offered as an update yet.
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.UpdateControlProbeActivity",
        "--es", "case", "available", timeout=40)
    notes = capture("update-notes-layout")
    locate(notes, "更新内容")
    locate(notes, "下载更新")
    REPORT["detail_fixture_pages"] = ["update-notes-layout"]


def inspect_row_feedback(release, verifier, home):
    """Inspect only changed feedback bounds, using the signed app and held touch events."""
    frames = ["home"]
    feedback = []

    def hold(tree, label, name, full_row=True):
        bounds = clickable_bounds(tree, label)
        left, top, right, bottom = bounds
        if full_row and (left >= 80 or right <= 640):
            raise RuntimeError("Action remains inset from the phone detail group: " + label)
        if not full_row and right - left >= 360:
            raise RuntimeError("Auxiliary action unexpectedly fills the row: " + label)
        # Avoid an accessibility dump during the held press: it waits for ripple idleness.
        (OUT / f"{name}-rest.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        x, y = (left + right) // 2, (top + bottom) // 2
        adb("shell", "input", "touchscreen", "motionevent", "DOWN", str(x), str(y))
        try:
            time.sleep(.55)
            (OUT / f"{name}-pressed.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        finally:
            adb("shell", "input", "touchscreen", "motionevent", "CANCEL", str(x), str(y))
        time.sleep(.3)
        frames.extend([name + "-rest", name + "-pressed"])
        feedback.append(dict(name=name, label=label, bounds=bounds, full_row=full_row))
        return bounds

    hold(home, "使用说明", "help-feedback", full_row=False)
    tap("使用说明", home)
    about = capture("about-feedback")
    frames.append("about-feedback")
    edge = hold(scroll_to_control(about, "游戏更新与数据保存"), "游戏更新与数据保存", "disclosure-feedback")
    adb("shell", "input", "tap", str(edge[2] - 4), str((edge[1] + edge[3]) // 2))
    time.sleep(.6)
    expanded = capture("disclosure-edge-open")
    frames.append("disclosure-edge-open")
    locate(expanded, "游戏可从原安装渠道更新，返回后自动识别；大资源只需在 BYBUS 内下载。")
    tap("关闭面板", expanded)
    tap("运行状态", hierarchy("feedback-home"))
    runtime = capture("runtime-feedback")
    frames.append("runtime-feedback")
    hold(runtime, "游戏更新与安装", "runtime-row-feedback")
    tap("关闭面板", runtime)
    tap("都市零协会", hierarchy("feedback-project-home"))
    project = capture("project")
    frames.append("project")
    hold(scroll_to_control(project, "查看 GitHub 发布"), "查看 GitHub 发布", "project-link-feedback")
    tap("关闭面板", project)
    tap("设置与工具", hierarchy("feedback-settings-home"))
    settings = capture("settings")
    frames.append("settings")
    edge = hold(settings, "更新", "settings-row-feedback")
    adb("shell", "input", "tap", str(edge[0] + 4), str((edge[1] + edge[3]) // 2))
    time.sleep(.6)
    update = hierarchy("update-edge-open")
    locate(update, "更新类别：BYBUS")
    locate(update, "返回上一页")
    tap("返回上一页", update)
    parent = hierarchy("settings-edge-return")
    locate(parent, "版本与资源")
    tap("关闭面板", parent)
    # Dark and enlarged text still use the same row semantics and group clipping.
    adb("shell", "cmd", "uimode", "night", "yes")
    adb("shell", "settings", "put", "system", "font_scale", "1.5")
    time.sleep(2)
    tap("使用说明", hierarchy("large-feedback-home"))
    large = capture("disclosure-large-dark")
    frames.append("disclosure-large-dark")
    # The expanded contents may scroll; only exercise the visible header.
    hold(scroll_to_control(large, "游戏更新与数据保存"), "游戏更新与数据保存", "disclosure-large-feedback")
    tap("关闭面板", large)
    adb("shell", "cmd", "uimode", "night", "no")
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    time.sleep(1)
    digest = install_component_probe(release, verifier)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.UpdateControlProbeActivity",
        "--es", "case", "download", timeout=40)
    downloading = capture("update-busy-label")
    frames.append("update-busy-label")
    locate(downloading, "更新类别：BYBUS")
    locate(downloading, "应用更新进度")
    if any("BYBUS · 更新中" in n.get("text", "") or "BYBUS · 更新中" in n.get("content-desc", "")
           for n in downloading.iter("node")):
        raise RuntimeError("Busy wording remains in the BYBUS tab")
    REPORT["row_feedback"] = dict(frames=frames, controls=feedback, probe_sha256=digest,
        edge_navigation=True, edge_disclosure=True, stable_update_label=True,
        update_fixture=True, press_cancelled=True)
    REPORT["result"] = "row feedback inspection completed; held press screenshots require visual review"


def main():
    candidate = os.environ.get("RUNTIME_CANDIDATE") == "true"
    endpoint = "latest"
    if candidate:
        source = Path("app/build.gradle.kts").read_text(encoding="utf-8")
        version = re.search(r'versionName\s*=\s*"(v[0-9.]+)"', source).group(1)
        code = re.search(r'versionCode\s*=\s*(\d+)', source).group(1)
        tag = f"{version}-build.{code}"
        candidates = json.loads(request("https://api.github.com/repos/gravere/BYBUS/releases?per_page=10", 2 * 1024 * 1024))
        matching = [r for r in candidates if r["tag_name"] == tag]
        if len(matching) != 1:
            raise RuntimeError("Source version must have exactly one staged release")
        release = matching[0]
    else:
        release = json.loads(request("https://api.github.com/repos/gravere/BYBUS/releases/" + endpoint, 512 * 1024))
    assets = {asset["name"]: asset for asset in release["assets"]}
    apk_asset = assets["BYBUS-Android.apk"]
    sums = request(assets["SHA256SUMS.txt"]["url"], 32 * 1024, asset=True).decode("utf-8")
    matches = re.findall(r"^([a-fA-F0-9]{64})\s+\*?BYBUS-Android\.apk\s*$", sums, re.M)
    if len(matches) != 1 or (release["draft"] and not candidate) or release["prerelease"]:
        raise RuntimeError("Invalid formal release metadata")
    apk = Path(os.environ["RUNNER_TEMP"]) / "BYBUS-Android.apk"
    data = request(apk_asset["url"], 200 * 1024 * 1024, asset=True)
    digest = hashlib.sha256(data).hexdigest()
    if len(data) != apk_asset["size"] or digest != matches[0].lower():
        raise RuntimeError("Release APK size/hash mismatch")
    REPORT.update(release=release["tag_name"], sha256=digest, draft_candidate=release["draft"])
    apk.write_bytes(data)
    del data
    # Use the release toolchain version rather than whichever preview tool sorts last.
    verifier = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0/apksigner"
    if not verifier.is_file():
        raise RuntimeError("Android Build Tools 35.0.0 signature verifier is unavailable")
    signatures = command(str(verifier), "verify", "--print-certs", str(apk), timeout=60)
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", signatures)
    REPORT["observed_certificate_sha256"] = certs
    if not certs:
        raise RuntimeError("APK verification succeeded but no signer digest could be parsed")
    if {c.lower() for c in certs} != {CERT}:
        raise RuntimeError("Released APK certificate differs from the BYBUS certificate")
    REPORT.update(release=release["tag_name"], sha256=digest, certificate_sha256=CERT)
    ready()
    REPORT["abi"] = adb("shell", "getprop", "ro.product.cpu.abilist").strip()
    REPORT["page_size"] = adb("shell", "getconf", "PAGE_SIZE").strip()
    REPORT["native_bridge"] = adb("shell", "getprop", "ro.dalvik.vm.native.bridge").strip()
    if "arm64-v8a" not in REPORT["abi"]:
        if REPORT["profile"] == "experimental16k":
            REPORT["result"] = "environment unsupported: no ARM64 ABI; release runtime not tested"
            return
        raise RuntimeError("This Android image cannot install the ARM64 release")
    installed = adb("install", "--no-streaming", str(apk), timeout=150)
    if "Success" not in installed:
        raise RuntimeError("APK installation did not succeed: " + installed[-1000:])
    REPORT["checks"].append("published APK signature/hash and installation")
    if os.environ.get("RUNTIME_MODE") in ("google-readiness", "google-window", "google-settings"):
        import importlib.util
        spec = importlib.util.spec_from_file_location("google_readiness", "scripts/inspect-google-readiness.py")
        inspection = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(inspection)
        inspection.inspect(globals(), release, verifier, windows_only=os.environ.get("RUNTIME_MODE") != "google-readiness",
                           settings_only=os.environ.get("RUNTIME_MODE") == "google-settings")
        return
    if os.environ.get("RUNTIME_MODE") == "package-jni":
        adb("root")
        adb("wait-for-device", timeout=30)
        if adb("shell", "id", "-u").strip() != "0":
            raise RuntimeError("Disposable emulator cannot retrieve the private fixture report")
        adb("shell", "am", "force-stop", PACKAGE)
        digest = install_component_probe(release, verifier)
        REPORT["jni_probe_sha256"] = digest
        inspect_framework_permissions(package_jni=True)
        REPORT["checks"].append("Public Java/JNI package-list and concrete parcel checks across server Binder and attached native thread")
        REPORT["result"] = "JNI package API inspection completed; no game or protection library started"
        return
    if os.environ.get("RUNTIME_MODE") in ("game-startup", "live-translation"):
        import importlib.util
        spec = importlib.util.spec_from_file_location("game_startup", "scripts/inspect-game-startup.py")
        inspection = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(inspection)
        inspection.inspect(adb, command, hierarchy, locate, tap, REPORT, OUT, verifier)
        return
    if os.environ.get("RUNTIME_MODE") in ("install-open", "startup-records", "release-update", "external-install"):
        import importlib.util
        spec = importlib.util.spec_from_file_location("install_open", "scripts/inspect-install-open.py")
        inspection = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(inspection)
        if os.environ.get("RUNTIME_MODE") == "external-install":
            inspection.inspect_external_install(apk, adb, command, hierarchy, locate, tap, REPORT, OUT)
        elif os.environ.get("RUNTIME_MODE") == "release-update":
            inspection.inspect_release_update(adb, command, hierarchy, locate, tap, REPORT, OUT,
                                              release, request, verifier)
        elif os.environ.get("RUNTIME_MODE") == "startup-records":
            inspection.inspect_startup_records(adb, hierarchy, locate, REPORT, OUT, release, verifier, install_component_probe)
        else:
            inspection.inspect(apk, adb, command, hierarchy, locate, tap, REPORT, OUT,
                               release, request, verifier, install_component_probe)
        return
    if os.environ.get("RUNTIME_MODE") in ("service-callback", "permissions", "package-queries"):
        adb("logcat", "-c")
        adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.MainActivity", timeout=90)
        time.sleep(3)
        locate(hierarchy("release-home"), "设置与工具")
        REPORT["checks"].append("signed release main activity resumed")
        if os.environ.get("RUNTIME_MODE") in ("permissions", "package-queries"):
            inspect_permissions(release, verifier, os.environ.get("RUNTIME_MODE") == "package-queries")
        else:
            inspect_service_callback(release, verifier)
        return
    if os.environ.get("RUNTIME_MODE") == "button-feedback":
        adb("shell", "cmd", "statusbar", "send-disable-flag", "system-icons", "clock", "notification-icons")
        adb("logcat", "-c")
        inspect_launch_control(release, verifier, feedback_only=True)
        REPORT["result"] = "button feedback inspection completed; screenshots require visual review"
        return
    if os.environ.get("RUNTIME_MODE") == "disclosure-ui":
        adb("shell", "cmd", "statusbar", "send-disable-flag", "system-icons", "clock", "notification-icons")
        adb("logcat", "-c")
        inspect_migration_screen(release, verifier, focused=True)
        REPORT["result"] = "disclosure inspection completed; screenshots require visual review"
        return
    if os.environ.get("RUNTIME_MODE") == "repairs":
        adb("logcat", "-c")
        adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.MainActivity", timeout=90)
        time.sleep(3)
        inspect_account_registry(release, verifier)
        inspect_migration(release, verifier)
        REPORT["result"] = "container repair regression completed; no account or purchase attempted"
        return
    adb("shell", "settings", "delete", "global", "policy_control")
    # Hide emulator system chrome before taking raw captures; never paint over app pixels.
    adb("shell", "cmd", "statusbar", "send-disable-flag", "system-icons", "clock", "notification-icons")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{NAMESPACE}.MainActivity", timeout=90)
    time.sleep(15)
    if os.environ.get("RUNTIME_MODE") == "detail-rest":
        inspect_remaining_details(hierarchy("remaining-home"))
        inspect_detail_controls(release, verifier)
        REPORT["result"] = "detail continuation completed; combine with the same APK's retained first pages"
        return
    home = capture("home")
    if os.environ.get("RUNTIME_MODE") in ("app-details", "app-details-rest"):
        import importlib.util
        spec = importlib.util.spec_from_file_location("app_details", Path(__file__).with_name("cloud-app-details.py"))
        inspection = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(inspection)
        inspection.inspect(globals())
        return
    if os.environ.get("RUNTIME_MODE") == "row-feedback":
        inspect_row_feedback(release, verifier, home)
        return
    if os.environ.get("RUNTIME_MODE") in ("ui", "launch", "button-ui", "interaction-ui"):
        inspect_home_alignment(home, 720, 1560, density=2)
    REPORT["checks"].append("main activity resumed")
    tap("都市零协会", home)
    project = capture("project")
    locate(project, "翻译项目")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    home = hierarchy("home-return")
    tap("设置与工具", home)
    settings = capture("settings")
    if os.environ.get("RUNTIME_MODE") == "ui":
        texts = {n.get("text", "") for n in settings.iter("node")}
        if texts & {"应用与汉化资源", "保留已下载的游戏资源", "管理容器账号与应用内购买", "保留资源，下次启动会重新准备"}:
            raise RuntimeError("Removed tools subtitles are still present")
    tap("游戏更新", settings)
    child = capture("game-update-detail") if os.environ.get("RUNTIME_MODE") == "button-ui" else hierarchy("game-update")
    locate(child, "返回上一页")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    parent = hierarchy("settings-return")
    locate(parent, "设置与工具")
    locate(parent, "版本与资源")
    REPORT["checks"].append("child back returns to parent settings")
    if os.environ.get("RUNTIME_MODE") in ("launch", "resources", "migration-ui", "interaction-ui"):
        tap("数据迁移", parent)
        migration = capture("migration-menu")
        locate(migration, "选择资源文件")
        tap("选择资源文件", migration)
        time.sleep(1)
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)
        locate(hierarchy("migration-picker-cancel"), "数据迁移")
        tap("返回上一页", hierarchy("migration-exit"))
        time.sleep(.5)
        locate(hierarchy("migration-parent"), "设置与工具")
        REPORT["checks"].append("release migration menu and document picker cancellation return safely")
    if os.environ.get("RUNTIME_MODE") == "button-ui":
        inspect_detail_redesign(parent)
        inspect_detail_controls(release, verifier)
        REPORT["result"] = "button inspection completed; screenshots require visual review"
        return
    if os.environ.get("RUNTIME_MODE") == "interaction-ui":
        inspect_tablet(focused=True)
        digest = install_component_probe(release, verifier)
        inspect_launch_control(release, verifier, probe_digest=digest)
        inspect_migration_screen(release, verifier, focused=True, probe_digest=digest)
        REPORT["result"] = "interaction inspection completed; screenshots require visual review"
        return
    if os.environ.get("RUNTIME_MODE") == "migration-ui":
        inspect_migration_screen(release, verifier)
        REPORT["result"] = "migration screen inspection completed; screenshots require visual review"
        return
    if os.environ.get("RUNTIME_MODE") == "resources":
        install_component_probe(release, verifier)
        inspect_resource_import()
        REPORT["result"] = "resource import regression completed; screenshots require visual review"
        return
    if os.environ.get("RUNTIME_MODE") == "ui":
        inspect_detail_scroll(parent)
        inspect_update_progress()
        inspect_system_navigation()
    elif os.environ.get("RUNTIME_MODE") == "updates":
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(.5)
        inspect_update_progress()
        inspect_system_navigation()
    elif os.environ.get("RUNTIME_MODE") == "runtime":
        inspect_google(parent)
    if os.environ.get("RUNTIME_MODE") != "updates":
        inspect_tablet()
    if os.environ.get("RUNTIME_MODE") == "launch":
        inspect_launch_control(release, verifier)
        inspect_resource_import()
    elif os.environ.get("RUNTIME_MODE") == "updates":
        inspect_update_controls(release, verifier)
    REPORT["result"] = "runtime baseline completed; screenshots require visual review"


try:
    main()
except Exception as error:
    REPORT["result"] = "blocked"
    REPORT["error"] = str(error)
    raise
finally:
    try:
        if os.environ.get("RUNTIME_MODE") in ("service-callback", "permissions", "package-queries", "install-open", "startup-records", "release-update", "external-install"):
            diagnostic = adb("logcat", "-d", "-t", "700", "PermissionProbe:E", "ServiceCallbackProbe:E", "ActivityManager:I",
                             "ActivityTaskManager:I", "LimbusZhCN:D", "LimbusZhCNApp:I", "LimbusVA:W",
                             "InstallSuccess:I", "InstallLaunch:I", "BYBUSInstallProbe:I", "AndroidRuntime:E", "*:S", timeout=15, check=False)
            (OUT / "callback-log.txt").write_text(diagnostic, encoding="utf-8")
        crashes = adb("logcat", "-d", "-b", "crash", "-t", "150", timeout=15, check=False)
        (OUT / "crashes.txt").write_text(crashes, encoding="utf-8")
    except Exception:
        pass
    (OUT / "report.json").write_text(json.dumps(REPORT, ensure_ascii=False, indent=2), encoding="utf-8")
