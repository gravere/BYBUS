#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusManagedState.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_map>

#define LT_LOGI(...) ((void)0)
int g_lock = 0, simulated_thread = 1, g_unity_init_thread = 2;
void pthread_mutex_lock(int *mutex) { assert(!*mutex); *mutex = 1; }
void pthread_mutex_unlock(int *mutex) { assert(*mutex); *mutex = 0; }
int pthread_self() { return simulated_thread; }
bool pthread_equal(int a, int b) { return a == b; }
bool g_font_ready = false, g_font_gc_validated = false, g_hook_installed = true;
unsigned g_font_attempts = 0;
thread_local bool g_inside_font_init = false;
char g_active_pointer[] = "/private/translation-cache/index/active.txt";
std::unordered_map<std::string, std::string> g_index{{"source", "translation"}};
bool main_thread = true, thread_exception = false, settings_ready = true;
int create_failures = 0, add_failures = 0, creates = 0, adds = 0, ui_loads = 0, receipts = 0;
bool contained = false, reenter = false, ui_available = true;
int probe, asset, ui, list, list_class, main_method, settings_method, create_method, add_method, contains_method, destroy_method;
int roots = 0;
void *keep(void *object, bool) { if (!object) return nullptr; ++roots; return object; }
void *target(void *handle) { return handle; }
void release(void *) { --roots; }
limbus_managed::GcApi g_gc{keep, keep, target, release};
limbus_managed::Ref g_tmp_font_root, g_ui_font_root;
void *g_tmp_font_asset = nullptr, *g_ui_font = nullptr;
bool g_ui_uses_system_font = true;
const void *g_current_thread_is_main_info = &main_method;
const void *g_get_fallback_fonts_info = &settings_method;
const void *g_create_tmp_font_info = &create_method;
const void *g_destroy_object_info = &destroy_method;
const void *g_create_dynamic_font_info = nullptr;
void *new_managed_string(const std::string &) { return &probe; }
limbus_managed::Ref load_bundled_ui_font(void *) { ++ui_loads; return ui_available ? limbus_managed::retain(g_gc, &ui) : limbus_managed::Ref{}; }
void append_native_metadata(const char *, const char *) {}
void write_runtime_receipt(const char *) { ++receipts; }
void *object_class(void *object) { assert(object == &list); return &list_class; }
auto g_object_get_class = object_class;
const void *method(void *klass, const char *name, int count) {
    assert(klass == &list_class && count == 1);
    if (!strcmp(name, "Add")) return &add_method;
    assert(!strcmp(name, "Contains")); return &contains_method;
}
auto g_class_get_method = method;
void *unbox(void *object) { return object; }
auto g_object_unbox = unbox;
bool ensure_tmp_font();
void *invoke(const void *entry, void *object, void **args, void **exception) {
    static uint8_t boolean;
    if (entry == &main_method) {
        assert(!object && !args);
        if (thread_exception) *exception = &main_method;
        boolean = main_thread; return &boolean;
    }
    if (entry == &settings_method) return settings_ready ? &list : nullptr;
    if (entry == &create_method) {
        assert(!object && *static_cast<int32_t *>(args[5]) == 4096 && *static_cast<int32_t *>(args[6]) == 4096);
        ++creates;
        if (reenter) assert(!ensure_tmp_font()); // No recursive lock / extra attempt.
        if (create_failures-- > 0) { *exception = &create_method; return nullptr; }
        return &asset;
    }
    assert(object == &list && args[0] == &asset);
    if (entry == &contains_method) { boolean = contained; return &boolean; }
    assert(entry == &add_method);
    ++adds;
    if (add_failures-- > 0) *exception = &add_method;
    else contained = true;
    return nullptr;
}
auto g_runtime_invoke = invoke;
#include "runtime-font-init-under-test.inc"

void reset() {
    g_tmp_font_root.reset(); g_ui_font_root.reset(); assert(roots == 0 && !g_lock && !g_inside_font_init);
    g_tmp_font_asset = g_ui_font = nullptr;
    g_font_ready = g_font_gc_validated = contained = reenter = thread_exception = false;
    g_font_attempts = creates = adds = ui_loads = receipts = create_failures = add_failures = 0;
    main_thread = settings_ready = ui_available = true;
    g_current_thread_is_main_info = &main_method;
}
int main() {
    // Main UI thread differs from init thread; the Unity API takes precedence.
    assert(simulated_thread != g_unity_init_thread && ensure_tmp_font());
    assert(g_font_ready && creates == 1 && adds == 1 && ui_loads == 1 && roots == 2);
    assert(ensure_tmp_font() && creates == 1 && receipts == 1);
    reset(); main_thread = false;
    assert(!ensure_tmp_font() && g_font_attempts == 0 && creates == 0);
    main_thread = true; thread_exception = true;
    assert(!ensure_tmp_font() && g_font_attempts == 0);
    thread_exception = false; settings_ready = false;
    assert(!ensure_tmp_font() && g_font_attempts == 0);
    settings_ready = true; create_failures = 1; reenter = true;
    assert(!ensure_tmp_font() && g_font_attempts == 1 && roots == 0);
    assert(ensure_tmp_font() && g_font_attempts == 2 && creates == 2);
    reset(); add_failures = 1;
    assert(!ensure_tmp_font() && creates == 1 && roots == 1 && ui_loads == 0);
    assert(ensure_tmp_font() && creates == 1 && adds == 2 && ui_loads == 1 && roots == 2);
    reset(); create_failures = 9;
    for (int i = 0; i < 5; ++i) assert(!ensure_tmp_font());
    assert(g_font_attempts == 3 && creates == 3 && receipts == 3 && roots == 0);
    reset(); g_current_thread_is_main_info = nullptr;
    assert(!ensure_tmp_font() && g_font_attempts == 0);
    simulated_thread = g_unity_init_thread;
    assert(ensure_tmp_font());
    reset();
    puts("Real font initialization: thread gate, deferred settings, retry, asset reuse and reentrancy passed");
}
