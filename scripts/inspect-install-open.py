"""Cloud-only system installer inspection using the untouched signed BYBUS APK."""
from pathlib import Path
import os
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
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-c",
        "android.intent.category.LAUNCHER", "-p", package, timeout=60)
    time.sleep(2)
    locate(hierarchy("before-update"), "设置与工具")
    adb("shell", "am", "start", "-W", "-n", fixture + "/.InstallProbe", timeout=60)
    adb("push", str(apk), "/sdcard/Android/data/" + fixture + "/files/update.apk", timeout=120)
    tap("Install BYBUS", hierarchy("installer-caller"))
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
    time.sleep(2)
    tree = hierarchy("after-installer-open")
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
