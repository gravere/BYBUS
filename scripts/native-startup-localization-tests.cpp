#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusManagedState.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <unordered_set>
#include <vector>
#include <atomic>

void *keep(void *p, bool) { return p; }
void *target(void *p) { return p; }
void release(void *) {}
limbus_managed::GcApi g_gc{keep,keep,target,release};
std::mutex g_font_state_lock;
#define pthread_mutex_lock(p) (p)->lock()
#define pthread_mutex_unlock(p) (p)->unlock()
int data_class,game_class,table_class,list_class,model_class,other_class;
int data_field,game_field,left_field,right_field,remote_field;
int list_method,count_method,item_method,lang_method,custom_method,load_method;
int key_identity,method_identity,exception_identity;
struct Object { void *klass; int32_t count=0; };
Object data{&data_class},game{&game_class},left{&table_class},right{&table_class};
Object replacement{&table_class},other{&other_class},model{&model_class};
Object left_list{&list_class},right_list{&list_class},new_list{&list_class};
void *data_instance=&data,*game_instance=&game;
Object *left_table=&left;
bool remote_loaded=false,custom=false,main_thread=true,g_live_resources=true;
bool throws=false,populates=true,replaces=false,placeholder=false;
int32_t language=20;
Object placeholder_model{&other_class};
int loads=0,translated=0,retry_events=0;
std::unordered_map<int32_t,const char *> g_language_codes{{10,"jp"},{20,"en"},{30,"kr"}};
bool is_unity_main_thread() { return main_thread; }
void append_native_metadata(const char *,const char *s) {
    if (!strcmp(s,"startup_localization_retry=begin")) ++retry_events;
}
void static_value(const void *f,void *out) {
    assert(f==&data_field||f==&game_field);
    *static_cast<void **>(out)=f==&data_field?data_instance:game_instance;
}
void field_value(void *p,const void *f,void *out) {
    assert(p==&data);
    if (f==&remote_field) *static_cast<bool *>(out)=remote_loaded;
    else { assert(f==&left_field||f==&right_field); *static_cast<void **>(out)=f==&left_field?left_table:&right; }
}
auto g_field_get_value=field_value;
void *get_class(void *p) { return static_cast<Object *>(p)->klass; }
auto g_object_get_class=get_class;
bool ensure_tmp_font() { return true; }
void translate_object_graph(void *p,void *) { assert(p==&model); ++translated; }
const void *get_method(void *k,const char *name,int) {
    assert(k==&list_class); return !strcmp(name,"get_Count")?&count_method:&item_method;
}
auto g_class_get_method=get_method;
void *invoke(const void *,void *,void **,void **);
auto g_runtime_invoke=invoke;
void *unbox(void *p) { return p; }
auto g_object_unbox=unbox;
#include "runtime-localized-table-under-test.inc"

void *invoke(const void *m,void *p,void **args,void **exception) {
    assert(!*exception);
    if (m==&custom_method) { assert(!p&&!args); return &custom; }
    if (m==&lang_method) { assert(p==&game&&!args); return &language; }
    if (m==&list_method) {
        assert(p==&left||p==&right||p==&replacement);
        return p==&right?&right_list:p==&replacement?&new_list:&left_list;
    }
    if (m==&count_method) { assert(!args); return &static_cast<Object *>(p)->count; }
    if (m==&item_method) return &model;
    assert(m==&load_method&&p==&data&&*static_cast<int32_t *>(args[0])==language);
    ++loads;
    assert(!retry_empty_startup_table(&left)); // Reentrant UI during LoadLocal must not recurse.
    if (throws) { *exception=&exception_identity; return nullptr; }
    if (populates) {
        if (replaces) { left_table=&replacement; new_list.count=1; }
        else left_list.count=1;
        right_list.count=1;
    }
    return nullptr;
}
void *get_ref(void *p,void *key,const void *method) {
    assert(key==&key_identity&&method==&method_identity);
    if (p==&replacement) return new_list.count?&model:nullptr;
    if (p==&left) return left_list.count?&model:placeholder?&placeholder_model:nullptr;
    if (p==&right) return right_list.count?&model:nullptr;
    return nullptr;
}
void *get_int(void *p,int32_t key,const void *method) {
    assert(key==27); return get_ref(p,&key_identity,method);
}
void reset() {
    std::unordered_set<int32_t> previous;
    g_startup_localization_attempts.take(&data,0,&previous);
    left_list.count=right_list.count=new_list.count=0;
    left_table=&left; data_instance=&data; game_instance=&game;
    remote_loaded=custom=throws=replaces=placeholder=false;
    populates=main_thread=g_live_resources=true;
    language=20; loads=retry_events=0;
}
void *query(void *p=&left) { return replacement_table_ref<0>(p,&key_identity,&method_identity); }
int main() {
    auto &b=g_startup_localization;
    b.data_class=&data_class;b.game_class=&game_class;
    b.data_instance=&data_field;b.game_instance=&game_field;b.static_value=static_value;
    b.remote_loaded=&remote_field;b.language=&lang_method;b.load=&load_method;b.custom=&custom_method;
    b.tables[0]={&left_field,&table_class,&list_method,&count_method};
    b.tables[1]={&right_field,&table_class,&list_method,&count_method};b.ready=true;
    g_localized_tables[0][0].owners.insert(&table_class);
    g_localized_tables[0][0].original=reinterpret_cast<void *>(get_ref);
    g_localized_tables[1][0].owners.insert(&table_class);
    g_localized_tables[1][0].original=reinterpret_cast<void *>(get_int);
    g_localized_models.insert(&model_class);
    for (int32_t lang:{10,20,30}) {
        reset();language=lang;assert(query()==&model&&loads==1&&retry_events==1);
        assert(query()==&model&&loads==1);
    }
    reset();replaces=true;assert(query()==&model&&left_table==&replacement&&loads==1);
    reset();assert(retry_empty_startup_table(nullptr)==&left&&loads==1);
    assert(query()==&model&&loads==1);
    reset();right_list.count=1;assert(!retry_empty_startup_table(nullptr)&&loads==0);
    reset();placeholder=true;assert(query()==&model&&loads==1);
    reset();placeholder=true;populates=false;assert(query()==&placeholder_model&&loads==1);
    assert(query()==&placeholder_model&&loads==1);
    reset();assert(replacement_table_int<0>(&left,27,&method_identity)==&model&&loads==1);
    reset();throws=true;assert(!query()&&loads==1);assert(!query()&&loads==1);
    reset();populates=false;assert(!query()&&loads==1);assert(!query()&&loads==1);
    language=30;assert(!query()&&loads==2);language=20;assert(!query()&&loads==2);
    reset();left_list.count=1;assert(query()==&model&&loads==0);
    reset();right_list.count=1;assert(!query()&&loads==0);
    reset();remote_loaded=true;assert(!query()&&loads==0);
    reset();custom=true;assert(!query()&&loads==0);
    reset();language=99;assert(!query()&&loads==0);
    reset();main_thread=false;assert(!query()&&loads==0);
    reset();g_live_resources=false;assert(!query()&&loads==0);
    reset();{LocalizationLoadScope active;assert(!query()&&loads==0);}assert(g_localization_load_depth==0);
    reset();data_instance=nullptr;assert(!query()&&loads==0);
    reset();game_instance=&other;assert(!query()&&loads==0);
    reset();b.ready=false;assert(!query()&&loads==0);b.ready=true;
    reset();assert(!query(&other)&&loads==0);
    reset();Object unrelated_table{&table_class};assert(!query(&unrelated_table)&&loads==0);
    assert(translated==1); // Model preparation remains shared and idempotent.
    puts("Startup localization: real wrappers, three languages, empty/healthy tables, reload replacement, recursion, exceptions and attempt limits passed");
}
