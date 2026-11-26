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
start = source.index("        auto has_inline_hook_space =")
end = source.index("        std::unordered_map<void *, std::pair<int, size_t>> table_addresses;", start)
(output / "runtime-core-text-binding.inc").write_text(source[start:end], encoding="utf-8")
(output / "runtime-translation-under-test.inc").write_text("\n\n".join(pieces), encoding="utf-8")
start = source.index("struct StartTutorialBinding {")
end = source.index("\nvoid prepare_notice_body(void *target) {", start)
(output / "runtime-start-tutorial-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("            auto hook_text_presenter =")
end = source.index("            auto hook_ui_loader =", start)
presenter_binding = source[start:end]
start = source.index("            g_start_tutorial.target = choice_field(")
end = source.index("            const void *implicit_method =", start)
(output / "runtime-start-tutorial-binding.inc").write_text(presenter_binding + source[start:end], encoding="utf-8")
start = source.index("        if (!g_start_tutorial.owner_class)")
end = source.index("        if (ui_text_data_class == nullptr)", start)
(output / "runtime-start-tutorial-classes.inc").write_text(source[start:end], encoding="utf-8")
catalogue = []
for signature in ("bool utf8_to_utf16(", "bool is_usable_display_text(", "bool is_display_string_field(",
                  "void rebuild_package_line_break_index(",
                  "bool read_exact(", "bool read_u32(", "bool read_string(", "bool load_live_font_catalogue("):
    start = source.index(signature)
    end = source.index("\n}", start) + 2
    catalogue.append(source[start:end])
(output / "runtime-live-catalogue-under-test.inc").write_text("\n\n".join(catalogue), encoding="utf-8")
start = source.index("bool read_flow_text(")
end = source.index("\n// Observation only:", start)
(output / "runtime-flow-under-test.inc").write_text(source[start:end], encoding="utf-8")
notice = []
start = source.index("struct NoticeTextState {")
end = source.index("void prepare_notice_body(void *target);", start)
(output / "runtime-notice-state.inc").write_text(source[start:end], encoding="utf-8")
for signature in ("bool is_notice_text(", "void prepare_notice_body(void *target) {", "void reflow_notice_body(",
                  "void *translate_notice_component(void *target, void *source) {", "template<size_t Slot>\nvoid replacement_notice_extra(", "void replacement_notice_set_data("):
    start = source.index(signature)
    end = source.index("\n}", start) + 2
    notice.append(source[start:end])
(output / "runtime-notice-under-test.inc").write_text("\n\n".join(notice), encoding="utf-8")
start = source.index("bool retain_tmp_material(")
end = source.index("\nvoid replacement_tmp_set_shared_material(", start)
(output / "runtime-material-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("bool capture_tmp_component_state(")
end = source.index("\nbool find_tmp_component_state(", start)
(output / "runtime-style-capture-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("thread_local void *g_inline_material_target =")
end = source.index("\nvoid restore_fixed_label_size(", start)
(output / "runtime-inline-material-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("void *prepare_tmp_text(", source.index("void *apply_tmp_component_layout_markup("))
end = source.index("\nvoid replacement_tmp_set_font(", start)
(output / "runtime-prepare-text-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("template<size_t Slot>\nvoid *replacement_ruby_format(")
end = source.index("\nvoid apply_translated_ui_style(", start)
(output / "runtime-ruby-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("template<class Format>\nvoid *carry_rich_text_provenance(")
end = source.index("\ntemplate<size_t Slot>\nvoid *replacement_ruby_format(", start)
(output / "runtime-rich-text-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("limbus_managed::Ref load_bundled_ui_font(")
end = source.index("\nbool is_unity_main_thread() {", start)
(output / "runtime-legacy-font-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("bool is_unity_main_thread() {")
end = source.index("bool invoke_float_getter(", start)
(output / "runtime-font-init-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("bool has_interactive_text_owner(")
end = source.index("\n}", start) + 2
(output / "runtime-interactive-owner-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("bool read_tmp_wrapping(")
end = source.index("\nstruct ComponentRect {", start)
(output / "runtime-wrapping-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("void restore_fixed_label_size(")
end = source.index("\nvoid restore_tmp_component_state(", start)
(output / "runtime-label-restore-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("float read_tmp_layout_size(")
end = source.index("\nvoid *apply_tmp_component_layout_markup(", start)
(output / "runtime-layout-under-test.inc").write_text(source[start:end], encoding="utf-8")
title_layout = []
for signature in ("float personality_title_character_width(", "std::vector<std::string> split_utf8_codepoints(",
                  "uint32_t first_utf8_codepoint(", "std::vector<std::string> wrap_personality_title_line(",
                  "std::string reflow_personality_title(", "void *apply_tmp_component_layout_markup("):
    start = source.index(signature)
    end = source.index("\n}", start) + 2
    title_layout.append(source[start:end])
(output / "runtime-title-layout-under-test.inc").write_text("\n\n".join(title_layout), encoding="utf-8")
start = source.index("void apply_translated_ui_style(")
end = source.index("\nvoid *prepare_ui_text(", start)
(output / "runtime-ui-layout-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("void replacement_tmp_set_font(")
end = source.index("\nvoid finalize_tmp_text(", start)
(output / "runtime-font-refresh-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("limbus_managed::Registry<bool> g_language_font_targets(")
end = source.index("\nvoid *prepare_tmp_text(", start)
(output / "runtime-language-font-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("struct LocalizedLayoutBinding {")
end = source.index("\nlimbus_managed::Registry<bool> g_language_font_targets(", start)
(output / "runtime-localized-layout-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("struct LocalizedTableHook {")
end = source.index("\nvoid *finish_formatted_translation(", start)
(output / "runtime-localized-table-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("    auto diagnostic_method =")
end = source.index("    const void *material_keyword_method =", start)
(output / "runtime-method-binding-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("        if (!custom_localize_class)")
end = source.index("\n", start)
(output / "runtime-custom-class-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("thread_local unsigned g_localization_load_depth =")
end = source.index("\nthread_local bool g_startup_localization_recovering", start)
(output / "runtime-localization-scope.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("template<size_t Slot>\nvoid replacement_localization_load(")
end = source.index("\ntypedef void *(*acquisition1_fn)", start)
(output / "runtime-loading-boundaries-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("typedef void (*ui_loader_fn)")
end = source.index("\nvoid replacement_ui_loader(", start)
(output / "runtime-story-assets-under-test.inc").write_text(source[start:end], encoding="utf-8")
start = source.index("limbus_terms::Trie g_term_trie;")
end = source.index("\nbool g_configured", start)
pieces = [source[start:end]]
for signature in ("bool contains_japanese_kana(", "void rebuild_term_trie(", "bool translate_embedded_terms("):
    start = source.index(signature)
    end = source.index("\n}", start) + 2
    pieces.append(source[start:end])
(output / "runtime-terms-under-test.inc").write_text("\n\n".join(pieces), encoding="utf-8")

vm_source = (root / "third_party/virtualapp-upstream/lib/src/main/jni/Foundation/VMHook.cpp").read_text(encoding="utf-8")
pieces = []
for signature in ("static bool startsWith(", "static bool isLimbusFindClassFallbackTarget(", "static jclass new_limbus_FindClass("):
    start = vm_source.index(signature)
    end = vm_source.index("\n}", start) + 2
    pieces.append(vm_source[start:end])
(output / "runtime-findclass-under-test.inc").write_text("\n\n".join(pieces), encoding="utf-8")
