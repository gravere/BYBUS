#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusManagedState.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFontProof.h"
#include <atomic>
#include <cassert>
#include <codecvt>
#include <cstdio>
#include <locale>
#include <mutex>
#include <unordered_set>

struct FakeHandle { void *object; bool weak; };
std::unordered_map<limbus_managed::Handle, FakeHandle> handles;
// Exercise nonzero upper bits; a 32-bit mock hid the actual ARM64 ABI error.
uintptr_t next_handle = UINT64_C(0x123400000000);
limbus_managed::Handle fake_retain(void *object, bool) {
    auto h = reinterpret_cast<void *>(++next_handle); handles[h] = {object, false}; return h;
}
limbus_managed::Handle fake_weak(void *object, bool) {
    auto h = reinterpret_cast<void *>(++next_handle); handles[h] = {object, true}; return h;
}
void *fake_target(limbus_managed::Handle h) { auto found = handles.find(h); return found == handles.end() ? nullptr : found->second.object; }
void fake_release(limbus_managed::Handle h) { handles.erase(h); }
void collect_object(void *object) { for (auto &entry : handles) if (entry.second.weak && entry.second.object == object) entry.second.object = nullptr; }
limbus_managed::GcApi g_gc{fake_retain, fake_weak, fake_target, fake_release};
limbus_managed::Registry<bool> g_translated_objects(g_gc, 262144);
limbus_managed::RecentOutputs g_recent_font_outputs;
limbus_font_proof::Catalogue g_font_catalogue;
std::unordered_map<std::string, std::string> g_index, g_contextual_index;
std::atomic<uint64_t> g_contextual_hits{0}, g_acquisition_hits{0};
// Shim the two mutex calls, not the production translation decisions.
std::mutex g_font_state_lock;
#define pthread_mutex_lock(p) (p)->lock()
#define pthread_mutex_unlock(p) (p)->unlock()
#define LT_LOGI(...) ((void)0)
struct ManagedString { std::u16string text; };
std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> codec;
int32_t length(void *s) { return static_cast<ManagedString *>(s)->text.size(); }
const uint16_t *chars(void *s) { return reinterpret_cast<const uint16_t *>(static_cast<ManagedString *>(s)->text.data()); }
auto g_string_length = length;
auto g_string_chars = chars;
bool utf16_to_utf8(const uint16_t *s, int32_t length, std::string *out) {
    *out = codec.to_bytes(reinterpret_cast<const char16_t *>(s), reinterpret_cast<const char16_t *>(s) + length);
    return true;
}
std::vector<std::unique_ptr<ManagedString>> strings;
void *new_managed_string(const std::string &text) {
    strings.emplace_back(new ManagedString{codec.from_bytes(text)});
    return strings.back().get();
}
bool strip_line_height_tags(const std::string &, std::string *) { return false; }
std::string apply_dante_line_height(const std::string &s) { return s; }
bool translate_embedded_terms(const std::string &, std::string *) { return false; }
bool is_usable_display_text(const std::string &) { return true; }
bool font_ready = true;
bool ensure_tmp_font() { return font_ready; }

// Generated from the current runtime, not a separate implementation of the algorithm.
#include "runtime-translation-under-test.inc"

std::string text(void *s) { std::string result; utf16_to_utf8(chars(s), length(s), &result); return result; }
std::unordered_set<void *> destroyed;
bool native_alive(void *object) { return !destroyed.count(object); }

int main() {
    g_index = {{"ガード", "防御"}, {"防御", "抵御"}};
    g_contextual_index[std::string("defense") + '\x1f' + "name" + '\x1f' + "防御"] = "抵御";
    g_font_catalogue.add("防御"); g_font_catalogue.add("抵御");
    assert(text(translate_managed_string(new_managed_string("防御"), "defense", "name")) == "抵御");
    void *guard = translate_managed_string(new_managed_string("ガード"));
    assert(text(guard) == "防御");
    assert(text(translate_managed_string(new_managed_string("防御"), "defense", "name")) == "抵御");
    assert(translate_managed_string(guard) == guard);
    void *copy = new_managed_string("防御");
    assert(translate_managed_string(copy) == copy); // Never chain a copied Chinese result.

    void *dynamic = new_managed_string("已译字段格式化后的动态显示");
    assert(remember_translated_text(dynamic, text(dynamic)));
    for (int n = 0; n < 6000; ++n) {
        auto value = new_managed_string("计时" + std::to_string(n));
        assert(remember_translated_text(value, text(value)));
    }
    assert(is_translated_managed_string(dynamic)); // No mass eviction of live proof.
    assert(translate_managed_string(dynamic) == dynamic);
    collect_object(dynamic);
    static_cast<ManagedString *>(dynamic)->text = u"ガード"; // Reused address, different object.
    assert(text(translate_managed_string(dynamic)) == "防御");
    font_ready = false;
    auto original = new_managed_string("ガード");
    assert(translate_managed_string(original) == original);

    int a, b, c, font;
    struct Saved { limbus_managed::Ref font; };
    const auto before = handles.size();
    {
        limbus_managed::Registry<Saved> state(g_gc, 2);
        state.set_alive(native_alive);
        assert(state.put(&a, 1, {limbus_managed::retain(g_gc, &font)}));
        assert(state.put(&b, 2, {limbus_managed::retain(g_gc, &font)}));
        assert(!state.put(&c, 3, {})); // Keep live restoration state at the bound.
        assert(state.find(&a, 1) && state.find(&b, 2));
        destroyed.insert(&a);
        state.collect(); // Unity destroyed, even though the managed wrapper is alive.
        assert(state.size() == 1);
        assert(state.put(&c, 3, {}));
        assert(!state.find(&b, 22)); // Instance ID reuse cannot inherit another style.
        Saved saved;
        assert(state.take(&c, 3, &saved));
        assert(state.size() == 0);
    }
    assert(handles.size() == before); // Weak owners and retained original assets released.
    puts("Managed provenance, context order, GC/address reuse, native destruction and bounds passed");
}
