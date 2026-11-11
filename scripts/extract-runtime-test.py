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
start = source.index("void *prepare_tmp_text(")
end = source.index("\nvoid finalize_tmp_text(", start)
(output / "runtime-prepare-text-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("template<size_t Slot>\nvoid *replacement_ruby_format(")
end = source.index("\nvoid apply_translated_ui_style(", start)
(output / "runtime-ruby-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("limbus_managed::Ref load_bundled_ui_font(")
end = source.index("\nbool is_unity_main_thread() {", start)
(output / "runtime-legacy-font-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("bool is_unity_main_thread() {")
end = source.index("bool invoke_float_getter(", start)
(output / "runtime-font-init-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("void apply_translated_tmp_style(")
end = source.index("\nvoid *apply_tmp_component_layout_markup(", start)
(output / "runtime-layout-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("void apply_translated_ui_style(")
end = source.index("\nvoid *prepare_ui_text(", start)
(output / "runtime-ui-layout-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("struct TermTrieNode {")
end = source.index("\nbool g_configured", start)
pieces = [source[start:end]]
for signature in ("bool contains_japanese_kana(", "void rebuild_term_trie(", "bool translate_embedded_terms("):
    start = source.index(signature)
    end = source.index("\n}", start) + 2
    pieces.append(source[start:end])
(output / "runtime-terms-under-test.inc").write_text("\n\n".join(pieces), encoding="utf-8")
