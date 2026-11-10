"""Build the optional debug-only package API fixture with the project's pinned NDK."""
from pathlib import Path
import os
import platform
import subprocess

root = Path(__file__).resolve().parent.parent
sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
host = "windows-x86_64" if platform.system() == "Windows" else "linux-x86_64"
bin_dir = sdk / "ndk/30.0.14904198/toolchains/llvm/prebuilt" / host / "bin"
compiler = bin_dir / ("clang++.exe" if platform.system() == "Windows" else "clang++")
output = root / "app/build/generated/jniProbe/arm64-v8a/libbybus_query_probe.so"
output.parent.mkdir(parents=True, exist_ok=True)
subprocess.run([str(compiler), "--target=aarch64-linux-android26", "-shared", "-fPIC", "-O2",
                "-Wall", "-Wextra", "-Werror", "-fno-exceptions", "-fno-rtti", "-nostdlib++",
                "-Wl,-z,max-page-size=16384", str(root / "scripts/jni-package-probe.cpp"),
                "-o", str(output)], check=True)
print("Built debug-only JNI package API fixture")
