#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusTextProvenance.h"
#include <atomic>
#include <cassert>
#include <codecvt>
#include <cstdio>
#include <locale>
#include <map>
#include <vector>
#include <memory>
#include <unordered_map>
struct TmpComponentState {
    limbus_text_provenance::LayoutOverride<int32_t> wrapping;
    limbus_text_provenance::LayoutOverride<int32_t> overflow;
    limbus_text_provenance::LayoutOverride<float> spacing;
};
struct UiComponentState { limbus_text_provenance::LayoutOverride<int32_t> horizontal, vertical; };
struct ComponentRect { float width = 300, height = 40; };
enum { Wrap=1, Overflow, Minimum, Maximum, Size, AutoSize, Spacing, Horizontal, Base };
struct Control {
    ComponentRect rect;
    std::map<int,float> values{{Size,30},{Minimum,5},{Maximum,300},{AutoSize,1},{Wrap,0},{Overflow,0},{Spacing,0},{Horizontal,1},{Base,30}};
    std::vector<int> writes;
    TmpComponentState tmp;
    UiComponentState ui;
    bool announcer=false,choice=false,ruby=false,fitted=false;
    bool clickable=false,reflection_error=false;
    Control *parent=nullptr;
    std::string name;
};
std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> codec;
int32_t length(void *s) { return static_cast<std::u16string *>(s)->size(); }
const uint16_t *chars(void *s) { return reinterpret_cast<const uint16_t *>(static_cast<std::u16string *>(s)->data()); }
auto g_string_length=length; auto g_string_chars=chars;
bool utf16_to_utf8(const uint16_t *s,int32_t n,std::string *out) { *out=codec.to_bytes(reinterpret_cast<const char16_t *>(s),reinterpret_cast<const char16_t *>(s)+n);return true; }
int g_tmp_get_rect_transform_info=0,g_tmp_set_word_wrapping_info=Wrap,g_tmp_get_word_wrapping_info=Wrap;
int g_tmp_get_wrapping_mode_info=Wrap,g_tmp_set_wrapping_mode_info=Wrap;
int g_tmp_set_overflow_mode_info=Overflow,g_tmp_get_overflow_mode_info=Overflow;
int g_tmp_get_auto_sizing_info=AutoSize,base_field;
const void *g_tmp_base_size_field=&base_field;
void field_get(void *i,const void *field,void *out) {assert(field==&base_field);*static_cast<float *>(out)=static_cast<Control *>(i)->values.at(Base);}
auto g_field_get_value=field_get;
int g_tmp_get_font_size_info=Size,g_tmp_get_line_spacing_info=Spacing,g_tmp_set_line_spacing_info=Spacing;
int g_ui_get_rect_transform_info=0,g_ui_get_font_size_info=Size;
int g_ui_get_horizontal_overflow_info=Horizontal,g_ui_set_horizontal_overflow_info=Horizontal;
int g_ui_get_vertical_overflow_info=Overflow;
bool invoke_float_getter(int key,void *i,float *out) {*out=static_cast<Control *>(i)->values.at(key);return true;}
bool invoke_int_getter(int key,void *i,int32_t *out) {*out=static_cast<Control *>(i)->values.at(key);return true;}
bool invoke_bool_getter(int key,void *i,bool *out) {*out=static_cast<Control *>(i)->values.at(key)!=0;return true;}
void invoke_float_setter(int key,void *i,float v) {auto &c=*static_cast<Control *>(i);c.values[key]=v;c.writes.push_back(key);}
void invoke_int_setter(int key,void *i,int32_t v) {invoke_float_setter(key,i,v);}
void invoke_bool_setter(int key,void *i,bool v) {invoke_float_setter(key,i,v);}
#include "runtime-wrapping-under-test.inc"
bool is_announcer_description(void *i) {return static_cast<Control *>(i)->announcer;}
bool is_choice_text(void *i) {return static_cast<Control *>(i)->choice;}
bool is_ruby_text(void *i) {return static_cast<Control *>(i)->ruby;}
bool has_content_fitted_height(void *i) {return static_cast<Control *>(i)->fitted;}
int g_component_get_transform_info=1,g_transform_get_parent_info=2,g_layout_get_component_info=3,click_type;
const void *g_click_handler_runtime_type=&click_type;
void *invoke_object_getter(int key,void *i) {return key==1?i:static_cast<Control *>(i)->parent;}
void *invoke_component(int key,void *i,void **args,void **error) {
    assert(key==3 && args[0]==g_click_handler_runtime_type);
    auto c=static_cast<Control *>(i);
    if(c->reflection_error){*error=c;return nullptr;}
    return c->clickable?i:nullptr;
}
auto g_runtime_invoke=invoke_component;
#include "runtime-interactive-owner-under-test.inc"
ComponentRect read_component_rect(void *i,int) {return static_cast<Control *>(i)->rect;}
std::string read_unity_object_name(void *i) {return static_cast<Control *>(i)->name;}
size_t count_visible_codepoints(const std::string &s) {return limbus_rich_text::visible_codepoints(s);}
void save_tmp_layout_state(void *i,const TmpComponentState &s) {static_cast<Control *>(i)->tmp=s;}
void save_ui_layout_state(void *i,const UiComponentState &s) {static_cast<Control *>(i)->ui=s;}
std::atomic<uint64_t> g_layout_decisions{0};
#define LT_LOGI(...) ((void)0)
#include "runtime-layout-under-test.inc"
#include "runtime-ui-layout-under-test.inc"
std::unordered_map<std::string,std::string> g_package_line_breaks;
int g_font_state_lock;
void pthread_mutex_lock(int *) {}
void pthread_mutex_unlock(int *) {}
bool remember_translated_text(void *,const std::string &) {return true;}
std::vector<std::unique_ptr<std::u16string>> title_strings;
void *new_managed_string(const std::string &text) {
    title_strings.emplace_back(new std::u16string(codec.from_bytes(text)));return title_strings.back().get();
}
#include "runtime-title-layout-under-test.inc"
void apply(Control &c,const std::string &text) {
    auto s=codec.from_bytes(text);auto before=c.values;
    apply_translated_tmp_style(&c,&s,c.tmp);
    for(int key:{Size,Minimum,Maximum,AutoSize}) assert(c.values.at(key)==before.at(key));
    for(int key:c.writes) assert(key!=Size&&key!=Minimum&&key!=Maximum&&key!=AutoSize);
}
void legacy(Control &c,const std::string &text) {
    auto s=codec.from_bytes(text);auto before=c.values;
    apply_translated_ui_style(&c,&s,c.ui);
    for(int key:{Size,Minimum,Maximum,AutoSize,Spacing,Overflow}) assert(c.values.at(key)==before.at(key));
}
int main() {
    // Actual serialized mirror pack-search configuration: no-wrap, auto 20..45,
    // base 40; HorizontalLayoutGroup stretches its text slot to about 133 x 97.
    Control pack_button,pack_row,pack_text;
    pack_button.clickable=true;pack_row.parent=&pack_button;pack_text.parent=&pack_row;
    pack_text.rect={132.761f,97};pack_text.values[Base]=40;pack_text.values[Size]=34.95f;
    pack_text.values[Minimum]=20;pack_text.values[Maximum]=45;
    apply(pack_text,"主题卡包观测");assert(pack_text.values[Wrap]==0);
    legacy(pack_text,"主题卡包观测");assert(pack_text.values[Horizontal]==1);
    // Resizing, repeated parsing and a fitted small size must not open wrapping.
    pack_text.values[Size]=20;pack_text.rect.height=110;
    apply(pack_text,"主题卡包观测");assert(pack_text.values[Wrap]==0);
    for(const auto *text:{"E.G.O","胜率","伤害","刷新","人格详情","通行证任务"}) {
        Control c;c.parent=&pack_button;c.rect.height=97;apply(c,text);assert(c.values[Wrap]==0);
    }
    // A later explicit game layout change, body/fitter, paging, or line break wins.
    pack_text.values[Wrap]=1;apply(pack_text,"游戏改成多行");assert(pack_text.values[Wrap]==1);
    Control fitted_label;fitted_label.parent=&pack_button;fitted_label.fitted=true;fitted_label.rect.height=100;
    apply(fitted_label,"饰品说明");assert(fitted_label.values[Wrap]==1);
    Control broken_label;broken_label.parent=&pack_button;broken_label.rect.height=100;
    apply(broken_label,"第一段\n第二段");assert(broken_label.values[Wrap]==1);
    for(int mode:{4,5,6}) {Control c;c.parent=&pack_button;c.rect.height=100;c.values[Overflow]=mode;
        apply(c,"分段正文");assert(c.values[Wrap]==0&&c.values[Overflow]==mode);}
    Control whitespace;whitespace.values[Wrap]=2;apply(whitespace,"确认");assert(whitespace.values[Wrap]==3);
    assert(whitespace.tmp.wrapping.restore(3)&&whitespace.tmp.wrapping.original==2);
    whitespace.rect.height=180;apply(whitespace,"包含空白的正文。");assert(whitespace.values[Wrap]==2);
    Control whitespace_button;whitespace_button.parent=&pack_button;whitespace_button.rect.height=97;
    whitespace_button.values[Wrap]=3;apply(whitespace_button,"主题卡包观测");assert(whitespace_button.values[Wrap]==3);
    g_tmp_get_wrapping_mode_info=0;g_tmp_set_wrapping_mode_info=0;
    Control classic;classic.rect.height=97;classic.parent=&pack_button;apply(classic,"旧版按钮");assert(classic.values[Wrap]==0);
    g_tmp_get_wrapping_mode_info=Wrap;g_tmp_set_wrapping_mode_info=Wrap;
    Control outside,a,b,c;outside.clickable=true;a.parent=&outside;b.parent=&a;c.parent=&b;
    Control child;child.parent=&c;assert(!has_interactive_text_owner(&child));
    assert(has_interactive_text_owner(&c));c.reflection_error=true;assert(!has_interactive_text_owner(&c));
    // An ordinary short paragraph with identical dimensions remains multi-line.
    Control paragraph;paragraph.rect={132.761f,97};apply(paragraph,"这里是对话正文。");assert(paragraph.values[Wrap]==1);
    // fontSizeMax can be 10x the actual size: a translated label must not grow.
    for(float size:{8.f,18.f,30.f,46.f}) for(bool automatic:{false,true}) {
        Control c;c.values[Size]=size;c.values[Base]=size;c.values[AutoSize]=automatic;c.rect.height=size*1.3f;
        apply(c,"E.G.O");apply(c,"胜率");assert(c.values[Wrap]==0);
        legacy(c,"伤害");assert(c.values[Horizontal]==1);
    }
    for(const auto *label:{"胜率","伤害","取消","继续当前战斗"}) {
        Control c;c.rect={52,40};c.values[Wrap]=1;apply(c,label);assert(c.values[Wrap]==0);
        // Reuse restores only a value still owned by us.
        assert(c.tmp.wrapping.restore(false)&&c.tmp.wrapping.original);
        assert(!c.tmp.wrapping.restore(true));
    }
    for(int mode:{0,1,2,3}) {
        Control c;c.rect={52,40};c.values[Wrap]=1;c.values[Overflow]=mode;
        apply(c,"胜率");assert(c.values[Wrap]==0&&c.values[Overflow]==mode);
    }
    Control fitted_button;fitted_button.values[Size]=9;fitted_button.values[Wrap]=1;
    apply(fitted_button,"胜率");assert(fitted_button.values[Wrap]==0&&fitted_button.values[Size]==9);
    Control body;body.rect.height=180;body.values[Overflow]=1;
    apply(body,"这段正文应该换行显示，字号范围应当交给游戏自己控制。");
    assert(body.values[Wrap]==1&&body.values[Overflow]==0);
    // A later game write is the new baseline; never replay the first snapshot.
    body.values[Size]=16;body.values[Minimum]=4;body.values[Maximum]=70;
    body.values[Overflow]=2;apply(body,"切换页签后新的正文。");
    assert(body.values[Overflow]==2&&!body.tmp.overflow.active);
    body.rect.height=20;apply(body,"确认"); // Masking remains owned by the game.
    assert(body.values[Overflow]==2);
    Control fitted;fitted.fitted=true;apply(fitted,"饰品内容决定高度。");assert(fitted.values[Wrap]==1);
    Control lines;apply(lines,"第一行<br>第二行");assert(lines.values[Wrap]==1);
    Control title;title.name="[Text]GroupName";title.values[Spacing]=4;
    apply(title,"第一行\n第二行");assert(title.values[Spacing]==0);
    for(const auto *name:{"黎明事务所\n收尾人","高级定制::\n黑派\n品牌经理","<line-height=110%>保留\n原排版", "<LINE-HEIGHT=110%>保留\n原排版"}) {
        auto value=codec.from_bytes(name);
        auto *output=static_cast<std::u16string *>(apply_tmp_component_layout_markup(&title,&value));
        assert(*output==value);assert(title.values[Size]==30);
    }
    g_package_line_breaks["黎明事务所 收尾人"]="黎明事务所\n收尾人";
    auto collapsed=codec.from_bytes("黎明事务所 收尾人");
    auto *restored=static_cast<std::u16string *>(apply_tmp_component_layout_markup(&title,&collapsed));
    assert(codec.to_bytes(*restored)=="黎明事务所\n收尾人");
    title.name="ordinary";apply(title,"中文正文");assert(title.values[Spacing]==4);
    assert(apply_tmp_component_layout_markup(&title,&collapsed)==&collapsed);
    for(int mode:{0,1,2,3,4,5,6}) {
        Control c;c.rect.height=180;c.values[Overflow]=mode;apply(c,"正文排版。");
        assert(c.values[Overflow]==((mode==1||mode==3)?0:mode));
    }
    for(int type=0;type<3;++type) {
        Control c;c.announcer=type==0;c.choice=type==1;c.ruby=type==2;
        apply(c,"<ruby=Fabius>费边</ruby>");assert(c.writes.empty());
    }
    Control old;old.values[Size]=8;old.rect.height=180;legacy(old,"旧式正文也保持游戏设置的字号。");
    assert(old.values[Horizontal]==0&&old.values[Size]==8&&old.values[Maximum]==300);
    puts("Production layout: sizes/best-fit untouched, horizontal controls, body wrapping, live game writes and scoped restoration passed");
}
