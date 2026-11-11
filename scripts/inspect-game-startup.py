"""One official/container startup comparison in an empty disposable emulator.

No game account, purchase, battle, protected-memory modification, or host-device ADB.
Game APKs stay in RUNNER_TEMP and are never included in uploaded artifacts.
"""
import hashlib
import os
from pathlib import Path
import re
import time
import urllib.request
import zipfile

GAME = "com.ProjectMoon.LimbusCompany"
HOST = "io.github.gravere.bybus"
GAME_SIGNER = "9ea88fdc27195dfef99050f88a889cad86cdf1a80faca2e22d6464d47f973145"
GAME_URL = "https://d.apkpure.net/b/XAPK/com.ProjectMoon.LimbusCompany?versionCode=488"
MAX_ARCHIVE = 1600 * 1024 * 1024


def download_game(directory):
    archive = directory / "game.xapk"
    request = urllib.request.Request(GAME_URL, headers={"User-Agent": "Mozilla/5.0"})
    digest = hashlib.sha256()
    count = 0
    with urllib.request.urlopen(request, timeout=60) as response, archive.open("wb") as output:
        if response.status != 200:
            raise RuntimeError("Game source did not return a complete archive")
        while chunk := response.read(1024 * 1024):
            count += len(chunk)
            if count > MAX_ARCHIVE:
                raise RuntimeError("Game archive exceeds size limit")
            digest.update(chunk)
            output.write(chunk)
    names = (GAME + ".apk", "config.arm64_v8a.apk", "UnityDataAssetPack.apk")
    apks = []
    with zipfile.ZipFile(archive) as source:
        for name in names:
            entry = source.getinfo(name)
            if not 0 < entry.file_size <= MAX_ARCHIVE:
                raise RuntimeError("Invalid game split size")
            target = directory / name
            # Fixed names only; read through EOF to verify ZIP CRC without extractall.
            with source.open(entry) as src, target.open("wb") as dst:
                while chunk := src.read(1024 * 1024):
                    dst.write(chunk)
            apks.append(target)
    archive.unlink()
    return apks, {"version_code": 488, "source_archive_bytes": count,
                  "source_archive_sha256": digest.hexdigest()}


def sanitize(text):
    text = re.sub(r"(?i)(authorization|token|password|cookie|api[_-]?key)\s*[:=]\s*\S+",
                  r"\1=[redacted]", text)
    text = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", "[redacted-email]", text)
    text = re.sub(r"\beyJ[\w-]+\.[\w-]+\.[\w-]+\b", "[redacted-jwt]", text)
    text = re.sub(r"https?://[^\s<>\"]+", "[redacted-url]", text)
    return text


def capture(adb, out, label):
    (out / (label + ".png")).write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    activity = adb("shell", "dumpsys", "activity", "activities", timeout=20)
    resumed = [line.strip() for line in activity.splitlines()
               if "mResumedActivity" in line or "topResumedActivity" in line]
    logs = adb("logcat", "-d", "LimbusSafety:V", "PackageQueries:V",
               "LimbusStartup:V", "LimbusZhCN:V", "LimbusVA:V", "AndroidRuntime:E",
               "ActivityManager:I", "ActivityTaskManager:I", "libc:F", "*:S", timeout=20)
    (out / (label + "-log.txt")).write_text(sanitize(logs[-150000:]), encoding="utf-8")
    crashes = adb("logcat", "-d", "-b", "crash", "-t", "180", check=False)
    (out / (label + "-crashes.txt")).write_text(sanitize(crashes), encoding="utf-8")
    return {"resumed": resumed, "safety_70036_observed": "70036" in logs,
            "native_crash_observed": "Fatal signal" in logs or "signal 11 (SIGSEGV)" in crashes,
            "screenshot_requires_review": True}


def security_evidence(logs):
    codes = set()
    for line in logs.splitlines():
        if "LimbusSafety" in line:
            match = re.search(r"\bcodes=([0-9,]+)", line)
            if match:
                codes.update(code for code in match[1].split(",") if re.fullmatch(r"\d{5}", code))
        codes.update(re.findall(r"AppSealingKillProcess\[(\d{5})\]", line))
    return sorted(codes)


def observe_startup(adb, out, label, timeout, container=False, failure_probe=None):
    deadline = time.monotonic() + timeout
    first_game_frame = None
    alert_seen = False
    preparation_error = None
    next_probe = time.monotonic() + 8
    while time.monotonic() < deadline:
        activities = adb("shell", "dumpsys", "activity", "activities", timeout=15)
        resumed = "\n".join(line for line in activities.splitlines()
                            if "topResumedActivity=" in line or "mResumedActivity:" in line)
        if "AppSealingAlertDialog" in resumed:
            alert_seen = True
            time.sleep(.4)
            capture(adb, out, label + "-safety")
            break
        in_game = (HOST + "/" in resumed and "ShadowActivity" in resumed) if container else GAME + "/" in resumed
        if in_game and first_game_frame is None:
            first_game_frame = time.monotonic()
        if first_game_frame is not None and time.monotonic() - first_game_frame >= 30:
            break
        if (failure_probe and first_game_frame is None and HOST + "/" in resumed and
                time.monotonic() >= next_probe):
            preparation_error = failure_probe()
            next_probe = time.monotonic() + 8
            if preparation_error:
                break
        time.sleep(1)
    result = capture(adb, out, label)
    result.update(game_activity_seen=first_game_frame is not None, security_dialog_seen=alert_seen,
                  security_rejection_seen=False, observed_security_codes=[])
    if preparation_error:
        result["preparation_error"] = preparation_error
    result["game_in_foreground_at_capture"] = any(
        (HOST + "/" in line and "ShadowActivity" in line) if container else GAME + "/" in line
        for line in result["resumed"])
    # Scope logs to fresh game/host UIDs. No account is created in this emulator.
    packages = adb("shell", "cmd", "package", "list", "packages", "-U", timeout=15)
    uids = [uid for package, uid in re.findall(r"package:(\S+) uid:(\d+)", packages)
            if package in (HOST, GAME)]
    if uids:
        logs = adb("logcat", "-d", "--uid=" + ",".join(uids), timeout=20)
        (out / (label + "-app-log.txt")).write_text(sanitize(logs[-150000:]), encoding="utf-8")
        # A short-lived alert can disappear between activity samples. Include
        # the existing process log so an observed rejection cannot be missed.
        codes = security_evidence(logs)
        result["observed_security_codes"] = codes
        result["security_rejection_seen"] = bool(codes)
        result["safety_70036_observed"] |= "70036" in codes
    return result


def inspect(adb, command, hierarchy, locate, tap, report, out, verifier):
    directory = Path(os.environ["RUNNER_TEMP"]) / "bybus-game-inspection"
    directory.mkdir(exist_ok=True)
    apks, source = download_game(directory)
    for apk in apks:
        result = command(str(verifier), "verify", "--print-certs", str(apk), timeout=150)
        certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", result)
        if {c.lower() for c in certs} != {GAME_SIGNER}:
            raise RuntimeError("Official game split signer mismatch")
    badging = command(str(verifier.with_name("aapt2")), "dump", "badging", str(apks[0]), timeout=30)
    if f"name='{GAME}' versionCode='488'" not in badging:
        raise RuntimeError("Downloaded game version differs from the diagnostic")
    source["split_signatures_verified"] = True
    report["game"] = source
    result = adb("install-multiple", "--no-streaming", "-r", *map(str, apks), timeout=300)
    if "Success" not in result:
        raise RuntimeError("Game split installation failed")
    adb("shell", "am", "force-stop", HOST)
    adb("logcat", "-b", "all", "-c")
    live = os.environ.get("RUNTIME_MODE") == "live-translation"
    if not live:
        component = adb("shell", "cmd", "package", "resolve-activity", "--brief",
                        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                        "-p", GAME).strip().splitlines()[-1]
        if not component.startswith(GAME + "/"):
            raise RuntimeError("Official launcher activity unresolved")
        launch = adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
                     "-c", "android.intent.category.LAUNCHER", "-n", component, timeout=90, check=False)
        (out / "official-launch.txt").write_text(sanitize(launch), encoding="utf-8")
        report["official_startup"] = observe_startup(adb, out, "official-startup", 40)
        report["official_startup"]["launch_status_ok"] = re.search(r"Status:\s+ok", launch) is not None
        exits = adb("shell", "dumpsys", "activity", "exit-info", GAME, timeout=20, check=False)
        (out / "official-exits.txt").write_text(sanitize(exits[-16000:]), encoding="utf-8")
    # Stop at the title/login/alert page; do not download user resources or log in.
    adb("shell", "am", "force-stop", GAME)
    adb("logcat", "-b", "all", "-c")
    adb("shell", "am", "start", "-W", "-n", HOST + "/com.example.limbuszhcn.MainActivity", timeout=90)
    deadline = time.monotonic() + 90
    while True:
        tree = hierarchy("container-detected")
        labels = {node.get("text", "") for node in tree.iter("node")}
        if "启动游戏" in labels:
            break
        if "检测游戏" in labels:
            tap("检测游戏", tree)
        if time.monotonic() >= deadline:
            capture(adb, out, "container-detection-timeout")
            raise RuntimeError("Installed game detection did not finish within the inspection window")
        time.sleep(2)
    tap("启动游戏", tree)
    def preparation_failure():
        state = hierarchy("container-preparation")
        if not any(node.get("text") == "查看失败原因" for node in state.iter("node")):
            return None
        tap("查看失败原因", state)
        details = hierarchy("container-preparation-error")
        return sanitize("\n".join(node.get("text", "") for node in details.iter("node")
                                  if node.get("text")))
    # Wait for actual game activity, not a fixed delay while first-run assets
    # are still being prepared. This remains a single bounded launch attempt.
    report["container_startup"] = observe_startup(adb, out, "container-startup", 240, container=True,
                                                 failure_probe=preparation_failure)
    report["result"] = "official/container startup evidence captured; compare screenshots and logs"
    report["checks"].append("one fresh container startup; no login" if live else "one startup each, original signed game and published BYBUS; no login")
    if not report["container_startup"]["game_activity_seen"]:
        raise RuntimeError(report["container_startup"].get("preparation_error") or
                           "First-run preparation did not reach game activity within the inspection window")
    for kind in (("container_startup",) if live else ("official_startup", "container_startup")):
        status = report[kind]
        if (status["security_dialog_seen"] or status["security_rejection_seen"] or status["safety_70036_observed"] or
                status["native_crash_observed"] or not status["game_in_foreground_at_capture"]):
            raise RuntimeError(kind + " did not remain on the game page; inspect captured evidence")

    if live:
        logs = adb("logcat", "-d", "-v", "brief", "LimbusTranslation:I", "*:S", timeout=20)
        (out / "live-translation-log.txt").write_text(sanitize(logs[-100000:]), encoding="utf-8")
        counts = [int(n) for n in re.findall(r"Live resource JSON merged count=(\d+)", logs)]
        report["live_translation"] = {
            "resource_hooks": "live_resource_hooks sync=1 asset=1" in logs,
            "font_ready": re.search(r"CJK font attempt=\d+ ready=1", logs) is not None,
            "merged_resources_at_least": max(counts, default=0),
            "fresh_container": True,
            "downloaded_resource_merge": "same-session JVM corpus; no game account or full download in emulator"
        }
        if not all((report["live_translation"]["resource_hooks"], report["live_translation"]["font_ready"], counts)):
            raise RuntimeError("First-run live translation did not complete resource loading and font preparation")
