"""Inspect an unmodified public APK; no Google account, game data, or purchase."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

PACKAGE = "com.example.limbuszhcn"
CERT = "d50bbcfb234692d5d5f90427499158608c5a4c13adb7f99a78d9b5f1af44d55b"
OUT = Path("runtime-output")
OUT.mkdir(exist_ok=True)
REPORT = {"checks": [], "payment_runtime": "not tested", "native_arm64_16k": "not tested"}


def command(*args, timeout=30, binary=False, check=True):
    result = subprocess.run(args, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"{args[0]} exited {result.returncode}: "
                           + result.stderr.decode("utf-8", "replace")[-1200:])
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def adb(*args, **kwargs):
    return command("adb", "-s", "emulator-5554", *args, **kwargs)


def request(url, maximum):
    req = urllib.request.Request(url, headers={"User-Agent": "BYBUS-Runtime-Inspection"})
    with urllib.request.urlopen(req, timeout=30) as response:
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


def main():
    release = json.loads(request("https://api.github.com/repos/gravere/BYBUS/releases/latest", 512 * 1024))
    assets = {asset["name"]: asset for asset in release["assets"]}
    apk_asset = assets["BYBUS.apk"]
    sums = request(assets["SHA256SUMS.txt"]["browser_download_url"], 32 * 1024).decode("utf-8")
    matches = re.findall(r"^([a-fA-F0-9]{64})\s+\*?BYBUS\.apk\s*$", sums, re.M)
    if len(matches) != 1 or release["draft"] or release["prerelease"]:
        raise RuntimeError("Invalid formal release metadata")
    apk = Path(os.environ["RUNNER_TEMP"]) / "BYBUS.apk"
    data = request(apk_asset["browser_download_url"], 200 * 1024 * 1024)
    digest = hashlib.sha256(data).hexdigest()
    if len(data) != apk_asset["size"] or digest != matches[0].lower():
        raise RuntimeError("Release APK size/hash mismatch")
    apk.write_bytes(data)
    del data
    candidates = list((Path(os.environ["ANDROID_HOME"]) / "build-tools").glob("*/apksigner"))
    if not candidates:
        raise RuntimeError("Android signature verifier is unavailable")
    verifier = sorted(candidates, key=lambda p: tuple(map(int, re.findall(r"\d+", p.parent.name))))[-1]
    signatures = command(str(verifier), "verify", "--print-certs", str(apk), timeout=60)
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", signatures)
    if {c.lower() for c in certs} != {CERT}:
        raise RuntimeError("Released APK certificate differs from the BYBUS certificate")
    REPORT.update(release=release["tag_name"], sha256=digest, certificate_sha256=CERT)
    ready()
    REPORT["abi"] = adb("shell", "getprop", "ro.product.cpu.abilist").strip()
    REPORT["page_size"] = adb("shell", "getconf", "PAGE_SIZE").strip()
    REPORT["native_bridge"] = adb("shell", "getprop", "ro.dalvik.vm.native.bridge").strip()
    if "arm64-v8a" not in REPORT["abi"]:
        raise RuntimeError("This Android image cannot install the ARM64 release")
    installed = adb("install", "--no-streaming", str(apk), timeout=150)
    if "Success" not in installed:
        raise RuntimeError("APK installation did not succeed: " + installed[-1000:])
    REPORT["checks"].append("published APK signature/hash and installation")
    adb("shell", "settings", "put", "global", "policy_control", f"immersive.full={PACKAGE}")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity", timeout=90)
    time.sleep(15)
    home = capture("home")
    REPORT["checks"].append("main activity resumed")
    tap("都市零协会", home)
    project = capture("project")
    locate(project, "翻译项目")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    home = hierarchy("home-return")
    tap("设置与工具", home)
    settings = capture("settings")
    tap("游戏更新", settings)
    child = hierarchy("game-update")
    locate(child, "返回上一页")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    parent = hierarchy("settings-return")
    locate(parent, "设置与工具")
    locate(parent, "版本与资源")
    REPORT["checks"].append("child back returns to parent settings")
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
