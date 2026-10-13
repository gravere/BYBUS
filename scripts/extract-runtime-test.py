"""Emit the actual runtime functions for the portable IL2CPP boundary regression test."""
import pathlib
import sys

root = pathlib.Path(__file__).resolve().parents[1]
source = (root / "third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusTranslationRuntime.cpp").read_text(encoding="utf-8")
pieces = []
for signature in ("bool remember_translated_text(", "void *translate_managed_string(", "bool is_translated_managed_string("):
    start = source.index(signature)
    end = source.index("\n}", start) + 2
    pieces.append(source[start:end])
output = pathlib.Path(sys.argv[1])
output.mkdir(parents=True, exist_ok=True)
(output / "runtime-translation-under-test.inc").write_text("\n\n".join(pieces), encoding="utf-8")
start = source.index("bool retain_tmp_material(")
end = source.index("\nvoid replacement_tmp_set_shared_material(", start)
(output / "runtime-material-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("limbus_managed::Ref load_bundled_ui_font(")
end = source.index("\nbool ensure_tmp_font() {", start)
(output / "runtime-legacy-font-under-test.inc").write_text(source[start:end], encoding="utf-8")
