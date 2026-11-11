#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusTextProvenance.h"
#include <atomic>
#include <cassert>
#include <codecvt>
#include <cstdio>
#include <locale>
#include <map>

struct TmpComponentState {
    bool has_font_size = true, has_auto_sizing = true, has_word_wrapping = true;
    bool has_overflow_mode = true, has_font_size_min = true, has_font_size_max = true;
    bool has_line_spacing = true, auto_sizing = true, word_wrapping = false;
    float font_size = 30, font_size_min = 5, font_size_max = 30, line_spacing = 0;
    int overflow_mode = 0;
};
struct UiComponentState {
    bool has_font_size = true, has_resize_best_fit = true, has_resize_min_size = true;
    bool has_resize_max_size = true, has_horizontal_overflow = true, has_vertical_overflow = true;
    bool has_line_spacing = true, resize_best_fit = true;
    int font_size = 30, resize_min_size = 5, resize_max_size = 30;
    int horizontal_overflow = 1, vertical_overflow = 0;
    float line_spacing = 1;
};
struct ComponentRect { float width = 300, height = 40; };
struct Control {
    ComponentRect rect;
    std::map<int, float> settings;
    bool announcer = false, choice = false;
    bool ruby = false;
    std::string name, parent;
};
std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> codec;
int32_t length(void *s) { return static_cast<std::u16string *>(s)->size(); }
const uint16_t *chars(void *s) { return reinterpret_cast<const uint16_t *>(static_cast<std::u16string *>(s)->data()); }
auto g_string_length = length;
auto g_string_chars = chars;
bool utf16_to_utf8(const uint16_t *s, int32_t length, std::string *out) {
    *out = codec.to_bytes(reinterpret_cast<const char16_t *>(s), reinterpret_cast<const char16_t *>(s) + length);
    return true;
}
enum { Wrap=1, Overflow, Minimum, Maximum, Size, AutoSize, Spacing, Horizontal };
int g_tmp_get_rect_transform_info = 0, g_tmp_set_word_wrapping_info = Wrap;
int g_tmp_set_overflow_mode_info = Overflow, g_tmp_set_font_size_min_info = Minimum;
int g_tmp_set_font_size_max_info = Maximum, g_tmp_set_font_size_info = Size;
int g_tmp_set_auto_sizing_info = AutoSize, g_tmp_set_line_spacing_info = Spacing;
int g_ui_get_rect_transform_info = 0, g_ui_set_resize_best_fit_info = AutoSize;
int g_ui_set_resize_min_size_info = Minimum, g_ui_set_resize_max_size_info = Maximum;
int g_ui_set_font_size_info = Size, g_ui_set_horizontal_overflow_info = Horizontal;
int g_ui_set_vertical_overflow_info = Overflow, g_ui_set_line_spacing_info = Spacing;
void invoke_float_setter(int key, void *i, float value) { static_cast<Control *>(i)->settings[key] = value; }
void invoke_int_setter(int key, void *i, int value) { invoke_float_setter(key, i, value); }
void invoke_bool_setter(int key, void *i, bool value) { invoke_float_setter(key, i, value); }
bool is_announcer_description(void *i) { return static_cast<Control *>(i)->announcer; }
bool is_choice_text(void *i) { return static_cast<Control *>(i)->choice; }
bool is_ruby_text(void *i) { return static_cast<Control *>(i)->ruby; }
ComponentRect read_component_rect(void *i, int) { return static_cast<Control *>(i)->rect; }
std::string read_unity_object_name(void *i) { return static_cast<Control *>(i)->name; }
std::string read_unity_parent_name(void *i) { return static_cast<Control *>(i)->parent; }
size_t count_visible_codepoints(const std::string &s) { return limbus_rich_text::visible_codepoints(s); }
std::atomic<uint64_t> g_layout_decisions{0};
#define LT_LOGI(...) ((void)0)
#include "runtime-layout-under-test.inc"
#include "runtime-ui-layout-under-test.inc"

void apply(Control &control, const std::string &label, const TmpComponentState &state = {}) {
    auto managed = codec.from_bytes(label);
    apply_translated_tmp_style(&control, &managed, state);
}
int main() {
    Control short_button, longer_button;
    apply(short_button, "取消");
    apply(longer_button, "继续当前战斗");
    assert(short_button.settings[Size] == longer_button.settings[Size]);
    assert(short_button.settings[Maximum] == 30 && short_button.settings[Minimum] == 24);
    assert(short_button.settings[Wrap] == 0 && short_button.settings[Overflow] == 0);

    TmpComponentState shrunk;
    shrunk.font_size = 9;
    Control reused;
    apply(reused, "战斗设置", shrunk);
    assert(reused.settings[Size] == 30 && reused.settings[Minimum] == 24);

    Control paragraph;
    paragraph.rect.height = 180;
    TmpComponentState ellipsis;
    ellipsis.overflow_mode = 1;
    apply(paragraph, "这段正文应该换行显示，而不是缩成一条小字。", ellipsis);
    assert(paragraph.settings[Wrap] == 1 && paragraph.settings[Overflow] == 0);
    assert(paragraph.settings[Size] == 30 && paragraph.settings[Minimum] == 24);

    Control dante;
    dante.rect.height = 180;
    apply(dante, "<我想问你一些事。>");
    assert(dante.settings[Wrap] == 1 && dante.settings[Size] == 30);
    Control explicit_lines;
    apply(explicit_lines, "第一行<br>第二行");
    assert(explicit_lines.settings[Wrap] == 1);
    for (int mode : {2, 3, 4, 5, 6}) {
        Control specialized;
        TmpComponentState original;
        original.overflow_mode = mode;
        apply(specialized, "分页或滚动正文", original);
        assert(specialized.settings.empty());
    }
    Control announcer, choice, ruby;
    announcer.announcer = true; choice.choice = true; ruby.ruby = true;
    apply(announcer, "播报正文"); apply(choice, "事件正文"); apply(ruby, "<ruby=Fabius>费边</ruby>");
    assert(announcer.settings.empty() && choice.settings.empty() && ruby.settings.empty());
    UiComponentState legacy;
    legacy.font_size = 8;
    auto label = codec.from_bytes("战斗设置");
    Control legacy_button;
    apply_translated_ui_style(&legacy_button, &label, legacy);
    assert(legacy_button.settings[Size] == 30 && legacy_button.settings[Minimum] == 24);
    Control legacy_body;
    legacy_body.rect.height = 180;
    apply_translated_ui_style(&legacy_body, &label, legacy);
    assert(legacy_body.settings[Size] == 30 && legacy_body.settings[Horizontal] == 0);
    assert(legacy_body.settings[Minimum] == 24 && legacy_body.settings[Overflow] == 0);
    puts("Runtime layout: button size, readable body wrapping, no added ellipsis, dedicated flows passed");
}
