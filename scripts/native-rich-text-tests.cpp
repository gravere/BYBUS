#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFontProof.h"
#include <cassert>
#include <cstdio>
#include <codecvt>
#include <locale>
#include <mutex>

std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> codec;
limbus_font_proof::Catalogue catalogue;
std::unordered_set<std::string> known_results;
std::mutex g_font_state_lock;
#define pthread_mutex_lock(p) (p)->lock()
#define pthread_mutex_unlock(p) (p)->unlock()
int32_t length(void *s) { return static_cast<std::u16string *>(s)->size(); }
const uint16_t *chars(void *s) { return reinterpret_cast<const uint16_t *>(static_cast<std::u16string *>(s)->data()); }
auto g_string_length = length;
auto g_string_chars = chars;
bool utf16_to_utf8(const uint16_t *s, int32_t n, std::string *out) {
    *out = codec.to_bytes(reinterpret_cast<const char16_t *>(s), reinterpret_cast<const char16_t *>(s) + n); return true;
}
bool is_translated_managed_string(void *s) {
    const auto text = codec.to_bytes(*static_cast<std::u16string *>(s));
    return known_results.count(text) || catalogue.contains(text);
}
bool remember_translated_text(void *, const std::string &s) { known_results.insert(s); return true; }
using One = void *(*)(void *, const void *);
using All = void *(*)(void *, bool, const void *);
using Color = void *(*)(void *, void *, const void *);
One g_original_rich_text_one[3] = {};
All g_original_rich_text_all = nullptr;
Color g_original_rich_text_color = nullptr;
Color g_original_rich_text_event = nullptr;
void *(*g_original_rich_text_skill)(void *, bool, bool, const void *) = nullptr;
#include "runtime-rich-text-under-test.inc"

std::u16string input, output;
int expected_method, color_argument;
void *one(void *s, const void *m) { assert(s == &input && m == &expected_method); return &output; }
void *all(void *s, bool gray, const void *m) { assert(gray); return one(s, m); }
void *color(void *s, void *c, const void *m) { assert(c == &color_argument); return one(s, m); }
void *skill(void *s, bool gray, bool highlight, const void *m) { assert(!gray && highlight); return one(s, m); }

int main() {
    catalogue.add("<style=\"highlight\">[命中时]</style> 使目标获得2层流血");
    input = u"<style=\"highlight\">[命中时]</style> 使目标获得2层流血";
    output = u"<style=\"highlightConverted\">[命中时]</style> 使目标获得2层<sprite name=\"Bleeding\"><color=#e30000><u><link=\"Bleeding\">流血</link></u></color>";
    assert(catalogue.contains(codec.to_bytes(input)));
    // This is the actual gap: arbitrary macro/sprite stripping must stay forbidden.
    assert(!catalogue.contains(codec.to_bytes(output)));
    g_original_rich_text_one[0] = g_original_rich_text_one[1] = g_original_rich_text_one[2] = one;
    g_original_rich_text_all = all; g_original_rich_text_color = color;
    g_original_rich_text_event = color;
    g_original_rich_text_skill = skill;
    for (int n = 0; n < 7; ++n) {
        known_results.clear();
        void *r = n == 0 ? replacement_rich_text_one<0>(&input, &expected_method) :
                  n == 1 ? replacement_rich_text_one<1>(&input, &expected_method) :
                  n == 2 ? replacement_rich_text_one<2>(&input, &expected_method) :
                  n == 3 ? replacement_rich_text_skill(&input, false, true, &expected_method) :
                  n == 4 ? replacement_rich_text_all(&input, true, &expected_method) :
                  n == 5 ? replacement_rich_text_color(&input, &color_argument, &expected_method) :
                           replacement_rich_text_event(&input, &color_argument, &expected_method);
        assert(r == &output && is_translated_managed_string(r));
    }
    auto saved_input = input, saved_output = output;
    input = output; output += u"<link=\"Buff\"></link>";
    auto nested = replacement_rich_text_one<1>(&input, &expected_method);
    assert(nested == &output && is_translated_managed_string(nested));
    known_results.clear();
    input = saved_input + u"未知玩家文本"; output = saved_output;
    assert(replacement_rich_text_one<1>(&input, &expected_method) == &output);
    assert(!is_translated_managed_string(&output));
    input = u"123";
    assert(replacement_rich_text_all(&input, true, &expected_method) == &output);
    assert(!is_translated_managed_string(&output));
    catalogue.add("流血");
    assert(catalogue.contains("<link=\"Bleeding\"><color=#e30000>流血</color></link>"));
    assert(!catalogue.contains("<sprite name=\"Bleeding\">流血"));
    assert(!catalogue.contains("<style=\"unknown\">流血</style>"));
    assert(!catalogue.contains("<font=\"unknown\">流血</font>"));
    assert(!catalogue.contains("<noparse><link=\"Bleeding\">流血</link></noparse>"));
    puts("Production rich-text wrappers: whole-source proof, macros/sprites, arguments, nested output, unrelated/numeric rejection passed");
}
