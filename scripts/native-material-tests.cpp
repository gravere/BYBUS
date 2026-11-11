#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFontMaterials.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFontProof.h"
#include <cassert>
#include <cstdio>
#include <mutex>
#include <vector>
#include <algorithm>
#include <atomic>
#include <codecvt>
#include <locale>
#include <cstring>

struct TmpComponentState {
    void *font = nullptr;
    void *shared_material = nullptr;
    limbus_managed::Ref material_root;
    limbus_font_materials::Lease converted_material;
    void *padded_material = nullptr;
    uint32_t padded_crc = 0;
    bool has_padded_crc = false;
};
struct Component { int id; void *font; bool alive = true; void *material = nullptr; int padding_updates = 0; int redraws = 0; };
struct Preset {
    int style = 0;
    float outline_width = 0, underlay_x = 0, underlay_y = 0, underlay_alpha = 0, face_dilate = 0;
    unsigned keywords = 0;
    bool chinese_atlas = false;
};
struct Converted : Preset { int references = 0; int refreshes = 0; };
void *keep(void *object, bool) { return object; }
void *target(void *handle) { return handle; }
void release(void *) {}
limbus_managed::GcApi g_gc{keep, keep, target, release};
limbus_managed::Registry<TmpComponentState> g_original_tmp_states(g_gc, 256);
std::mutex g_font_state_lock;
#define pthread_mutex_lock(p) (p)->lock()
#define pthread_mutex_unlock(p) (p)->unlock()
int chinese_font, chinese_material, japanese_font, conversion_method, retain_method, release_method;
int texture_method, padding_method, chinese_texture, japanese_texture, crc_method;
int writer_get_text_method, dirty_method;
struct MaterialWriter { Component *text; Preset *cached; float outline, alpha, dilate; };
const void *g_material_writer_get_text_info = &writer_get_text_method;
void write_material(void *object, const void *) {
    auto &writer = *static_cast<MaterialWriter *>(object);
    writer.cached->outline_width = writer.outline;
    writer.cached->underlay_alpha = writer.alpha;
    writer.cached->face_dilate = writer.dilate;
}
auto g_original_material_write = write_material;
void *g_tmp_font_asset = &chinese_font;
const void *g_tmp_get_font_info = &chinese_font;
const void *g_tmp_set_font_info = &japanese_font;
const void *g_tmp_get_shared_material_info = &conversion_method;
const void *g_tmp_set_shared_material_info = &release_method;
const void *g_tmp_font_get_material_info = &chinese_material;
const void *g_get_fallback_material_info = &conversion_method;
const void *g_retain_fallback_material_info = &retain_method;
const void *g_release_fallback_material_info = &release_method;
const void *g_material_get_texture_info = &texture_method;
const void *g_material_compute_crc_info = &crc_method;
auto g_object_unbox = target;
void *new_managed_string(const std::string &);
void invoke_object_setter(const void *, void *, void *);
void *object_class(void *) { return &padding_method; }
const void *class_method(void *, const char *name, int argc) {
    if (std::string(name) == "SetVerticesDirty" && argc == 0) return &dirty_method;
    assert(std::string(name) == "UpdateMeshPadding" && argc == 0);
    return &padding_method;
}
auto g_object_get_class = object_class;
auto g_class_get_method = class_method;
limbus_font_materials::Pool g_converted_font_materials(g_gc);
std::unordered_map<void *, std::unique_ptr<Converted>> converted_cache;
bool fail_conversion = false, fail_retain = false;
int retained = 0, released = 0;
int32_t component_id(void *object) { return static_cast<Component *>(object)->id; }
void *get_font(void *object, const void *) { return static_cast<Component *>(object)->font; }
auto g_tmp_get_font = get_font;
bool find_tmp_component_state(void *instance, TmpComponentState *out) {
    auto saved = g_original_tmp_states.find(instance, component_id(instance));
    if (!saved) return false;
    *out = *saved;
    return true;
}
void *invoke_object_getter(const void *method, void *object) {
    if (method == g_material_writer_get_text_info) return static_cast<MaterialWriter *>(object)->text;
    if (method == g_tmp_get_shared_material_info) return static_cast<Component *>(object)->material;
    assert(object == g_tmp_font_asset);
    return &chinese_material;
}
void *invoke(const void *method, void *object, void **args, void **exception) {
    if (method == &dirty_method) { ++static_cast<Component *>(object)->redraws; return nullptr; }
    if (method == g_material_compute_crc_info) {
        static uint32_t hash;
        hash = 0;
        if (object == &chinese_material) return &hash;
        const auto &preset = *static_cast<Preset *>(object);
        hash = preset.style * 31u + preset.keywords;
        for (float value : {preset.outline_width, preset.underlay_x, preset.underlay_y,
                           preset.underlay_alpha, preset.face_dilate}) {
            uint32_t bits; std::memcpy(&bits, &value, sizeof(bits)); hash = hash * 31 + bits;
        }
        return &hash;
    }
    if (method == g_material_get_texture_info) {
        return object == &chinese_material || static_cast<Preset *>(object)->chinese_atlas
                ? &chinese_texture : &japanese_texture;
    }
    if (method == &padding_method) {
        ++static_cast<Component *>(object)->padding_updates;
        return nullptr;
    }
    if (method == g_get_fallback_material_info) {
        assert(args[1] == &chinese_material);
        if (fail_conversion) { *exception = &conversion_method; return nullptr; }
        auto &converted = converted_cache[args[0]];
        if (!converted) converted = std::make_unique<Converted>();
        static_cast<Preset &>(*converted) = *static_cast<Preset *>(args[0]);
        converted->chinese_atlas = true;
        ++converted->refreshes;
        return converted.get();
    }
    auto converted = static_cast<Converted *>(args[0]);
    if (method == g_retain_fallback_material_info) {
        if (fail_retain) { *exception = &retain_method; return nullptr; }
        ++converted->references; ++retained;
    } else {
        assert(method == g_release_fallback_material_info && converted->references > 0);
        --converted->references; ++released;
    }
    return nullptr;
}
auto g_runtime_invoke = invoke;
#include "runtime-material-under-test.inc"

// Exercise the production text-entry path too: calling only the material
// converter missed stale materials when the Chinese font was already assigned.
std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> codec;
int32_t text_length(void *s) { return static_cast<std::u16string *>(s)->size(); }
const uint16_t *text_chars(void *s) { return reinterpret_cast<const uint16_t *>(static_cast<std::u16string *>(s)->data()); }
auto g_string_length = text_length;
auto g_string_chars = text_chars;
void *g_string_new_utf16 = &chinese_font;
bool utf16_to_utf8(const uint16_t *s, int32_t length, std::string *out) {
    *out = codec.to_bytes(reinterpret_cast<const char16_t *>(s), reinterpret_cast<const char16_t *>(s) + length);
    return true;
}
bool ensure_tmp_font() { return true; }
bool is_choice_text(void *, void *) { return false; }
void *translate_managed_string(void *text) { return text; }
void *new_managed_string(const std::string &value) {
    assert(value == "_MainTex");
    return &texture_method;
}
bool remember_translated_text(void *, const std::string &) { assert(false); return false; }
void *apply_tmp_component_layout_markup(void *, void *text) { return text; }
limbus_font_proof::Catalogue fonts;
bool is_translated_managed_string(void *text) { return fonts.contains(codec.to_bytes(*static_cast<std::u16string *>(text))); }
bool is_translated_tmp_fragment(void *, void *, std::vector<void *> *) { return false; }
namespace limbus_ui_labels { std::string translate_status(const std::string &) { return {}; } }
bool capture_tmp_component_state(void *object, void *font, TmpComponentState *out) {
    TmpComponentState state;
    state.font = font;
    state.shared_material = static_cast<Component *>(object)->material;
    state.material_root = limbus_managed::retain(g_gc, state.shared_material);
    *out = state;
    return g_original_tmp_states.put(object, component_id(object), state);
}
void set_font(void *object, void *font, const void *) {
    static_cast<Component *>(object)->font = font;
    static_cast<Component *>(object)->material = &chinese_material;
}
auto g_tmp_set_font = set_font;
void invoke_object_setter(const void *, void *object, void *material) {
    static_cast<Component *>(object)->material = translated_tmp_material(object, material);
}
int restore_calls = 0;
void restore_tmp_component_state(void *) { ++restore_calls; }
std::atomic<uint64_t> g_tmp_primary_font_switches{0};
uint64_t g_translation_hits = 0;
#define LT_LOGI(...) ((void)0)
#define __atomic_add_fetch(p, n, order) (*(p) += (n))
#define __ATOMIC_RELAXED 0
#include "runtime-prepare-text-under-test.inc"

void forget(Component &component) {
    TmpComponentState saved;
    assert(g_original_tmp_states.take(&component, component.id, &saved));
}

int main() {
    for (const auto label : {"经验", "纺锤采光 ({0})", "暴食", "是否跳过战斗？"}) fonts.add(label);
    g_original_tmp_states.set_alive([](void *object) { return static_cast<Component *>(object)->alive; });
    Component first{1, &japanese_font}, second{2, &chinese_font};
    Preset preset{42};
    assert(translated_tmp_material(&first, &preset) == &preset);
    first.font = &chinese_font;
    assert(translated_tmp_material(&first, &preset) == &chinese_material);
    assert(g_original_tmp_states.put(&first, first.id, {}));
    auto material = static_cast<Converted *>(translated_tmp_material(&first, &preset));
    assert(material->style == 42 && material->references == 1);
    preset.style = 73;
    assert(translated_tmp_material(&first, &preset) == material);
    assert(material->style == 73 && material->references == 1 && material->refreshes == 2);
    // Sharing a converted material must keep it alive until both users leave.
    assert(g_original_tmp_states.put(&second, second.id, {}));
    assert(translated_tmp_material(&second, material) == material);
    forget(first);
    assert(material->references == 1);
    forget(second);
    assert(material->references == 0);
    g_converted_font_materials.collect();
    assert(g_converted_font_materials.size() == 0);

    // One boundary regression: 130 simultaneous styles, then scene destruction.
    std::vector<Component> components;
    std::vector<Preset> presets;
    components.reserve(130); presets.reserve(130);
    for (int n = 0; n < 130; ++n) {
        components.push_back({n + 10, &chinese_font}); presets.push_back({n + 100});
        auto &component = components.back();
        assert(g_original_tmp_states.put(&component, component.id, {}));
        auto result = translated_tmp_material(&component, &presets.back());
        assert(result != &chinese_material);
        assert(static_cast<Converted *>(result)->style == n + 100);
    }
    assert(g_converted_font_materials.size() == 130);
    for (auto &component : components) component.alive = false;
    g_original_tmp_states.collect(256);
    g_converted_font_materials.collect();
    assert(g_original_tmp_states.size() == 0 && g_converted_font_materials.size() == 0);
    assert(retained == released);

    assert(g_original_tmp_states.put(&first, first.id, {}));
    material = static_cast<Converted *>(translated_tmp_material(&first, &preset));
    assert(translated_tmp_material(&first, nullptr) == &chinese_material);
    assert(material->references == 0);
    fail_conversion = true;
    assert(translated_tmp_material(&first, &preset) == &chinese_material);
    fail_conversion = false; fail_retain = true;
    assert(translated_tmp_material(&first, &preset) == &chinese_material);
    fail_retain = false;
    forget(first);
    assert(retained == released);
    Component rendered{300, &japanese_font};
    Preset normal{10}, selected{20};
    normal.outline_width = .15f; normal.underlay_x = .1f;
    normal.underlay_y = -.2f; normal.underlay_alpha = .6f; normal.keywords = 1;
    selected.outline_width = .25f; selected.underlay_alpha = .8f;
    selected.underlay_y = -.3f; selected.keywords = 3;
    rendered.material = &normal;
    auto text = codec.from_bytes("经验");
    assert(prepare_tmp_text(&rendered, &text) == &text);
    auto visible = static_cast<Converted *>(rendered.material);
    assert(visible->style == 10 && visible->underlay_alpha == .6f && visible->keywords == 1);
    normal.style = 11; normal.underlay_alpha = .4f; normal.underlay_y = -.4f;
    prepare_tmp_text(&rendered, &text);
    assert(visible->style == 11 && visible->underlay_alpha == .4f && visible->underlay_y == -.4f);
    assert(rendered.padding_updates == 1);
    prepare_tmp_text(&rendered, &text);
    assert(rendered.padding_updates == 1); // Unchanged parsing must not re-dirty Ruby/layout.
    // The shared game component cached the source before the font switch.
    // Its write must refresh the visible clone even with no text parse/setter.
    MaterialWriter writer{&rendered, &normal, .31f, .72f, .08f};
    replacement_material_write(&writer, nullptr);
    assert(visible->outline_width == .31f && visible->underlay_alpha == .72f && visible->face_dilate == .08f);
    assert(rendered.padding_updates == 2);
    assert(rendered.redraws == 1);
    replacement_material_write(&writer, nullptr);
    assert(rendered.padding_updates == 2); // No perpetual geometry invalidation.
    assert(rendered.redraws == 1);
    // A material-array or internal Unity assignment bypasses the singular setter.
    rendered.material = &selected;
    prepare_tmp_text(&rendered, &text);
    assert(rendered.material != &selected);
    visible = static_cast<Converted *>(rendered.material);
    assert(visible->style == 20 && visible->outline_width == .25f && visible->keywords == 3);
    assert(rendered.padding_updates == 3);
    TmpComponentState saved;
    assert(find_tmp_component_state(&rendered, &saved) && saved.shared_material == &selected);
    saved = {};
    // A local Chinese material clone may change shadow/face styling, but it
    // must never replace the original-font restore preset with a Chinese atlas.
    Preset local = *visible;
    local.style = 30; local.underlay_alpha = .2f; local.face_dilate = .1f;
    rendered.material = &local;
    const auto conversions_before_local = converted_cache.size();
    prepare_tmp_text(&rendered, &text);
    assert(rendered.material == &local);
    assert(converted_cache.size() == conversions_before_local);
    assert(local.style == 30 && local.underlay_alpha == .2f && local.face_dilate == .1f);
    writer = {&rendered, &local, .35f, .2f, .1f};
    replacement_material_write(&writer, nullptr);
    assert(rendered.material == &local && local.outline_width == .35f);
    assert(find_tmp_component_state(&rendered, &saved) && saved.shared_material == &selected);
    saved = {};
    local.underlay_alpha = .1f;
    local.underlay_y = -.6f;
    // Animation edits the instance held by the game without assigning text.
    assert(static_cast<Preset *>(rendered.material)->underlay_alpha == .1f);
    assert(static_cast<Preset *>(rendered.material)->underlay_y == -.6f);
    prepare_tmp_text(&rendered, &text);
    assert(rendered.material == &local);
    for (int i = 0; i < 3; ++i) {
        auto instance_read_by_game = rendered.material;
        prepare_tmp_text(&rendered, &text);
        assert(rendered.material == instance_read_by_game);
        assert(converted_cache.size() == conversions_before_local);
    }
    auto checksum_api = g_material_compute_crc_info;
    g_material_compute_crc_info = nullptr;
    auto padding_count = rendered.padding_updates;
    prepare_tmp_text(&rendered, &text);
    prepare_tmp_text(&rendered, &text);
    assert(rendered.padding_updates == padding_count);
    g_material_compute_crc_info = checksum_api;
    forget(rendered);
    assert(retained == released);
    Component thread_dialog{301, &japanese_font};
    thread_dialog.material = &selected;
    auto dialog_text = codec.from_bytes("纺锤采光 (暴食) - 5\n是否跳过战斗？");
    assert(prepare_tmp_text(&thread_dialog, &dialog_text) == &dialog_text);
    assert(thread_dialog.font == &chinese_font);
    visible = static_cast<Converted *>(thread_dialog.material);
    assert(visible->outline_width == selected.outline_width && visible->underlay_alpha == selected.underlay_alpha);
    assert(visible->underlay_y == selected.underlay_y && visible->keywords == selected.keywords);
    auto unrelated = codec.from_bytes("尚未确认来源的正文");
    const int restored_before = restore_calls;
    prepare_tmp_text(&thread_dialog, &unrelated);
    assert(restore_calls == restored_before + 1);
    forget(thread_dialog);
    assert(retained == released);
    Component untouched{302, &japanese_font};
    untouched.material = &normal;
    writer = {&untouched, &normal, .2f, .4f, .05f};
    replacement_material_write(&writer, nullptr);
    assert(untouched.material == &normal && untouched.padding_updates == 0);
    assert(untouched.font == &japanese_font);
    puts("Material styles, live sharing, cache boundary, destruction and failure fallback passed");
}
