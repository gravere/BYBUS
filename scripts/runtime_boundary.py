"""Require the complete qualified guest runtime in downloadable update assets."""
from pathlib import Path
import re
from zipfile import ZipFile


def verify_guest_runtime(apk: Path) -> None:
    library = "lib/arm64-v8a/libv++_64.so"
    required_native = (b"install_limbus_code_integrity_compat", b"limbus_report_dispatch_impl",
                       b"maintainLimbusAppSealingPatches", b"limbus-engine1-arm64-full",
                       b"refuse unexpected instruction")
    with ZipFile(apk) as archive:
        entries = archive.infolist()
        runtimes = [item for item in entries if item.filename == library]
        if len(runtimes) != 1:
            raise ValueError("Expected a single packaged guest runtime")
        native = archive.read(runtimes[0])
        if not native.startswith(b"\x7fELF") or b"configure_limbus_translation_runtime" not in native:
            raise ValueError("Missing native translation entry point")
        if any(name not in native for name in required_native):
            raise ValueError("Incomplete qualified game compatibility runtime")
        dex = [item for item in entries if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", item.filename)]
        if not dex or not any(b"nativeMaintainLimbusAppSealingPatches" in archive.read(item) for item in dex):
            raise ValueError("Missing qualified runtime initialization")
        # Packaging checks supplement structural and instruction regression tests;
        # the presence of these markers alone does not prove runtime compatibility.
        profiles = archive.read("assets/compatibility/native-profiles.properties")
        if b"profiles.count=" not in profiles or b"libcovault.sha256=" not in profiles:
            raise ValueError("Missing independently qualified legacy profiles")
