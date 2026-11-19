#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFontMaterials.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFontProof.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFontScale.h"
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
struct Component { int id; void *font; bool alive = true; void *material = nullptr; int padding_updates = 0; int redraws = 0; void *text = nullptr; bool language_owned = false;
    void *inline_font = nullptr, *inline_material = nullptr, *mesh_material = nullptr;
};
struct Preset {
    int style = 0;
    float outline_width = 0, underlay_x = 0, underlay_y = 0, underlay_alpha = 0, face_dilate = 0;
    unsigned keywords = 0;
    bool chinese_atlas = false;
    float gradient = 6, normal = 0, bold = .75f, ratio_a = 1, ratio_b = 1, ratio_c = 1;
    float outline_softness = 0, shadow_softness = 0, shadow_dilate = 0, glow_offset = 0, glow_outer = 0;
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
int writer_get_text_method, dirty_method, text_method;
int set_float_method;
const void *g_material_set_float_info = nullptr;
std::string property_name;
Preset source_font_material;
float original_point = 110;
float font_point_size(void *font) { return font == &chinese_font ? 34.f : original_point; }
float read_material_number(void *object, const char *name) {
    if (object == &chinese_material) object = &source_font_material;
    auto &p = *static_cast<Preset *>(object);
    const std::string key = name;
    if (key == "_GradientScale") return p.gradient;
    if (key == "_WeightNormal") return p.normal;
    if (key == "_WeightBold") return p.bold;
    if (key == "_FaceDilate") return p.face_dilate;
    if (key == "_OutlineWidth") return p.outline_width;
    if (key == "_OutlineSoftness") return p.outline_softness;
    if (key == "_UnderlayOffsetX") return p.underlay_x;
    if (key == "_UnderlayOffsetY") return p.underlay_y;
    if (key == "_UnderlaySoftness") return p.shadow_softness;
    if (key == "_UnderlayDilate") return p.shadow_dilate;
    if (key == "_GlowOffset") return p.glow_offset;
    if (key == "_GlowOuter") return p.glow_outer;
    assert(false); return -999;
}
int material_keyword(void *object, const char *name) {
    assert(std::string(name) == "RATIOS_OFF");
    return (static_cast<Preset *>(object)->keywords & 8) != 0;
}
const void *g_tmp_get_text_info = &text_method;
bool g_font_ready = true;
thread_local void *g_language_font_update_target = nullptr;
void *game_font_material = nullptr;
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
    if (method == g_tmp_get_text_info) return static_cast<Component *>(object)->text;
    if (method == g_material_writer_get_text_info) return static_cast<MaterialWriter *>(object)->text;
    if (method == g_tmp_get_shared_material_info) return static_cast<Component *>(object)->material;
    if (object == &japanese_font) return &source_font_material;
    assert(object == g_tmp_font_asset);
    return &chinese_material;
}
void *invoke(const void *method, void *object, void **args, void **exception) {
    if (method == &set_float_method) {
        auto &p = *static_cast<Preset *>(object);
        auto &key = *static_cast<std::string *>(args[0]);
        float value = *static_cast<float *>(args[1]);
        if (key == "_ScaleRatioA") p.ratio_a = value;
        else if (key == "_ScaleRatioB") p.ratio_b = value;
        else { assert(key == "_ScaleRatioC"); p.ratio_c = value; }
        return nullptr;
    }
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
    property_name = value;
    return &property_name;
}
bool remember_translated_text(void *, const std::string &) { assert(false); return false; }
void *apply_tmp_component_layout_markup(void *, void *text) { return text; }
limbus_font_proof::Catalogue fonts;
bool is_translated_managed_string(void *text) { return fonts.contains(codec.to_bytes(*static_cast<std::u16string *>(text))); }
bool is_translated_tmp_fragment(void *, void *, std::vector<void *> * = nullptr) { return false; }
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
    static_cast<Component *>(object)->material = font == g_tmp_font_asset ? &chinese_material : game_font_material;
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
bool is_language_font_target(void *p) { return static_cast<Component *>(p)->language_owned; }
#include "runtime-prepare-text-under-test.inc"
#include "runtime-font-refresh-under-test.inc"

int inline_font_field, inline_material_field;
void inline_get_field(void *object, const void *field, void *out) {
    auto &c=*static_cast<Component *>(object);
    *static_cast<void **>(out)=field==&inline_font_field?c.inline_font:c.inline_material;
}
void inline_set_field(void *object, const void *field, void *value) {
    assert(field==&inline_material_field);
    static_cast<Component *>(object)->inline_material=*static_cast<void **>(value);
}
auto g_field_get_value=inline_get_field;
auto g_field_set_value=inline_set_field;
#include "runtime-inline-material-under-test.inc"
int32_t add_inline_reference(void *material, void *font, void **references, void *, const void *) {
    assert(font); *references=material; return 7;
}
bool parse_inline_tag(void *object, void *, int32_t start, int32_t *end, const void *) {
    auto &c=*static_cast<Component *>(object);
    assert(g_inline_material_target==object);
    if(start<0)return false;
    assert(replacement_tmp_add_material(c.inline_material,c.inline_font,&c.mesh_material,nullptr,nullptr)==7);
    *end=start+3; return true;
}

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
    g_language_font_update_target = &first;
    assert(translated_tmp_material(&first, &preset) == &preset);
    g_language_font_update_target = nullptr;
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
    // Follow the complete production font setter -> text preparation -> material
    // conversion chain, without relying on another text assignment or parse.
    Component rebound{901, &japanese_font};
    Preset initial{91}, changed{92}; changed.outline_width = .2f; changed.underlay_alpha = .6f;
    auto caption = codec.from_bytes("经验"); rebound.text = &caption; rebound.material = &initial;
    prepare_tmp_text(&rebound, &caption);
    assert(rebound.font == &chinese_font);
    game_font_material = &changed;
    replacement_tmp_set_font(&rebound, &japanese_font, nullptr);
    assert(rebound.font == &chinese_font);
    auto painted = static_cast<Converted *>(rebound.material);
    assert(painted->chinese_atlas && painted->style == 92 && painted->outline_width == .2f && painted->underlay_alpha == .6f);
    TmpComponentState restore;
    assert(find_tmp_component_state(&rebound, &restore));
    assert(restore.font == &japanese_font && restore.shared_material == &changed);
    forget(rebound); restore = {};
    caption = codec.from_bytes("未知原文");
    replacement_tmp_set_font(&rebound, &japanese_font, nullptr);
    assert(rebound.font == &japanese_font && rebound.material == &changed);
    // A language-owned target needs no recently seen whole-string proof.
    // Typewriter prefixes and tag states keep one face while preserving the game's material.
    rebound.language_owned = true;
    const int restores_before_owner = restore_calls;
    for (const auto part : {"", "事", "事件过程", "特性标签", "事"}) {
        caption = codec.from_bytes(part);
        prepare_tmp_text(&rebound, &caption);
        assert(rebound.font == &chinese_font);
        game_font_material = &changed;
        replacement_tmp_set_font(&rebound, &japanese_font, nullptr);
        assert(rebound.font == &chinese_font);
        auto state = static_cast<Converted *>(rebound.material);
        assert(state->outline_width == .2f && state->underlay_alpha == .6f);
    }
    assert(restore_calls == restores_before_owner);
    forget(rebound);
    // A <material> style registers a submesh directly, bypassing the public
    // material setter. Both the stored mesh and the active parser material
    // must use the Chinese atlas, with source outline and shadow intact.
    g_original_tmp_validate_tag=parse_inline_tag;
    g_original_tmp_add_material=add_inline_reference;
    g_tmp_current_font_field=&inline_font_field;
    g_tmp_current_material_field=&inline_material_field;
    Component inline_text{902,&japanese_font}; inline_text.language_owned=true;
    Preset red{95}; red.outline_width=.15f;red.underlay_alpha=.4f;red.keywords=5;
    inline_text.material=&red;prepare_tmp_text(&inline_text,&caption);
    inline_text.inline_font=&chinese_font;inline_text.inline_material=&red;
    int32_t end=0;
    assert(replacement_tmp_validate_tag(&inline_text,nullptr,4,&end,nullptr)&&end==7);
    auto inline_painted=static_cast<Converted *>(inline_text.inline_material);
    assert(inline_text.mesh_material==inline_painted&&inline_painted->chinese_atlas);
    assert(inline_painted->outline_width==.15f&&inline_painted->underlay_alpha==.4f&&inline_painted->keywords==5);
    assert(!red.chinese_atlas&&inline_text.material!=&red&&g_inline_material_target==nullptr);
    TmpComponentState inline_restore;assert(find_tmp_component_state(&inline_text,&inline_restore));
    assert(inline_restore.shared_material==&red);inline_restore={};
    red.underlay_alpha=.9f;inline_text.inline_material=&red;
    replacement_tmp_validate_tag(&inline_text,nullptr,0,&end,nullptr);
    assert(static_cast<Converted *>(inline_text.mesh_material)->underlay_alpha==.9f);
    // Explicit numeric fonts and non-owned labels never receive Chinese material.
    inline_text.inline_font=&japanese_font;inline_text.inline_material=&red;
    replacement_tmp_validate_tag(&inline_text,nullptr,0,&end,nullptr);
    assert(inline_text.mesh_material==&red&&inline_text.inline_material==&red);
    inline_text.inline_font=&chinese_font;
    g_inline_material_target=&untouched;
    assert(!replacement_tmp_validate_tag(&inline_text,nullptr,-1,&end,nullptr));
    assert(g_inline_material_target==&untouched);g_inline_material_target=nullptr;
    clear_inline_tmp_materials(&inline_text);forget(inline_text);
    inline_text.inline_material=&red;
    replacement_tmp_validate_tag(&inline_text,nullptr,0,&end,nullptr);
    assert(inline_text.mesh_material==&red&&inline_text.inline_material==&red);
    // Actual conversion/refresh hook with the 110pt English source observed on
    // device. Compare physical outline/shadow widths, not just equal numbers.
    g_material_set_float_info = &set_float_method;
    g_original_shader_ratios = [](void *object, const void *) {
        auto &p = *static_cast<Preset *>(object);
        p.ratio_a = p.ratio_b = p.ratio_c = .833333333f;
    };
    Component pass{950, &japanese_font}; pass.language_owned = true;
    Preset pass_style{96}; pass_style.outline_width = .3f; pass_style.underlay_y = -.4f;
    pass.material = &pass_style;
    prepare_tmp_text(&pass, &caption);
    auto scaled = static_cast<Converted *>(pass.material);
    auto near = [](float a, float b) { assert(std::abs(a - b) < .00001f); };
    near(scaled->outline_width * scaled->ratio_a * 6 / 34, .3f * (5.f / 6) * 6 / 110);
    near(scaled->underlay_y * scaled->ratio_c * 6 / 34, -.4f * (5.f / 6) * .8125f * 6 / 110);
    near(pass_style.ratio_a, 1); // Never writes source game material.
    const float first_ratio = scaled->ratio_a;
    replacement_shader_ratios(scaled, nullptr); // TMP GetPadding recalculation.
    replacement_shader_ratios(scaled, nullptr);
    near(scaled->ratio_a, first_ratio); // No accumulating rescale.
    pass_style.outline_width = .6f;
    prepare_tmp_text(&pass, &caption);
    near(scaled->outline_width * scaled->ratio_a * 6 / 34, .6f * (5.f / 6) * 6 / 110);
    Preset animated = *scaled; animated.style = 97; pass.material = &animated;
    prepare_tmp_text(&pass, &caption);
    animated.underlay_y = -.8f;
    replacement_shader_ratios(&animated, nullptr);
    near(animated.underlay_y * animated.ratio_c * 6 / 34, -.8f * (5.f / 6) * .8125f * 6 / 110);
    assert(pass.material == &animated && animated.underlay_y == -.8f);
    forget(pass);
    // Exercise different atlas padding as well as different point sizes.
    for (const auto metrics : {std::pair<float,float>{34,6}, {51,5}, {110,6}, {195,12}}) {
        limbus_font_scale::Basis basis{metrics.first, metrics.second, 0, .75f, 34};
        limbus_font_scale::Style s; s.outline=.3f;s.shadow_y=-.4f;s.glow_outer=.5f;
        auto r=limbus_font_scale::calculate(basis,s,6);
        near(r.a*6/34,(metrics.second-1)/metrics.second*metrics.second/metrics.first);
        near(r.c*6/34,(metrics.second-1)*.8125f/metrics.first);
        s.ratios_off=true;r=limbus_font_scale::calculate(basis,s,6);
        near(r.a*6/34,metrics.second/metrics.first);
    }
    puts("Material styles, lifecycle, inline styles, physical SDF scale and animated clones passed");
}
