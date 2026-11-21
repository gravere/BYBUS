#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusManagedState.h"
#include <cassert>
#include <algorithm>
#include <cmath>
#include <codecvt>
#include <cstdint>
#include <cstdio>
#include <locale>
#include <mutex>
#include <string>
#include <unordered_map>
void *keep(void *p,bool) { return p; }
void *target(void *p) { return p; }
void release(void *) {}
limbus_managed::GcApi g_gc{keep,keep,target,release};
#include "runtime-notice-state.inc"
struct Object { int32_t id; int wrap=0; int overflow=1; Object *body=nullptr; float width=400,height=100; Object *parent=nullptr; };
std::mutex g_font_state_lock;
void pthread_mutex_lock(std::mutex *m) { m->lock(); }
void pthread_mutex_unlock(std::mutex *m) { m->unlock(); }
int id_property,wrap_property,overflow_property,body_field,format_field,rect_property,parent_property,preferred_method,extent_method,rect_class;
const void *g_object_get_instance_id_info=&id_property,*g_tmp_set_word_wrapping_info=&wrap_property,
    *g_tmp_get_overflow_mode_info=&overflow_property,*g_tmp_set_overflow_mode_info=&overflow_property,*g_notice_body_field=&body_field,
    *g_notice_format_field=&format_field,*g_notice_extra_fields[]={&body_field,&body_field,&body_field},
    *g_tmp_get_rect_transform_info=&rect_property,*g_transform_get_parent_info=&parent_property,*g_component_get_transform_info=&rect_property,
    *g_notice_preferred_info=&preferred_method,*g_notice_set_extent_info=&extent_method;
const void *g_tmp_get_word_wrapping_info=&wrap_property,*g_tmp_get_wrapping_mode_info=&wrap_property,*g_tmp_set_wrapping_mode_info=&wrap_property;
void *g_notice_rect_class=&rect_class;
bool invoke_int_getter(const void *property,void *object,int32_t *out) {
    *out=property==&id_property?static_cast<Object *>(object)->id:property==&wrap_property?static_cast<Object *>(object)->wrap:static_cast<Object *>(object)->overflow;return true;
}
bool invoke_int_setter(const void *property,void *object,int32_t value) {if(property==&wrap_property)static_cast<Object *>(object)->wrap=value;else static_cast<Object *>(object)->overflow=value;return true;}
bool invoke_bool_getter(const void *,void *object,bool *value) {*value=static_cast<Object *>(object)->wrap!=0;return true;}
bool invoke_bool_setter(const void *,void *object,bool value) {static_cast<Object *>(object)->wrap=value;return true;}
#include "runtime-wrapping-under-test.inc"
std::u16string format=u"<size=120%>{0}</size>";
void field(void *object,const void *slot,void *out) {*static_cast<void **>(out)=slot==&format_field?static_cast<void *>(&format):static_cast<void *>(static_cast<Object *>(object)->body);}
auto g_field_get_value=field;
int32_t length(void *text) {return static_cast<std::u16string *>(text)->size();}
const uint16_t *characters(void *text) {return reinterpret_cast<const uint16_t *>(static_cast<std::u16string *>(text)->data());}
auto g_string_length=length; auto g_string_chars=characters;
bool utf16_to_utf8(const uint16_t *text,int32_t length,std::string *out) {
    std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t> codec;
    *out=codec.to_bytes(reinterpret_cast<const char16_t *>(text),reinterpret_cast<const char16_t *>(text)+length);return true;
}
void *invoke_object_getter(const void *info,void *instance) {return info==&parent_property?static_cast<Object *>(instance)->parent:instance;}
struct ComponentRect {float width,height;bool valid;};
ComponentRect read_component_rect(void *p,const void *) {auto o=static_cast<Object *>(p);return {o->width,o->height,true};}
void *get_class(void *) {return &rect_class;}
auto g_object_get_class=get_class;
float measured[2]={400,701.2f};
void *invoke(const void *info,void *instance,void **args,void **) {
    if(info==&preferred_method){measured[0]=*static_cast<float *>(args[0]);return measured;}
    auto o=static_cast<Object *>(instance);int axis=*static_cast<int *>(args[0]);
    (axis==0?o->width:o->height)=*static_cast<float *>(args[1]);return nullptr;
}
auto g_runtime_invoke=invoke;auto g_object_unbox=target;
std::u16string raw=u"公開のお知らせ",chinese=u"公开公告",unknown=u"Notice on Reinforced Monitoring for Unauthorized Account Creation";
void *translated_notice_body(void *source) {return source==&raw?&chinese:source;}
void *translate_notice_component(void *,void *);
void *received=nullptr;
void original(void *owner,void *source,const void *) {
    auto *body=static_cast<Object *>(owner)->body;
    assert(body->wrap && body->overflow==0); // Actual game measures preferred height after assigning text.
    received=source; body->wrap=false; body->overflow=3;
}
auto g_original_notice_set_data=original;
void title_original(void *owner,void *source,const void *method) {
    original(owner,translate_notice_component(static_cast<Object *>(owner)->body,source),method);
}
using setter=void (*)(void *,void *,const void *);
setter g_original_notice_extra[]={title_original,title_original,title_original};
#include "runtime-notice-under-test.inc"
int main() {
    Object parent{9},body{12},other{13},owner{4};owner.body=&body;body.parent=&parent;parent.width=360;
    replacement_notice_set_data(&owner,&raw,nullptr);
    assert(received==&chinese && body.wrap && body.overflow==0 && body.height==702 && body.width==360);
    assert(is_notice_text(&body,&chinese,true) && !is_notice_text(&other,&chinese,true));
    assert(!is_notice_text(&body,&raw,true));
    std::u16string styled=u"<size=120%>公开公告</size>";
    assert(is_notice_text(&body,&styled,true));
    replacement_notice_set_data(&owner,&unknown,nullptr);
    assert(received==&unknown && is_notice_text(&body) && !is_notice_text(&body,&chinese,true));
    body.height=120;body.wrap=false;
    replacement_notice_extra<0>(&owner,&unknown,nullptr);
    assert(received==&unknown && body.wrap && body.height==120);
    replacement_notice_extra<1>(&owner,&raw,nullptr);
    assert(received==&chinese && is_notice_text(&body,&chinese,true));
    body.wrap=3;prepare_notice_body(&body);assert(body.wrap==2);
    assert(!is_notice_text(&other));
    ++body.id; assert(!is_notice_text(&body));
    prepare_notice_body(&other);assert(!other.wrap);
    puts("Notice: scoped titles, unknown preservation, wrapped measurement, body sizing, formatted font proof and recycled identity passed");
}
