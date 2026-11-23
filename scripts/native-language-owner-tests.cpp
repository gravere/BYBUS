#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusManagedState.h"
#include <cassert>
#include <cstdio>
#include <mutex>
#include <unordered_set>
#include <unordered_map>
#include <vector>
#include <cstring>
#include <atomic>
void *keep(void *p,bool) {return p;}
void *target(void *p) {return p;}
void release(void *) {}
limbus_managed::GcApi g_gc{keep,keep,target,release};
std::mutex g_font_state_lock;
#define pthread_mutex_lock(p) (p)->lock()
#define pthread_mutex_unlock(p) (p)->unlock()
// Deliberately unlike today's enum: no assumption of JP=2 survives.
std::unordered_map<int32_t,const char *> g_language_codes{{10,"jp"},{20,"en"},{30,"kr"}};
std::atomic<int32_t> g_font_language{INT32_MIN};
thread_local void *g_language_font_update_target=nullptr;
struct Font {std::string name;};
Font ja{"JP Main"},en{"EN Main"},kr{"KR Main"},cn{"Chinese"},number{"ExcelsiorSans SDF"};
struct Component {
    int32_t id=1; Font *font=&ja; void *text=nullptr;
    Font *saved=nullptr; int material=10,captured_material=0;
};
struct Owner {
    Component *component; int32_t category=0,language=10; bool use_category=true;
    Font *requested=&ja; int material=20; Owner *nested=nullptr;
};
int target_field,category_field,use_field,language_field,font_getter,text_getter;
const void *g_tmp_get_font_info=&font_getter,*g_tmp_get_text_info=&text_getter;
void *g_tmp_font_asset=&cn;bool g_live_resources=true,g_font_ready=true;
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
bool find_tmp_component_state(void *p,TmpComponentState *state) {
    state->font=static_cast<Component *>(p)->saved;return state->font!=nullptr;
}
int empty_text,prepares=0,restores=0;
void *new_managed_string(const std::string &) {return &empty_text;}
void *prepare_tmp_text(void *p,void *s,bool restore) {
    assert(!restore&&p!=g_language_font_update_target);++prepares;
    auto &c=*static_cast<Component *>(p);
    if(c.font!=&cn)c.saved=c.font;
    c.captured_material=c.material;c.font=&cn;return s;
}
void restore_tmp_component_state(void *p) {
    ++restores;auto &c=*static_cast<Component *>(p);
    if(c.font==&cn&&c.saved)c.font=c.saved;
    c.saved=nullptr;
}
bool is_translated_managed_string(void *s) {return s==&empty_text;}
bool is_choice_text(void *,void *) {return false;}
bool is_notice_text(void *,void *,bool) {return false;}
bool is_translated_tmp_fragment(void *,void *) {return false;}
void set_font(void *p,void *font,const void *) {static_cast<Component *>(p)->font=static_cast<Font *>(font);}
auto g_tmp_set_font=set_font;
#include "runtime-language-font-under-test.inc"
#include "runtime-font-refresh-under-test.inc"
void original(void *p,int32_t,const void *) {
    auto &o=*static_cast<Owner *>(p);
    assert(g_language_font_update_target==o.component);
    replacement_tmp_set_font(o.component,o.requested,nullptr);
    assert(o.component->font==o.requested);
    if(o.nested) {
        replacement_language_font(o.nested,o.nested->language,nullptr);
        assert(g_language_font_update_target==o.component);
    }
    o.component->material=o.material;
}
void original_state(void *p,const void *m) {original(p,static_cast<Owner *>(p)->language,m);}
int main() {
    for(auto &b:g_language_font_bindings) {
        b.target=&target_field;b.category=&category_field;b.categories={0,1};
        b.original=reinterpret_cast<void *>(original_state);
    }
    g_language_font_bindings[0].original=reinterpret_cast<void *>(original);
    g_language_font_bindings[0].use_category=&use_field;
    g_language_font_bindings[1].category=nullptr;
    g_language_font_bindings[1].language=&language_field;
    g_language_font_bindings[4].category=nullptr;
    g_language_font_bindings[4].language=&language_field;
    g_language_font_bindings[5].category=nullptr;
    Component a,b;b.id=2;Owner oa{&a},ob{&b};
    for(auto language:{10,20,30}) {
        g_font_language.store(language);oa.language=ob.language=language;
        oa.requested=ob.requested=language==10?&ja:language==20?&en:&kr;
        for(auto *o:{&oa,&ob,&ob,&oa}) {
            replacement_language_font(o,language,nullptr);
            assert(o->component->font==&cn&&is_language_font_target(o->component));
            assert(o->component->captured_material==o->material);
        }
        a.text=&empty_text;
        replacement_language_font_state<1>(&oa,nullptr);
        assert(a.font==&cn&&is_language_font_target(&a));
        replacement_language_font_state<2>(&oa,nullptr);
        replacement_language_font_state<3>(&oa,nullptr);
        replacement_language_font_state<4>(&oa,nullptr);
        replacement_language_font_state<5>(&oa,nullptr);
        assert(a.font==&cn&&a.saved==oa.requested);
    }
    g_font_language.store(10);oa.language=20;replacement_language_font_state<1>(&oa,nullptr);
    assert(a.font==&cn);
    g_font_language.store(INT32_MIN);oa.language=30;
    replacement_language_font_state<4>(&oa,nullptr);
    assert(a.font==&cn&&is_language_font_target(&a)); // Its own fixed language is authoritative.
    replacement_language_font_state<5>(&oa,nullptr);
    assert(!is_language_font_target(&a)&&a.font==oa.requested);
    g_font_language.store(20);
    oa.requested=&number;replacement_language_font_state<5>(&oa,nullptr);
    assert(!is_language_font_target(&a)&&a.font==&number);
    oa.requested=&en;
    oa.category=20;replacement_language_font(&oa,20,nullptr);
    assert(!is_language_font_target(&a)&&a.font==oa.requested&&a.saved==nullptr);
    oa.category=0;oa.use_category=false;oa.requested=&number;
    replacement_language_font(&oa,20,nullptr);
    assert(!is_language_font_target(&a)&&a.font==&number&&a.saved==nullptr);
    oa.requested=&en;replacement_language_font(&oa,20,nullptr);assert(a.font==&cn);
    ++a.id;assert(!is_language_font_target(&a));
    assert(bind_language_font_target(&oa,0,20)==&a);
    replacement_language_font(&oa,999,nullptr);
    assert(!is_language_font_target(&a)&&a.font==&en);
    g_font_language.store(INT32_MIN);replacement_language_font_state<2>(&oa,nullptr);
    assert(!is_language_font_target(&a)&&a.font==&en);
    oa.nested=&ob;ob.language=30;ob.requested=&kr;ob.material=99;
    replacement_language_font(&oa,20,nullptr);
    assert(a.font==&cn&&b.font==&cn&&a.captured_material==20&&b.captured_material==99);
    assert(g_language_font_update_target==nullptr);
    g_live_resources=false;assert(!bind_language_font_target(&ob,0,20)&&!is_language_font_target(&b));
    puts("Production font ownership: JP/EN/KR, all six owners, final preset, numeric roles, unknown/fixed language, reuse and nested updates passed");
}
