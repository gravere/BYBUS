#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusManagedState.h"
#include <cassert>
#include <cstdio>
#include <mutex>
#include <unordered_set>
#include <vector>
#include <atomic>
void append_native_metadata(const char *,const char *) {}
void *keep(void *p,bool) {return p;}
void *target(void *p) {return p;}
void release(void *) {}
limbus_managed::GcApi g_gc{keep,keep,target,release};
std::mutex g_font_state_lock;
#define pthread_mutex_lock(p) (p)->lock()
#define pthread_mutex_unlock(p) (p)->unlock()
int table_class,unrelated_class,model_class,list_class,count_method,item_method,method_identity,key_identity;
int first_field, second_field, load_list_method;
struct Object {void *klass; int32_t translations=0;};
Object table{&table_class},other{&unrelated_class},a{&model_class},b{&model_class};
Object c{&model_class},d{&model_class};
struct List : Object {std::vector<Object *> models;};
List list{{&list_class}, {&a,&b}};
List loaded{{&list_class}, {&c,&d}};
struct Dataset {Object *first;Object *second;};
void field_value(void *p,const void *f,void *out) {
    auto &set=*static_cast<Dataset *>(p);
    *static_cast<void **>(out)=f==&first_field?set.first:set.second;
}
auto g_field_get_value=field_value;
void *get_class(void *p) {return static_cast<Object *>(p)->klass;}
auto g_object_get_class=get_class;
bool font_ready=true;
bool ensure_tmp_font() {return font_ready;}
void translate_object_graph(void *p,void *) {++static_cast<Object *>(p)->translations;}
const void *get_method(void *k,const char *name,int) {
    assert(k==&list_class);return std::string(name)=="get_Count"?&count_method:&item_method;
}
auto g_class_get_method=get_method;
void *invoke(const void *m,void *p,void **args,void **exception) {
    if(m==&load_list_method) {assert(p==&table&&!args);return &loaded;}
    assert(!*exception);auto &l=*static_cast<List *>(p);static int32_t count;
    if(m==&count_method){count=l.models.size();return &count;}
    assert(m==&item_method);return l.models.at(*static_cast<int32_t *>(args[0]));
}
auto g_runtime_invoke=invoke;
void *unbox(void *p) {return p;}
auto g_object_unbox=unbox;
#include "runtime-localized-table-under-test.inc"
void *get_ref(void *,void *key,const void *method) {assert(key==&key_identity&&method==&method_identity);return &a;}
void *get_int(void *,int32_t key,const void *method) {assert(key==-123&&method==&method_identity);return &b;}
void *get_list(void *,const void *method) {assert(method==&method_identity);return &list;}
int main() {
    g_localized_models.insert(&model_class);
    for(int abi=0;abi<3;++abi)g_localized_tables[abi][0].owners.insert(&table_class);
    g_localized_tables[0][0].original=reinterpret_cast<void *>(get_ref);
    g_localized_tables[1][0].original=reinterpret_cast<void *>(get_int);
    g_localized_tables[2][0].original=reinterpret_cast<void *>(get_list);
    assert(replacement_table_ref<0>(&other,&key_identity,&method_identity)==&a&&a.translations==0);
    font_ready=false;replacement_table_int<0>(&table,-123,&method_identity);assert(b.translations==0);
    font_ready=true;
    assert(replacement_table_list<0>(&table,&method_identity)==&list);
    assert(a.translations==1&&b.translations==1);
    for(int n=0;n<3;++n) {
        replacement_table_int<0>(&table,-123,&method_identity);
        replacement_table_ref<0>(&table,&key_identity,&method_identity);
        replacement_table_list<0>(&table,&method_identity);
    }
    assert(a.translations==1&&b.translations==1);
    prepare_localized_model(&other);assert(other.translations==0);
    Dataset set{&table,&other};
    g_localized_loads.push_back({&first_field,&table_class,&load_list_method});
    g_localized_loads.push_back({&second_field,&table_class,&load_list_method});
    font_ready=false;prepare_loaded_localization(&set);assert(c.translations==0);
    font_ready=true;prepare_loaded_localization(&set);
    assert(c.translations==1&&d.translations==1);
    prepare_loaded_localization(&set);assert(c.translations==1&&d.translations==1);
    puts("Production typed retrieval: pre-getter processing, shared-body isolation, repeat order, font failure and load completion passed");
}
