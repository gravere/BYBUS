#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusManagedState.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFontProof.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusTextProvenance.h"
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
std::unordered_map<std::string, std::string> g_terms;
bool g_live_resources = false;
thread_local std::string g_ui_text_key;
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
bool utf8_to_utf16(const std::string &s, std::u16string *out) { *out = codec.from_bytes(s); return true; }
std::vector<std::unique_ptr<ManagedString>> strings;
void *new_managed_string(const std::string &text) {
    strings.emplace_back(new ManagedString{codec.from_bytes(text)});
    return strings.back().get();
}
bool strip_line_height_tags(const std::string &, std::string *) { return false; }
std::string apply_dante_line_height(const std::string &s) { return s; }
bool is_usable_display_text(const std::string &) { return true; }
bool font_ready = true;
bool ensure_tmp_font() { return font_ready; }
#include "runtime-terms-under-test.inc"

// Generated from the current runtime, not a separate implementation of the algorithm.
#include "runtime-translation-under-test.inc"
using rich_text_one_fn = void *(*)(void *, const void *);
using rich_text_all_fn = void *(*)(void *, bool, const void *);
using rich_text_color_fn = void *(*)(void *, void *, const void *);
rich_text_one_fn g_original_rich_text_one[3] = {};
rich_text_all_fn g_original_rich_text_all = nullptr;
rich_text_color_fn g_original_rich_text_color = nullptr;
rich_text_color_fn g_original_rich_text_event = nullptr;
void *(*g_original_rich_text_skill)(void *, bool, bool, const void *) = nullptr;
#include "runtime-rich-text-under-test.inc"

std::string text(void *s) { std::string result; utf16_to_utf8(chars(s), length(s), &result); return result; }
std::unordered_set<void *> destroyed;
bool native_alive(void *object) { return !destroyed.count(object); }

using ruby_format_fn = void *(*)(void *, void *, const void *);
ruby_format_fn g_original_ruby_format[3] = {};
thread_local bool g_inside_ruby_format = false;
bool ruby_prepared = false;
bool ruby_layout_prepared = false;
void finalize_tmp_text(void *, void *) { ruby_layout_prepared = true; }
void *prepare_tmp_text(void *, void *value) {
    ruby_prepared = true;
    return translate_managed_string(value);
}
#include "runtime-ruby-under-test.inc"
void *format_ruby(void *, void *value, const void *) {
    assert(ruby_prepared); // Font preparation must precede the formatter's measurements.
    assert(ruby_layout_prepared);
    return new_managed_string("<pos=2><size=50%>Fabius</size><voffset=-1>" + text(value));
}

int main() {
    // Multiple gift abilities are formatted independently, then joined by the
    // game. The complete parts must retain identity across this final step.
    const std::string ability1 = "使目标获得<sprite name=\"Burn\"><link=\"Burn\">烧伤</link>。";
    const std::string ability2 = "<style=\"upgradeHighlight\">强化效果</style>\n下回合生效。";
    g_recent_font_outputs.add(ability1);
    g_recent_font_outputs.add(ability2);
    assert(is_translated_managed_string(new_managed_string(ability1 + "\n\n" + ability2)));
    assert(is_translated_managed_string(new_managed_string(ability1 + "<br>" + ability2)));
    assert(!is_translated_managed_string(new_managed_string(ability1 + "\n未知玩家正文")));
    assert(!is_translated_managed_string(new_managed_string("烧伤</link>。\n" + ability2)));
    assert(!is_translated_managed_string(new_managed_string("\n\n")));
    std::string many_abilities = ability1;
    for (int i = 1; i < 32; ++i) many_abilities += "\n" + ability1;
    assert(is_translated_managed_string(new_managed_string(many_abilities)));
    assert(!is_translated_managed_string(new_managed_string(many_abilities + "\n未知效果")));
    {
        std::string scene = "@story:outer";
        try {
            limbus_text_provenance::ScopedTextKey scope(scene, "@story:inner");
            throw 7;
        } catch (int value) {
            assert(value == 7);
        }
        assert(scene == "@story:outer");
    }
    g_index["共通の台詞"] = "全局译文";
    g_contextual_index[std::string("@story:A") + '\x1f' + "content" + '\x1f' + "共通の台詞"] = "剧情译文";
    g_contextual_index[std::string("@story:A/dialog:8") + '\x1f' + "content" + '\x1f' + "共通の台詞"] = "本行译文";
    assert(text(translate_managed_string(new_managed_string("共通の台詞"), "@story:A/dialog:8", "content")) == "本行译文");
    assert(text(translate_managed_string(new_managed_string("共通の台詞"), "@story:A/dialog:9", "content")) == "剧情译文");
    assert(text(translate_managed_string(new_managed_string("共通の台詞"), "@story:B/dialog:9", "content")) == "全局译文");
    g_index["入場"] = "进入战斗";
    g_contextual_index[std::string("enter") + '\x1f' + "content" + '\x1f' + "入場"] = "进入";
    {
        limbus_text_provenance::ScopedTextKey outer(g_ui_text_key, "enter");
        assert(text(translate_managed_string(new_managed_string("入場"))) == "进入");
        {
            limbus_text_provenance::ScopedTextKey inner(g_ui_text_key, "another-button");
            assert(text(translate_managed_string(new_managed_string("入場"))) == "进入战斗");
        }
        assert(g_ui_text_key == "enter");
        assert(text(translate_managed_string(new_managed_string("入場"))) == "进入");
        // Explicit object identities cannot inherit the enclosing UI key.
        assert(text(translate_managed_string(new_managed_string("入場"), "other", "content")) == "进入战斗");
    }
    assert(g_ui_text_key.empty());
    // Identical visible source text has different names in different typed tables.
    // Stable release identity must win even after a previous pass or cache eviction.
    g_model_fields[std::string("@skill-tag:X") + '\x1f' + "name"] = "技能特性";
    g_model_fields[std::string("@battle-keyword:X") + '\x1f' + "name"] = "战斗特性";
    for (const auto source : {"特性", "<color=red>特性</color>", "技能特性"}) {
        auto input = new_managed_string(source);
        assert(remember_translated_text(input, source));
        assert(text(translate_managed_string(input, "@skill-tag:X", "name")) == "技能特性");
        assert(text(translate_managed_string(input, "@battle-keyword:X", "name")) == "战斗特性");
    }
    font_ready = false;
    auto blocked = new_managed_string("未翻訳");
    assert(translate_managed_string(blocked, "@skill-tag:X", "name") == blocked);
    font_ready = true;
    assert(text(translate_managed_string(new_managed_string("入場"))) == "进入战斗");
    g_index = {{"ガード", "防御"}, {"防御", "抵御"}};
    g_contextual_index[std::string("defense") + '\x1f' + "name" + '\x1f' + "防御"] = "抵御";
    g_font_catalogue.add("防御"); g_font_catalogue.add("抵御");
    assert(text(translate_managed_string(new_managed_string("防御"), "defense", "name")) == "抵御");
    g_live_resources = true;
    auto merged_label = new_managed_string("防御");
    assert(translate_managed_string(merged_label, "defense", "name") == merged_label);
    g_live_resources = false;
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

    font_ready = true;
    g_font_catalogue.add("使目标获得2层流血");
    g_original_rich_text_one[0] = [](void *value, const void *) -> void * {
        return new_managed_string("<sprite name=\"Bleeding\"><style=\"highlightConverted\">" + text(value) + "</style>");
    };
    auto rich = replacement_rich_text_one<0>(new_managed_string("使目标获得2层流血"), nullptr);
    assert(!g_font_catalogue.contains(text(rich)) && is_translated_managed_string(rich));
    assert(is_translated_managed_string(new_managed_string(text(rich)))); // copied output retains full-string proof
    auto unverified_rich = replacement_rich_text_one<0>(new_managed_string("未知玩家正文"), nullptr);
    assert(!is_translated_managed_string(unverified_rich));
    assert(!is_translated_managed_string(new_managed_string(text(unverified_rich))));
    const std::string ruby = "你已经无处可逃了，<ruby=Fabius>费边</ruby>。";
    g_font_catalogue.add(ruby);
    g_original_ruby_format[0] = format_ruby;
    auto annotated = new_managed_string(ruby);
    auto generated = replacement_ruby_format<0>(&ruby_prepared, annotated, nullptr);
    assert(!g_font_catalogue.contains(text(generated)));
    assert(is_translated_managed_string(generated));
    assert(text(generated) == "<pos=2><size=50%>Fabius</size><voffset=-1>" + ruby);
    assert(!g_inside_ruby_format);
    ruby_prepared = false;
    auto unknown = replacement_ruby_format<0>(&ruby_prepared,
            new_managed_string("<ruby=unknown>未知正文</ruby>"), nullptr);
    assert(!is_translated_managed_string(unknown));
    assert(!g_inside_ruby_format);
    g_original_ruby_format[1] = [](void *, void *, const void *) -> void * { throw 9; };
    try { replacement_ruby_format<1>(&ruby_prepared, annotated, nullptr); assert(false); }
    catch (int value) { assert(value == 9 && !g_inside_ruby_format); }

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
    g_terms = {{"ガード", "防御"}, {"or", "或"}};
    rebuild_term_trie();
    std::string translated;
    assert(translate_embedded_terms("<ガード>", &translated) && translated == "<防御>");
    assert(translate_embedded_terms("<color=red>ガード</color>", &translated));
    assert(translated == "<color=red>防御</color>");
    assert(!translate_embedded_terms("<noparse>ガード</noparse>", &translated));
    assert(!translate_embedded_terms("<color=red></color>", &translated));
    puts("Managed provenance, Ruby formatter boundary, context order, GC/address reuse, native destruction and bounds passed");
}
