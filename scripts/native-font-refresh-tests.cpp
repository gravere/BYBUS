#include <cassert>
#include <cstdio>
#include <cstdint>
#include <initializer_list>
struct Text { bool translated=false,stream=false,fragment=false; };
struct Component { void *font=nullptr; Text *text=nullptr; };
int chinese,original_font,text_getter,model_class,other_class;
void *g_tmp_font_asset=&chinese;const void *g_tmp_get_text_info=&text_getter;
bool g_font_ready=true,g_live_resources=true;
int font_prepares=0;
void set_font(void *i,void *f,const void *) {static_cast<Component *>(i)->font=f;}
auto g_tmp_set_font=set_font;
void *invoke_object_getter(const void *,void *i) {return static_cast<Component *>(i)->text;}
bool is_translated_managed_string(void *s) {return static_cast<Text *>(s)->translated;}
bool is_choice_text(void *,void *s) {return static_cast<Text *>(s)->stream;}
bool is_translated_tmp_fragment(void *,void *s) {return static_cast<Text *>(s)->fragment;}
bool is_language_font_target(void *) {return false;}
void *prepare_tmp_text(void *i,void *s,bool restore) {assert(!restore);++font_prepares;static_cast<Component *>(i)->font=g_tmp_font_asset;return s;}
#include "runtime-font-refresh-under-test.inc"
using acquisition0_fn=void *(*)(void *,const void *);
void append_native_metadata(const char *,const char *) {}
using Load=void(*)(void *,int32_t,const void *);
Load g_original_localization_load[2];
bool ready_before_load=false; int loads=0,models=0;
int completed_loads=0;
void prepare_loaded_localization(void *) { assert(loads==completed_loads+1); ++completed_loads; }
bool ensure_tmp_font() {ready_before_load=true;return true;}
void load(void *,int32_t lang,const void *) {assert(ready_before_load&&lang==2);++loads;}
struct Model {void *klass;}; Model valid{&model_class},wrong{&other_class};
void *get_class(void *p) {return static_cast<Model *>(p)->klass;}
auto g_object_get_class=get_class;void *g_ego_gift_text_class=&model_class;
void translate_object_graph(void *p,void *) {assert(p==&valid);++models;}
#include "runtime-loading-boundaries-under-test.inc"
void *getter(void *p,const void *) {return p;}
int main() {
    Text translated{true},partial{false,true},fragment{false,false,true},unknown;
    Component c;
    for(auto *t:{&translated,&partial,&fragment}) {
        c.text=t;replacement_tmp_set_font(&c,&original_font,nullptr);assert(c.font==&chinese);
    }
    c.text=&unknown;replacement_tmp_set_font(&c,&original_font,nullptr);assert(c.font==&original_font);
    c.text=&translated;g_font_ready=false;replacement_tmp_set_font(&c,&original_font,nullptr);assert(c.font==&original_font);
    g_font_ready=true;replacement_tmp_set_font(&c,&chinese,nullptr);assert(font_prepares==3);
    g_original_localization_load[0]=g_original_localization_load[1]=load;
    replacement_localization_load<0>(nullptr,2,nullptr);ready_before_load=false;
    replacement_localization_load<1>(nullptr,2,nullptr);assert(loads==2&&completed_loads==2);
    g_original_gift_text_data=getter;
    assert(replacement_gift_text_data(&valid,nullptr)==&valid);
    assert(replacement_gift_text_data(&wrong,nullptr)==&wrong);
    assert(replacement_gift_text_data(nullptr,nullptr)==nullptr&&models==1);
    puts("Production font reassignment and localization/model boundaries: identity, ordering and unknown inputs passed");
}
