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
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusManagedState.h"
struct TmpComponentState {
    limbus_text_provenance::LayoutOverride<int32_t> wrapping;
    limbus_text_provenance::LayoutOverride<int32_t> overflow;
    limbus_text_provenance::LayoutOverride<float> spacing;
    limbus_text_provenance::LayoutOverride<float> label_size;
    limbus_text_provenance::LayoutOverride<float> right_margin;
};
struct UiComponentState { limbus_text_provenance::LayoutOverride<int32_t> horizontal, vertical; };
struct ComponentRect { float width = 300, height = 40; };
enum { Wrap=1, Overflow, Minimum, Maximum, Size, AutoSize, Spacing, Horizontal, Base, FitterEnabled, FitX, FitY };
struct Control {
    ComponentRect rect;
    std::map<int,float> values{{Size,30},{Minimum,5},{Maximum,300},{AutoSize,1},{Wrap,0},{Overflow,0},{Spacing,0},{Horizontal,1},{Base,30}};
    std::vector<int> writes;
    TmpComponentState tmp;
    UiComponentState ui;
    bool announcer=false,choice=false,ruby=false,fitted=false;
    bool clickable=false,reflection_error=false;
    bool fitter=false,measure_error=false,reenter=false,measuring=false;
    Control *skill_owner=nullptr,*material_owner=nullptr,*exp_owner=nullptr,*label=nullptr,*counter=nullptr;
    float bounds[6]={300,0,0,50,20,0},local_rect[4]={0,-20,400,40};
    float margins[4]={0,0,0,0},preferred[2]={0,0},glyph_em=2;
    int measurements=0;
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
int measuring_field,label_field,counter_field,skill_type,material_type,exp_type;
void field_get(void *i,const void *field,void *out) {
    auto c=static_cast<Control *>(i);
    if(field==&measuring_field){*static_cast<bool *>(out)=c->measuring;return;}
    if(field==&label_field){*static_cast<void **>(out)=c->label;return;}
    if(field==&counter_field){*static_cast<void **>(out)=c->counter;return;}
    assert(field==&base_field);*static_cast<float *>(out)=c->values.at(Base);
}
auto g_field_get_value=field_get;
int g_tmp_get_font_size_info=Size,g_tmp_get_line_spacing_info=Spacing,g_tmp_set_line_spacing_info=Spacing;
int g_fixed_label_size_info=Size,g_fixed_label_preferred_info=101,g_fixed_label_margin_info=102;
int g_content_fitter_horizontal_info=FitX,g_content_fitter_vertical_info=FitY;
int g_rect_transform_get_rect_info=106;
int g_component_get_component_info=103,g_behaviour_get_active_info=FitterEnabled,fitter_type;
const void *g_content_fitter_runtime_type=&fitter_type;
int g_ui_get_rect_transform_info=0,g_ui_get_font_size_info=Size;
int g_ui_get_horizontal_overflow_info=Horizontal,g_ui_set_horizontal_overflow_info=Horizontal;
int g_ui_get_vertical_overflow_info=Overflow;
bool invoke_float_getter(int key,void *i,float *out) {*out=static_cast<Control *>(i)->values.at(key);return true;}
bool invoke_int_getter(int key,void *i,int32_t *out) {*out=static_cast<Control *>(i)->values.at(key);return true;}
bool invoke_bool_getter(int key,void *i,bool *out) {auto &v=static_cast<Control *>(i)->values;*out=key==FitterEnabled&&!v.count(key)?true:v.at(key)!=0;return true;}
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
void *invoke_object_getter(int key,void *i) {return key==0||key==1?i:static_cast<Control *>(i)->parent;}
void apply_translated_tmp_style(void *,void *,const TmpComponentState &);
void *invoke_component(int key,void *i,void **args,void **error) {
    if(key==107)return static_cast<Control *>(args[1])->bounds;
    auto c=static_cast<Control *>(i);
    if(c->reflection_error){*error=c;return nullptr;}
    if(key==104){assert(args[1]&&*static_cast<bool *>(args[1]));return args[0]==&skill_type?c->skill_owner:args[0]==&exp_type?c->exp_owner:c->material_owner;}
    if(key==105){std::copy(static_cast<float *>(args[0]),static_cast<float *>(args[0])+4,c->margins);return nullptr;}
    if(key==106)return c->local_rect;
    if(key==103){assert(args[0]==g_content_fitter_runtime_type);return c->fitter?i:nullptr;}
    if(key==102)return c->margins;
    if(key==101){
        ++c->measurements;
        if(c->measure_error){*error=c;return nullptr;}
        if(c->reenter)apply_translated_tmp_style(i,args[0],c->tmp);
        c->preferred[0]=c->glyph_em*c->values[Size]+c->margins[0]+c->margins[2]+0.01f;
        return c->preferred;
    }
    assert(key==3 && args[0]==g_click_handler_runtime_type);
    return c->clickable?i:nullptr;
}
void *invoke_component(const void *key,void *i,void **args,void **error) {return invoke_component(static_cast<int>(reinterpret_cast<uintptr_t>(key)),i,args,error);}
struct RuntimeInvoke {explicit operator bool() const {return true;} void *operator()(int k,void *i,void **a,void **e){return invoke_component(k,i,a,e);} void *operator()(const void *k,void *i,void **a,void **e){return invoke_component(k,i,a,e);}} g_runtime_invoke;
void *unbox(void *p){return p;}
auto g_object_unbox=unbox;
#include "runtime-interactive-owner-under-test.inc"
ComponentRect read_component_rect(void *i,int) {return static_cast<Control *>(i)->rect;}
std::string read_unity_object_name(void *i) {return static_cast<Control *>(i)->name;}
size_t count_visible_codepoints(const std::string &s) {return limbus_rich_text::visible_codepoints(s);}
void save_tmp_layout_state(void *i,const TmpComponentState &s) {static_cast<Control *>(i)->tmp=s;}
void save_ui_layout_state(void *i,const UiComponentState &s) {static_cast<Control *>(i)->ui=s;}
std::atomic<uint64_t> g_layout_decisions{0};
#define LT_LOGI(...) ((void)0)
#include "runtime-label-restore-under-test.inc"
void *retain(void *p,bool){return p;} void *ref_target(void *p){return p;} void ref_release(void *){}
limbus_managed::GcApi g_gc{retain,retain,ref_target,ref_release};
#include "runtime-text-row-under-test.inc"
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
    auto s=codec.from_bytes(text);auto before=c.values;const auto first_write=c.writes.size();
    apply_translated_tmp_style(&c,&s,c.tmp);
    for(int key:{Size,Minimum,Maximum,AutoSize}) assert(c.values.at(key)==before.at(key));
    for(size_t i=first_write;i<c.writes.size();++i){int key=c.writes[i];assert(key!=Size&&key!=Minimum&&key!=Maximum&&key!=AutoSize);}
}
void legacy(Control &c,const std::string &text) {
    auto s=codec.from_bytes(text);auto before=c.values;
    apply_translated_ui_style(&c,&s,c.ui);
    for(int key:{Size,Minimum,Maximum,AutoSize,Spacing,Overflow}) assert(c.values.at(key)==before.at(key));
}
int main() {
    g_text_owner_info=reinterpret_cast<void *>(104);
    g_text_margin_set_info=reinterpret_cast<void *>(105);
    g_text_relative_bounds_info=reinterpret_cast<void *>(107);
    g_tmp_measuring_field=&measuring_field;
    g_skill_label_owner={&skill_type,&label_field,nullptr};
    g_material_label_owner={&material_type,&label_field,&counter_field};
    g_exp_gain_owner={&exp_type,&label_field,nullptr};
    Control skill_owner,skill;
    skill_owner.label=&skill;skill.skill_owner=&skill_owner;
    skill.values[Size]=36;skill.values[AutoSize]=0;skill.values[Wrap]=1;skill.values[Overflow]=3;
    for(float width:{0.f,72.f,185.91f,270.f}) {
        skill.rect={width,55};apply(skill,"切割攻击");
        assert(skill.values[Wrap]==1&&skill.values[Overflow]==3&&skill.measurements==0);
    }
    // Another child of the skill UI is not its dynamic name field.
    skill_owner.label=nullptr;skill.rect={70,40};
    auto skill_text=codec.from_bytes("切割攻击");
    apply_translated_tmp_style(&skill,&skill_text,skill.tmp);assert(skill.measurements==0);
    skill_owner.label=&skill;apply_translated_tmp_style(&skill,&skill_text,skill.tmp);
    assert(skill.values[Size]==36 && !skill.tmp.label_size.active);
    Control measurement;measurement.rect={66,40};measurement.values[Size]=50;
    measurement.values[AutoSize]=0;measurement.measuring=true;apply(measurement,"经验");
    assert(measurement.measurements==0 && measurement.writes.empty());
    Control row,name,count;row.label=&name;row.counter=&count;name.material_owner=&row;
    name.local_rect[2]=400;name.rect={400,40};name.values[AutoSize]=0;name.values[Size]=30;name.glyph_em=10;
    count.bounds[0]=350;count.bounds[3]=50;
    auto material=codec.from_bytes("第八赛季同步与异想解析专用材料");
    apply_translated_tmp_style(&name,&material,name.tmp);
    assert(name.margins[2]==108 && name.values[Size]>29.1f && name.values[Size]<29.3f);
    assert(name.values[Wrap]==0 && count.writes.empty() && name.tmp.right_margin.original==0);
    auto margin=name.margins[2];apply_translated_tmp_style(&name,&material,name.tmp);
    assert(name.margins[2]==margin); // no cumulative margin or shrink
    count.bounds[0]=650;apply_translated_tmp_style(&name,&material,name.tmp);
    assert(name.margins[2]==0 && name.values[Size]==30); // resized row no longer overlaps
    count.bounds[0]=350;apply_translated_tmp_style(&name,&material,name.tmp);
    name.margins[2]=20;apply_translated_tmp_style(&name,&material,name.tmp);
    assert(name.tmp.right_margin.original==20); // later game margin wins
    name.material_owner=nullptr;apply_translated_tmp_style(&name,&material,name.tmp);
    assert(name.margins[2]==20 && !name.tmp.right_margin.active);
    row.label=nullptr;name.material_owner=&row;apply(name,"另一条文本");
    assert(name.margins[2]==20); // field identity required
    row.label=&name;name.values[AutoSize]=1;name.values[Size]=18;
    apply(name,"第八赛季同步与异想解析专用材料");
    assert(name.margins[2]==108 && name.values[Size]==18 && name.values[Minimum]==5 && name.values[Maximum]==300);
    count.values[FitterEnabled]=0;apply(name,"第八赛季同步与异想解析专用材料");
    assert(name.margins[2]==20); // hidden count no longer reserves space
    count.values[FitterEnabled]=1;name.measuring=true;apply(name,"第八赛季同步与异想解析专用材料");
    assert(name.margins[2]==20); // measurement does not change bounds
    name.measuring=false;apply(name,"第八赛季同步与异想解析专用材料");
    assert(name.margins[2]==108);
    restore_text_row_margin(&name,name.tmp);assert(name.margins[2]==20);
    name.margins[2]=25;restore_text_row_margin(&name,name.tmp);assert(name.margins[2]==25);
    Control unknown;row.label=&unknown;unknown.material_owner=&row;unknown.reflection_error=true;
    apply(unknown,"不匹配的材料");assert(unknown.margins[2]==0);
    // Real result prefabs (story / battle / alternate mode): EXP has a fixed
    // 66x40 rectangle, 50pt text, no auto-size or horizontal fitter. Its +value
    // is a separate child. Two full-width CJK glyphs at 50pt cannot fit.
    Control exp;exp.rect={66,40};exp.values[Size]=50;exp.values[AutoSize]=0;exp.reenter=true;
    Control gauge,exp_row,gain;gauge.label=&gain;gain.parent=&exp_row;
    exp.exp_owner=&gauge;exp.parent=&exp_row;exp.name="[Tmpro]ExpTitle";
    auto translated=codec.from_bytes("经验");
    apply_translated_tmp_style(&exp,&translated,exp.tmp);
    assert(exp.values[Size]>32.9f && exp.values[Size]<=33.f && exp.values[Wrap]==0);
    assert(exp.tmp.label_size.active && exp.tmp.label_size.original==50 && exp.measurements==1);
    const auto fitted_size=exp.values[Size];
    apply_translated_tmp_style(&exp,&translated,exp.tmp);
    assert(exp.values[Size]==fitted_size); // no cumulative shrink / recursive fit
    assert(exp.values[Minimum]==5 && exp.values[Maximum]==300 && exp.values[AutoSize]==0);
    restore_fixed_label_size(&exp,exp.tmp);assert(exp.values[Size]==50);
    apply_translated_tmp_style(&exp,&translated,exp.tmp);
    exp.values[Size]=44;restore_fixed_label_size(&exp,exp.tmp);assert(exp.values[Size]==44);
    exp.values[Size]=fitted_size;exp.values[AutoSize]=1;
    restore_fixed_label_size(&exp,exp.tmp);assert(exp.values[Size]==fitted_size);
    exp.values[AutoSize]=0;
    exp.rect.width=120;apply_translated_tmp_style(&exp,&translated,exp.tmp);
    assert(exp.values[Size]==50 && !exp.tmp.label_size.active); // resized container
    exp.rect.width=66;exp.margins[0]=4;exp.margins[2]=2;
    apply_translated_tmp_style(&exp,&translated,exp.tmp);
    assert(exp.values[Size]>29.9f && exp.values[Size]<=30);
    exp.values[Size]=40;exp.margins[0]=exp.margins[2]=0;
    apply_translated_tmp_style(&exp,&translated,exp.tmp);
    assert(exp.tmp.label_size.original==40); // newer game write is authoritative
    exp.rect.height=160;apply_translated_tmp_style(&exp,&translated,exp.tmp);
    assert(exp.values[Size]==40 && !exp.tmp.label_size.active); // reuse as paragraph
    exp.rect.height=40;apply_translated_tmp_style(&exp,&translated,exp.tmp);
    exp.choice=true;apply_translated_tmp_style(&exp,&translated,exp.tmp);
    assert(exp.values[Size]==40 && !exp.tmp.label_size.active); // reuse as managed text flow
    exp.choice=false;apply_translated_tmp_style(&exp,&translated,exp.tmp);
    exp.exp_owner=nullptr;apply_translated_tmp_style(&exp,&translated,exp.tmp);
    assert(exp.values[Size]==40 && !exp.tmp.label_size.active); // reused outside the owning gauge
    exp.exp_owner=&gauge;gain.parent=nullptr;apply_translated_tmp_style(&exp,&translated,exp.tmp);
    assert(exp.values[Size]==40 && exp.margins[2]==0); // same owner, different row: not a caption
    gain.parent=&exp_row;exp.name="unrelated label";apply_translated_tmp_style(&exp,&translated,exp.tmp);
    assert(exp.values[Size]==40); // same dimensions and parent are insufficient
    // Official UnitInformationPersonalitySkillTypeButton uses 36pt fixed text
    // in a 70.29 x 50 slot and intentionally overflows into a much wider tab.
    // No text-length heuristic may shrink skills 1/2/3 relative to defence.
    for(const char *label:{"技能1","技能2","技能3","守备","E.G.O","通行证任务"}) {
        Control tab;tab.rect={70.29f,50};tab.values[Size]=36;tab.values[AutoSize]=0;
        tab.glyph_em=3;apply(tab,label);
        assert(tab.values[Size]==36 && tab.measurements==0 && tab.values[Overflow]==0);
    }
    // Remove an earlier BYBUS size override once the role no longer matches;
    // a later game-written size still takes precedence.
    Control reused;reused.rect={70.29f,50};reused.values[Size]=24;reused.values[AutoSize]=0;
    reused.tmp.label_size.observe(36);reused.tmp.label_size.record(24);
    auto tab_text=codec.from_bytes("技能1");apply_translated_tmp_style(&reused,&tab_text,reused.tmp);
    assert(reused.values[Size]==36 && !reused.tmp.label_size.active);
    reused.values[Size]=32;apply(reused,"技能2");assert(reused.values[Size]==32);
    for(const char *value:{"+20","50/356","EXP","<size=50>经验</size>","第一行\n第二行"}) {
        Control excluded;excluded.rect={66,40};excluded.values[Size]=50;excluded.values[AutoSize]=0;
        apply(excluded,value);assert(excluded.measurements==0);
    }
    for(int mode:{4,5,6}){Control c;c.rect={66,40};c.values[Size]=50;c.values[AutoSize]=0;c.values[Overflow]=mode;
        apply(c,"经验");assert(c.measurements==0);}
    for(int axis:{FitX,FitY}) {
        Control c;c.rect={66,40};c.values[Size]=50;c.values[AutoSize]=0;c.fitter=true;
        c.values[FitterEnabled]=1;c.values[FitX]=c.values[FitY]=0;c.values[axis]=2;
        apply(c,"经验");assert(c.measurements==0);
    }
    for(int failure=0;failure<3;++failure){Control c;c.rect={66,40};c.values[Size]=50;c.values[AutoSize]=0;
        c.reflection_error=failure==0;c.measure_error=failure==1;if(failure==2)c.glyph_em=10;
        apply(c,"经验");}
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
    puts("Production layout: fixed-label measured fit, exclusions, reentry, resize/reuse, game auto-size and body layout passed");
}
