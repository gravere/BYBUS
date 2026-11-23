// Exercise the production profile-selection code against managed boundary doubles.
// Original game layout methods are modelled; no Android or rendered-screen claim.
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusManagedState.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <vector>

struct Object { int32_t id = 1; bool alive = true; };
void *keep(void *p, bool) { return p; }
void *resolve(void *p) { return static_cast<Object *>(p)->alive ? p : nullptr; }
void release(void *) {}
limbus_managed::GcApi g_gc{keep, keep, resolve, release};
std::mutex g_font_state_lock;
#define pthread_mutex_lock(p) (p)->lock()
#define pthread_mutex_unlock(p) (p)->unlock()
// These enum values intentionally differ from the current game's values.
std::unordered_map<int32_t, const char *> g_language_codes{{10,"jp"},{20,"en"},{30,"kr"}};
bool g_live_resources = true, g_font_ready = true;
struct Text { std::u16string value; bool translated = false; };
struct Component : Object { Text *text = nullptr; float size = 70, margin = 0; };
struct Profile { int32_t language; float value; };
struct Profiles {
    std::vector<Profile> entries;
    int32_t count = 0;
    bool count_error = false, item_error = false;
    explicit Profiles(std::initializer_list<Profile> data) : entries(data), count(entries.size()) {}
};
struct Owner : Object {
    Component *text; Profiles *profiles; bool enabled = true;
    int calls = 0; int32_t last_language = INT32_MIN;
    Owner(Component &t, Profiles &p) : text(&t), profiles(&p) {}
};
int target_field, profiles_field, language_field, count_method, item_method, enabled_method, text_method;
const void *g_behaviour_get_active_info = &enabled_method, *g_tmp_get_text_info = &text_method;
int32_t component_id(void *p) { return p ? static_cast<Object *>(p)->id : 0; }
bool unity_object_alive(void *p) { return p && static_cast<Object *>(p)->alive; }
void read_field(void *p, const void *field, void *out) {
    if (field == &target_field) *static_cast<void **>(out) = static_cast<Owner *>(p)->text;
    else if (field == &profiles_field) *static_cast<void **>(out) = static_cast<Owner *>(p)->profiles;
    else if (field == &language_field) *static_cast<int32_t *>(out) = static_cast<Profile *>(p)->language;
    else assert(false);
}
auto g_field_get_value = read_field;
void *invoke(const void *method, void *p, void **args, void **error) {
    auto &list = *static_cast<Profiles *>(p);
    if (method == &count_method) {
        if (list.count_error) *error = &count_method;
        return &list.count;
    }
    assert(method == &item_method);
    if (list.item_error) { *error = &item_method; return nullptr; }
    int index = *static_cast<int *>(args[0]);
    assert(index >= 0 && index < static_cast<int>(list.entries.size()));
    return &list.entries[index];
}
auto g_runtime_invoke = invoke;
void *unbox(void *p) { return p; }
auto g_object_unbox = unbox;
bool invoke_bool_getter(const void *method, void *p, bool *value) {
    assert(method == &enabled_method); *value = static_cast<Owner *>(p)->enabled; return true;
}
void *invoke_object_getter(const void *method, void *p) {
    assert(method == &text_method); return static_cast<Component *>(p)->text;
}
int32_t string_length(void *p) { return static_cast<Text *>(p)->value.size(); }
const uint16_t *string_chars(void *p) {
    return reinterpret_cast<const uint16_t *>(static_cast<Text *>(p)->value.data());
}
auto g_string_length = string_length;
auto g_string_chars = string_chars;
bool is_translated_managed_string(void *p) { return p && static_cast<Text *>(p)->translated; }
bool is_choice_text(void *, void *) { return false; }
bool is_translated_tmp_fragment(void *, void *) { return false; }
#include "runtime-localized-layout-under-test.inc"

template<int Slot> void game_profile(void *p, int32_t language, const void *) {
    auto &owner = *static_cast<Owner *>(p);
    ++owner.calls; owner.last_language = language;
    if (!owner.profiles) return;
    for (const auto &profile : owner.profiles->entries) if (profile.language == language) {
        (Slot == 0 ? owner.text->size : owner.text->margin) = profile.value;
        // Simulate a TMP refresh caused by the game's property setter. It must
        // not recurse into the owner whose profile is currently being applied.
        refresh_localized_layout(owner.text, owner.text->text);
        break;
    }
}
int main() {
    for (size_t slot = 0; slot < 2; ++slot) {
        auto &b = g_localized_layout_bindings[slot];
        b.target = &target_field; b.profiles = &profiles_field; b.language = &language_field;
        b.count = &count_method; b.item = &item_method;
        b.original = slot == 0 ? reinterpret_cast<void *>(game_profile<0>) : reinterpret_cast<void *>(game_profile<1>);
    }
    g_localized_layout_targets.set_alive(unity_object_alive);
    Text chinese{u"生效中", true}, latin{u"using", false}, source{u"使用中", false}, numbers{u"0/??", true};
    Component text; text.text = &chinese;
    Profiles sizes{{30,50},{20,72},{10,50}}, margins{{30,4},{20,0},{10,2}};
    Owner size(text, sizes), margin(text, margins);
    size.id = 2; margin.id = 3;
    // Raw game profile reproduces the English / Chinese mismatch (72 vs 50).
    game_profile<0>(&size,20,nullptr);
    assert(text.size == 72);
    replacement_localized_layout<0>(&size,20,nullptr);
    replacement_localized_layout<1>(&margin,20,nullptr);
    assert(text.size == 50 && text.margin == 2);
    assert(size.last_language == 10 && margin.last_language == 10);
    // A later translation setter fixes an owner originally updated before text.
    text.text = &latin; refresh_localized_layout(&text,&latin);
    assert(text.size == 72 && text.margin == 0);
    replacement_localized_layout<0>(&size,20,nullptr);
    refresh_localized_layout(&text,&chinese);
    assert(text.size == 50); // argument is new text; TMP's stored value is old.
    text.text = &chinese;
    // Do not undo manual game writes on every frame or translate arbitrary Han.
    text.size = 55; refresh_localized_layout(&text,&chinese);
    assert(text.size == 55);
    text.text = &source; refresh_localized_layout(&text,&source);
    assert(text.size == 72 && text.margin == 0);
    text.text = &numbers; refresh_localized_layout(&text,&numbers);
    assert(text.size == 72);
    // Native KR and JP profiles remain authoritative; a different configured
    // JP value is read each time, not replaced by a hardcoded 50-point rule.
    text.text = &chinese;
    sizes.entries[0].value = 57; sizes.entries[2].value = 48;
    replacement_localized_layout<0>(&size,30,nullptr); assert(text.size == 57);
    replacement_localized_layout<0>(&size,10,nullptr); assert(text.size == 48);
    replacement_localized_layout<0>(&size,20,nullptr); assert(text.size == 48);
    // Missing / corrupt / exceptional lists fall back to the game's request.
    sizes.entries[2].language = 40;
    replacement_localized_layout<0>(&size,20,nullptr); assert(text.size == 72);
    sizes.entries[2].language = 10;
    for (int count : {0,17}) {
        sizes.count = count; replacement_localized_layout<0>(&size,20,nullptr); assert(text.size == 72);
    }
    sizes.count = 3; sizes.count_error = true;
    replacement_localized_layout<0>(&size,20,nullptr); assert(text.size == 72);
    sizes.count_error = false; sizes.item_error = true;
    replacement_localized_layout<0>(&size,20,nullptr); assert(text.size == 72);
    sizes.item_error = false; size.enabled = false;
    replacement_localized_layout<0>(&size,20,nullptr); assert(text.size == 72);
    size.enabled = true; refresh_localized_layout(&text,&chinese); assert(text.size == 48);
    // A reused target is independent of the previous component's evidence.
    ++text.id; text.size = 71; refresh_localized_layout(&text,&chinese); assert(text.size == 71);
    replacement_localized_layout<0>(&size,20,nullptr); assert(text.size == 48);
    ++size.id; text.size = 71; refresh_localized_layout(&text,&latin); assert(text.size == 71);
    // Moving or destroying an owner cannot modify the previously owned target.
    Component other; other.id = 8; other.text = &chinese;
    replacement_localized_layout<0>(&size,20,nullptr);
    size.text = &other; text.size = 71;
    refresh_localized_layout(&text,&latin); assert(text.size == 71);
    size.text = &text; replacement_localized_layout<0>(&size,20,nullptr);
    size.alive = false; text.size = 71;
    refresh_localized_layout(&text,&latin); assert(text.size == 71);
    // Same component contract on the neighbouring buy label: EN80 / JP60.
    Profiles buy_sizes{{30,60},{20,80},{10,60}};
    Owner buy(other,buy_sizes); buy.id = 9;
    replacement_localized_layout<0>(&buy,20,nullptr); assert(other.size == 60);
    g_live_resources = false;
    replacement_localized_layout<0>(&buy,20,nullptr); assert(other.size == 80);
    g_live_resources = true; g_font_ready = false;
    replacement_localized_layout<0>(&buy,20,nullptr); assert(other.size == 80);
    std::puts("PASS localized layout: game profiles, proof, restoration, reentrancy, language and lifecycle boundaries");
}
