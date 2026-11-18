"""Reject stale guest runtimes before creating downloadable update assets."""
from pathlib import Path
import re
from zipfile import ZipFile


def verify_guest_runtime(apk: Path) -> None:
    library = "lib/arm64-v8a/libv++_64.so"
    retired_native = (b"install_limbus_code_integrity_compat", b"limbus_report_dispatch_impl",
                      b"new_sigaction", b"redirect_proc_maps", b"maintainLimbusAppSealingPatches")
    with ZipFile(apk) as archive:
        entries = archive.infolist()
        runtimes = [item for item in entries if item.filename == library]
        if len(runtimes) != 1:
            raise ValueError("Expected a single packaged guest runtime")
        native = archive.read(runtimes[0])
        if not native.startswith(b"\x7fELF") or b"configure_limbus_translation_runtime" not in native:
            raise ValueError("Missing native translation entry point")
        if any(name in native for name in retired_native):
            raise ValueError("Retired guest integrity overrides are present in the APK")
        dex = [item for item in entries if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", item.filename)]
        if not dex or any(b"nativeMaintainLimbusAppSealingPatches" in archive.read(item) for item in dex):
            raise ValueError("Invalid or stale guest initialization in the APK")
