#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFontMaterials.h"
#include <cassert>
#include <cstdio>
#include <mutex>
#include <vector>

struct TmpComponentState {
    void *shared_material = nullptr;
    limbus_managed::Ref material_root;
    limbus_font_materials::Lease converted_material;
};
struct Component { int id; void *font; bool alive = true; };
struct Preset { int style; };
struct Converted { int style = 0; int references = 0; int refreshes = 0; };
void *keep(void *object, bool) { return object; }
void *target(void *handle) { return handle; }
void release(void *) {}
limbus_managed::GcApi g_gc{keep, keep, target, release};
limbus_managed::Registry<TmpComponentState> g_original_tmp_states(g_gc, 256);
std::mutex g_font_state_lock;
#define pthread_mutex_lock(p) (p)->lock()
#define pthread_mutex_unlock(p) (p)->unlock()
int chinese_font, chinese_material, japanese_font, conversion_method, retain_method, release_method;
void *g_tmp_font_asset = &chinese_font;
const void *g_tmp_get_font_info = &chinese_font;
const void *g_tmp_font_get_material_info = &chinese_material;
const void *g_get_fallback_material_info = &conversion_method;
const void *g_retain_fallback_material_info = &retain_method;
const void *g_release_fallback_material_info = &release_method;
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
void *invoke_object_getter(const void *, void *font) {
    assert(font == g_tmp_font_asset);
    return &chinese_material;
}
void *invoke(const void *method, void *, void **args, void **exception) {
    if (method == g_get_fallback_material_info) {
        assert(args[1] == &chinese_material);
        if (fail_conversion) { *exception = &conversion_method; return nullptr; }
        auto &converted = converted_cache[args[0]];
        if (!converted) converted = std::make_unique<Converted>();
        converted->style = static_cast<Preset *>(args[0])->style;
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

void forget(Component &component) {
    TmpComponentState saved;
    assert(g_original_tmp_states.take(&component, component.id, &saved));
}

int main() {
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
    puts("Material styles, live sharing, cache boundary, destruction and failure fallback passed");
}
