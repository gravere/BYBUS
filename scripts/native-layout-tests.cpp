#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusTextProvenance.h"
#include <atomic>
#include <cassert>
#include <codecvt>
#include <cstdio>
#include <locale>
#include <map>
#include <vector>
struct TmpComponentState {
    limbus_text_provenance::LayoutOverride<bool> wrapping;
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
    std::string name;
};
std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> codec;
int32_t length(void *s) { return static_cast<std::u16string *>(s)->size(); }
const uint16_t *chars(void *s) { return reinterpret_cast<const uint16_t *>(static_cast<std::u16string *>(s)->data()); }
auto g_string_length=length; auto g_string_chars=chars;
bool utf16_to_utf8(const uint16_t *s,int32_t n,std::string *out) { *out=codec.to_bytes(reinterpret_cast<const char16_t *>(s),reinterpret_cast<const char16_t *>(s)+n);return true; }
int g_tmp_get_rect_transform_info=0,g_tmp_set_word_wrapping_info=Wrap,g_tmp_get_word_wrapping_info=Wrap;
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
bool is_announcer_description(void *i) {return static_cast<Control *>(i)->announcer;}
bool is_choice_text(void *i) {return static_cast<Control *>(i)->choice;}
bool is_ruby_text(void *i) {return static_cast<Control *>(i)->ruby;}
bool has_content_fitted_height(void *i) {return static_cast<Control *>(i)->fitted;}
ComponentRect read_component_rect(void *i,int) {return static_cast<Control *>(i)->rect;}
std::string read_unity_object_name(void *i) {return static_cast<Control *>(i)->name;}
size_t count_visible_codepoints(const std::string &s) {return limbus_rich_text::visible_codepoints(s);}
void save_tmp_layout_state(void *i,const TmpComponentState &s) {static_cast<Control *>(i)->tmp=s;}
void save_ui_layout_state(void *i,const UiComponentState &s) {static_cast<Control *>(i)->ui=s;}
std::atomic<uint64_t> g_layout_decisions{0};
#define LT_LOGI(...) ((void)0)
#include "runtime-layout-under-test.inc"
#include "runtime-ui-layout-under-test.inc"
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
    title.name="ordinary";apply(title,"中文正文");assert(title.values[Spacing]==4);
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
