#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusManagedState.h"
#include <cassert>
#include <cstdio>
#include <mutex>
#include <unordered_set>

struct TmpComponentState {
    void *shared_material = nullptr;
    limbus_managed::Ref material_root;
};
void *keep(void *object, bool) { return object; }
void *target(void *handle) { return handle; }
void release(void *) {}
limbus_managed::GcApi g_gc{keep, keep, target, release};
limbus_managed::Registry<TmpComponentState> g_original_tmp_states(g_gc, 8);
std::mutex g_font_state_lock;
#define pthread_mutex_lock(p) (p)->lock()
#define pthread_mutex_unlock(p) (p)->unlock()
int chinese_font, chinese_material, japanese_font, japanese_material, converted_material;
void *g_tmp_font_asset = &chinese_font;
void *current_font = nullptr;
const void *g_tmp_get_font_info = &chinese_font;
const void *g_tmp_font_get_material_info = &chinese_material;
const void *g_get_fallback_material_info = &converted_material;
const void *g_retain_fallback_material_info = &japanese_material;
std::unordered_map<void *, void *> g_font_materials;
std::unordered_set<void *> g_converted_font_materials;
int32_t component_id(void *) { return 7; }
void *get_font(void *, const void *) { return current_font; }
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
void *invoke(const void *method, void *, void **args, void **) {
    if (method == g_get_fallback_material_info) {
        assert(args[0] == &japanese_material && args[1] == &chinese_material);
        return &converted_material;
    }
    assert(method == g_retain_fallback_material_info && args[0] == &converted_material);
    return nullptr;
}
auto g_runtime_invoke = invoke;
#include "runtime-material-under-test.inc"

int main() {
    int component;
    current_font = &japanese_font;
    assert(translated_tmp_material(&component, &japanese_material) == &japanese_material);
    current_font = &chinese_font;
    // A missing/collected state must not bind a Japanese atlas to Chinese glyphs.
    assert(translated_tmp_material(&component, &japanese_material) == &chinese_material);
    assert(g_original_tmp_states.put(&component, 7, {}));
    assert(translated_tmp_material(&component, &japanese_material) == &converted_material);
    assert(translated_tmp_material(&component, &converted_material) == &converted_material);
    assert(translated_tmp_material(&component, nullptr) == &chinese_material);
    TmpComponentState saved;
    assert(g_original_tmp_states.take(&component, 7, &saved));
    assert(saved.shared_material == &japanese_material);
    assert(translated_tmp_material(&component, &japanese_material) == &chinese_material);
    puts("Production material conversion, lost-state atlas safety and original styling passed");
}
