#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusManagedState.h"
#include <cassert>
#include <cstdio>
#include <mutex>
#include <unordered_set>
#include <vector>
#include <cstring>
void *keep(void *p,bool) {return p;}
void *target(void *p) {return p;}
void release(void *) {}
limbus_managed::GcApi g_gc{keep,keep,target,release};
std::mutex g_font_state_lock;
#define pthread_mutex_lock(p) (p)->lock()
#define pthread_mutex_unlock(p) (p)->unlock()
struct Font {std::string name;};
Font ja{"JP Main"},cn{"Chinese"},number{"ExcelsiorSans SDF"};
struct Component {int32_t id=1; Font *font=&ja; void *text=nullptr;};
struct Owner {Component *component; int32_t category=0,language=2;bool use_category=true;};
int target_field,category_field,use_field,language_field,font_getter,text_getter;
const void *g_tmp_get_font_info=&font_getter,*g_tmp_get_text_info=&text_getter;
void *g_tmp_font_asset=&cn;bool g_live_resources=true;
int32_t component_id(void *p) {return p?static_cast<Component *>(p)->id:0;}
void get_field(void *p,const void *f,void *out) {
    auto &o=*static_cast<Owner *>(p);
    if(f==&target_field)*static_cast<void **>(out)=o.component;
    else if(f==&category_field)*static_cast<int32_t *>(out)=o.category;
    else if(f==&language_field)*static_cast<int32_t *>(out)=o.language;
    else if(f==&use_field)*static_cast<bool *>(out)=o.use_category;
    else assert(false);
}
auto g_field_get_value=get_field;
void *invoke_object_getter(const void *method,void *p) {
    auto &c=*static_cast<Component *>(p);return method==&font_getter?c.font:c.text;
}
std::string read_unity_object_name(void *p) {return p?static_cast<Font *>(p)->name:"";}
struct TmpComponentState {void *font;};
bool find_tmp_component_state(void *,TmpComponentState *state) {state->font=&ja;return true;}
int empty_text,prepares=0;
void *new_managed_string(const std::string &) {return &empty_text;}
void *prepare_tmp_text(void *p,void *s,bool restore) {
    assert(!restore);++prepares;static_cast<Component *>(p)->font=&cn;return s;
}
#include "runtime-language-font-under-test.inc"
void original(void *p,int32_t language,const void *) {
    auto &o=*static_cast<Owner *>(p);
    assert(is_language_font_target(o.component)==(language==2&&o.category<2));
    o.component->font=&ja;
}
int main() {
    for(auto &b:g_language_font_bindings) {
        b.target=&target_field;b.category=&category_field;b.categories={0,1};
        b.original=reinterpret_cast<void *>(original);
    }
    g_language_font_bindings[0].use_category=&use_field;
    Component a,b; b.id=2;Owner oa{&a},ob{&b};
    // Empty first frame and unrelated/partial text have the same component ownership.
    for(auto *o:{&oa,&ob,&ob,&oa}) {
        replacement_language_font(o,2,nullptr);
        assert(o->component->font==&cn&&is_language_font_target(o->component));
    }
    a.text=&empty_text;replacement_language_font(&oa,2,nullptr);assert(a.font==&cn);
    oa.category=20;replacement_language_font(&oa,2,nullptr);
    assert(!is_language_font_target(&a)&&a.font==&ja);
    oa.category=0;oa.use_category=false;a.font=&number;
    assert(!bind_language_font_target(&oa,0));assert(!is_language_font_target(&a));
    a.font=&ja;assert(bind_language_font_target(&oa,0)==&a);
    ++a.id;assert(!is_language_font_target(&a)); // Same address, new Unity object.
    assert(bind_language_font_target(&oa,0)==&a);
    assert(!bind_language_font_target(&oa,0,1));assert(!is_language_font_target(&a));
    g_live_resources=false;assert(!bind_language_font_target(&ob,0));assert(!is_language_font_target(&b));
    assert(prepares==5);
    puts("Production language ownership: empty/partial text, order, category, state and instance reuse passed");
}
