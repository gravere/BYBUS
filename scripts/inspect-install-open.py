"""Cloud-only system installer inspection using the untouched signed BYBUS APK."""
from pathlib import Path
import os
import json
import re
import time
import zipfile


def inspect(apk, adb, command, hierarchy, locate, tap, report, out):
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
    # Throwaway fixture identity generated only on this ephemeral runner.
    command("keytool", "-genkeypair", "-keystore", str(key), "-alias", "probe", "-keyalg", "RSA",
            "-storepass", "temporary", "-keypass", "temporary", "-dname", "CN=Disposable probe", "-validity", "1")
    probe = work / "probe.apk"
    command(str(build / "apksigner"), "sign", "--ks", str(key), "--ks-pass", "pass:temporary",
            "--out", str(probe), str(unsigned))
    fixture = "io.github.gravere.bybus.installprobe"
    package = "io.github.gravere.bybus"
    installed = adb("install", "--no-streaming", str(probe), timeout=90)
    if "Success" not in installed:
        raise RuntimeError("Installer fixture installation failed")
    adb("shell", "appops", "set", fixture, "REQUEST_INSTALL_PACKAGES", "allow")
    # Leave the real application task present during replacement, like an external file-manager update.
    adb("logcat", "-c")
    # PackageManager.getLaunchIntentForPackage returns an explicit component.
    # A raw implicit MAIN start incorrectly adds MATCH_DEFAULT_ONLY at resolution.
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-c",
        "android.intent.category.LAUNCHER", "-n", package + "/com.example.limbuszhcn.MainActivity", timeout=60)
    time.sleep(2)
    locate(hierarchy("before-update"), "设置与工具")
    adb("shell", "am", "start", "-W", "-n", fixture + "/.InstallProbe", timeout=60)
    adb("push", str(apk), "/sdcard/Android/data/" + fixture + "/files/update.apk", timeout=120)
    caller = hierarchy("installer-caller")
    label = next(n.get("text") for n in caller.iter("node") if n.get("text", "").lower() == "install bybus")
    tap(label, caller)
    deadline = time.monotonic() + 90
    confirmed = False
    opened = False
    while time.monotonic() < deadline:
        tree = hierarchy("installer-state")
        buttons = {n.get("text", "").lower(): n for n in tree.iter("node")
                   if n.get("clickable") == "true" and n.get("enabled") == "true"}
        if not confirmed:
            action = next((name for name in ("update", "install") if name in buttons), None)
            if action:
                tap(buttons[action].get("text"), tree)
                confirmed = True
                report["installer_confirmation_clicked"] = True
                continue
        elif "open" in buttons:
            (out / "before-installer-open.txt").write_text(adb("shell", "dumpsys", "activity", "activities"), encoding="utf-8")
            tap(buttons["open"].get("text"), tree)
            opened = True
            report["installer_open_clicked"] = True
            break
        time.sleep(1)
    if not opened:
        raise RuntimeError("System installer did not expose an Open action after confirmation")
    # Replacement cold-start can take several seconds on the ARM translation image.
    # Wait for the actual first app frame, not merely the installer's disappearing window.
    deadline = time.monotonic() + 30
    while True:
        tree = hierarchy("after-installer-open")
        try:
            locate(tree, "设置与工具")
            break
        except RuntimeError:
            if time.monotonic() >= deadline:
                break
            time.sleep(1)
    tasks = adb("shell", "dumpsys", "activity", "activities")
    (out / "after-installer-open.txt").write_text(tasks, encoding="utf-8")
    (out / "installer-log.txt").write_text(adb("logcat", "-d", "-t", "1200", "ActivityTaskManager:I",
        "ActivityManager:I", "PackageInstaller:I", "InstallSuccess:I", "InstallLaunch:I", "BYBUSInstallProbe:I",
        "LimbusZhCNApp:I", "LimbusZhCN:I", "AndroidRuntime:E", "*:S"), encoding="utf-8")
    locate(tree, "设置与工具")
    resumed = [line.strip() for line in tasks.splitlines() if "ResumedActivity" in line or "topResumedActivity" in line]
    if not any(package + "/com.example.limbuszhcn.MainActivity" in line for line in resumed):
        raise RuntimeError("Open did not resume the BYBUS main activity")
    report["install_open"] = dict(passed=True, external_caller=True, actual_installer_ui=True,
        same_version_replacement=True, bybus_main_resumed=True, game_launched=False, vivo_installer_tested=False)
    report["checks"].append("System installer: same signed APK replacement, Open button and BYBUS home resumed")
    report["result"] = "installer Open inspection completed; vivo behavior and 70036 not tested"


def inspect_crash_handlers(adb, hierarchy, locate, report, out):
    package = "io.github.gravere.bybus"
    namespace = "com.example.limbuszhcn"
    adb("root")
    adb("wait-for-device", timeout=30)
    if adb("shell", "id", "-u").strip() != "0":
        raise RuntimeError("Cannot retrieve the isolated emulator's private diagnostic evidence")
    evidence = {}
    for name, activity, suffix in [("host", "AppCrashProbeActivity", ""), ("server", "ServerCrashProbeActivity", "_x")]:
        adb("logcat", "-c")
        adb("shell", "am", "start", "-W", "-n", package + "/" + namespace + "." + activity, check=False)
        time.sleep(2)
        result = json.loads(adb("shell", "cat", "/data/user/0/" + package + "/files/crash-probe-" + name + ".json"))
        persistent = adb("shell", "cat", "/data/user/0/" + package + "/no_backup/diagnostics/runtime-" + package + suffix + ".log", check=False)
        system = adb("logcat", "-d", "-b", "crash")
        (out / ("expected-crash-" + name + ".txt")).write_text(system, encoding="utf-8")
        (out / ("persistent-crash-" + name + ".txt")).write_text(persistent, encoding="utf-8")
        if not result["owned_handler"] or not result["expected_crash"]:
            raise RuntimeError(name + " exception handler was replaced")
        if "BYBUS_EXPECTED_DIAGNOSTIC_CRASH" not in persistent or "Uncaught exception thread=main" not in persistent:
            raise RuntimeError(name + " exception did not reach bounded persistent diagnostics")
        if "FATAL EXCEPTION: main" not in system or "BYBUS_EXPECTED_DIAGNOSTIC_CRASH" not in system:
            raise RuntimeError(name + " exception was swallowed instead of reaching Android")
        running = adb("shell", "ps", "-A", "-o", "PID").split()
        if not running or running[0] != "PID" or not all(pid.isdecimal() for pid in running[1:]):
            raise RuntimeError("Could not read the isolated emulator's process inventory")
        if str(result["pid"]) in running[1:]:
            raise RuntimeError(name + " process survived an unhandled exception")
        evidence[name] = dict(owned_handler=True, persisted=True, delegated_to_android=True, process_exited=True)
        adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", package + "/" + namespace + ".MainActivity", timeout=60)
    time.sleep(2)
    locate(hierarchy("after-expected-crashes"), "设置与工具")
    report["crash_handler_checks"] = dict(passed=True, expected_crashes=2, processes=evidence, home_reopened=True)
    report["checks"].append("Two intentional Debug crashes: persisted, Android handler preserved, process exits and home can reopen")
