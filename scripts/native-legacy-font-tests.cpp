#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusManagedState.h"
#include <cassert>
#include <cstdio>

int font, font_class, font_path, ctor, dynamic_method, has_glyph_method, destroy_method;
bool fail_retention = false, fail_ctor = false, dynamic_font = true, missing_glyph = false;
bool alive = false;
int roots = 0, constructors = 0, destroyed = 0, glyph_checks = 0;
void *keep(void *object, bool) { if (fail_retention) return nullptr; ++roots; return object; }
void *target(void *handle) { return handle; }
void release(void *) { --roots; }
limbus_managed::GcApi g_gc{keep, keep, target, release};
void *create(void *klass) { assert(klass == &font_class); return &font; }
auto g_object_new = create;
void *g_unity_font_class = &font_class;
const void *g_font_path_constructor_info = &ctor;
const void *g_font_dynamic_info = &dynamic_method;
const void *g_font_has_character_info = &has_glyph_method;
const void *g_destroy_object_info = &destroy_method;
bool unity_object_alive(void *object) { assert(object == &font); return alive; }
bool invoke_bool_getter(const void *method, void *object, bool *value) {
    assert(method == &dynamic_method && object == &font && alive);
    *value = dynamic_font; return true;
}
void *unbox(void *object) { return object; }
auto g_object_unbox = unbox;
void *invoke(const void *method, void *object, void **args, void **exception) {
    if (method == &ctor) {
        assert(object == &font && args[0] == &font_path);
        ++constructors;
        if (fail_ctor) { *exception = &ctor; return nullptr; }
        alive = true;
    } else if (method == &has_glyph_method) {
        assert(object == &font && alive);
        ++glyph_checks;
        static uint8_t result;
        result = !(missing_glyph && *static_cast<int32_t *>(args[0]) == 0x8bd1);
        return &result;
    } else {
        assert(method == &destroy_method && object == nullptr && args[0] == &font && alive);
        ++destroyed; alive = false;
    }
    return nullptr;
}
auto g_runtime_invoke = invoke;
#include "runtime-legacy-font-under-test.inc"

int main() {
    {
        auto result = load_bundled_ui_font(&font_path);
        assert(result && result->get() == &font && glyph_checks == 4 && roots == 1);
    }
    assert(roots == 0);
    missing_glyph = true;
    assert(!load_bundled_ui_font(&font_path) && roots == 0 && !alive && destroyed == 1);
    missing_glyph = false; dynamic_font = false;
    assert(!load_bundled_ui_font(&font_path) && roots == 0 && !alive && destroyed == 2);
    dynamic_font = true; fail_ctor = true;
    assert(!load_bundled_ui_font(&font_path) && roots == 0 && !alive && destroyed == 2);
    fail_ctor = false; fail_retention = true;
    int calls = constructors;
    assert(!load_bundled_ui_font(&font_path) && constructors == calls && roots == 0);
    fail_retention = false; g_font_has_character_info = nullptr;
    assert(!load_bundled_ui_font(&font_path) && constructors == calls);
    for (int style = 0; style < 4; ++style) {
        assert(translated_legacy_font_style(style, true) == (style | 1));
        assert(translated_legacy_font_style(style, false) == style);
    }
    assert(translated_legacy_font_style(-1, true) == -1);
    puts("Legacy Font validation, failed creation cleanup and fallback weight passed");
}
