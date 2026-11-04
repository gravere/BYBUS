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

PACKAGE = "com.example.limbuszhcn"
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
    while time.monotonic() < deadline:
        try:
            user = adb("shell", "am", "get-started-user-state", "0", timeout=12).strip()
            services = [adb("shell", "service", "check", s, timeout=8) for s in ("package", "activity", "window")]
            ok = user == "RUNNING_UNLOCKED" and all("found" in s.lower() and "not found" not in s.lower() for s in services)
            streak = streak + 1 if ok else 0
            if streak == 2:
                REPORT["user_state"] = user
                return
        except (RuntimeError, subprocess.TimeoutExpired):
            streak = 0
        time.sleep(3)
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


def inspect_tablet():
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity", timeout=30)
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
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    time.sleep(2)
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


def inspect_system_navigation():
    """Exercise real system navigation modes; never replace system chrome in screenshots."""
    overlays = adb("shell", "cmd", "overlay", "list")
    original = re.search(r"\[x\]\s+(com\.android\.internal\.systemui\.navbar\.\S+)", overlays)
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
             ("nav-three-button-landscape", "threebutton", "no", "1280x720")]
    try:
        for name, mode, night, size in cases:
            adb("shell", "cmd", "overlay", "enable-exclusive", "--category", prefix + mode)
            adb("shell", "cmd", "uimode", "night", night)
            adb("shell", "wm", "size", size)
            time.sleep(3)
            tree = capture(name)
            windows = adb("shell", "dumpsys", "window", "windows")
            (OUT / f"{name}-window.txt").write_text(windows, encoding="utf-8")
            nav_frames = []
            for line in windows.splitlines():
                source = re.search(r"(?:mType|type)=navigationBars\b([^}\n]*)", line)
                if not source or not re.search(r"(?:mVisible|visible)=true", source[1]):
                    continue
                match = re.search(r"(?:mFrame|frame)=\[\s*(\d+),\s*(\d+)\]\[\s*(\d+),\s*(\d+)\]", source[1])
                if not match:
                    match = re.search(r"(?:mFrame|frame)=Rect\(\s*(\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)", source[1])
                if match:
                    frame = tuple(map(int, match.groups()))
                    if frame[2] > frame[0] and frame[3] > frame[1]:
                        nav_frames.append(frame)
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
                size=size, navigation_bounds=nav, controls=controls))
    finally:
        adb("shell", "cmd", "uimode", "night", "no", check=False)
        adb("shell", "wm", "size", "720x1560", check=False)
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
    apk_asset = assets["BYBUS.apk"]
    sums = request(assets["SHA256SUMS.txt"]["url"], 32 * 1024, asset=True).decode("utf-8")
    matches = re.findall(r"^([a-fA-F0-9]{64})\s+\*?BYBUS\.apk\s*$", sums, re.M)
    if len(matches) != 1 or (release["draft"] and not candidate) or release["prerelease"]:
        raise RuntimeError("Invalid formal release metadata")
    apk = Path(os.environ["RUNNER_TEMP"]) / "BYBUS.apk"
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
    adb("shell", "settings", "delete", "global", "policy_control")
    # Hide emulator system chrome before taking raw captures; never paint over app pixels.
    adb("shell", "cmd", "statusbar", "send-disable-flag", "system-icons", "clock", "notification-icons")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity", timeout=90)
    time.sleep(15)
    home = capture("home")
    if os.environ.get("RUNTIME_MODE") == "ui":
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
    child = hierarchy("game-update")
    locate(child, "返回上一页")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    parent = hierarchy("settings-return")
    locate(parent, "设置与工具")
    locate(parent, "版本与资源")
    REPORT["checks"].append("child back returns to parent settings")
    if os.environ.get("RUNTIME_MODE") == "ui":
        inspect_detail_scroll(parent)
        inspect_update_progress()
        inspect_system_navigation()
    else:
        inspect_google(parent)
    inspect_tablet()
    REPORT["result"] = "runtime baseline completed; screenshots require visual review"


try:
    main()
except Exception as error:
    REPORT["result"] = "blocked"
    REPORT["error"] = str(error)
    raise
finally:
    try:
        crashes = adb("logcat", "-d", "-b", "crash", "-t", "150", timeout=15, check=False)
        (OUT / "crashes.txt").write_text(crashes, encoding="utf-8")
    except Exception:
        pass
    (OUT / "report.json").write_text(json.dumps(REPORT, ensure_ascii=False, indent=2), encoding="utf-8")
