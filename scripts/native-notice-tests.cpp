#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusManagedState.h"
#include <cassert>
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
struct Object { int32_t id; bool wrap=false; int overflow=1; Object *body=nullptr; };
struct NoticeTextState { int32_t id=0; std::string translated; };
std::unordered_map<void *,NoticeTextState> g_notice_texts;
std::mutex g_font_state_lock;
void pthread_mutex_lock(std::mutex *m) { m->lock(); }
void pthread_mutex_unlock(std::mutex *m) { m->unlock(); }
int id_property,wrap_property,overflow_property,body_field;
const void *g_object_get_instance_id_info=&id_property,*g_tmp_set_word_wrapping_info=&wrap_property,
    *g_tmp_get_overflow_mode_info=&overflow_property,*g_tmp_set_overflow_mode_info=&overflow_property,*g_notice_body_field=&body_field;
bool invoke_int_getter(const void *property,void *object,int32_t *out) {
    *out=property==&id_property?static_cast<Object *>(object)->id:static_cast<Object *>(object)->overflow;return true;
}
bool invoke_int_setter(const void *,void *object,int32_t value) {static_cast<Object *>(object)->overflow=value;return true;}
bool invoke_bool_setter(const void *,void *object,bool value) {static_cast<Object *>(object)->wrap=value;return true;}
void field(void *object,const void *,void *out) {*static_cast<void **>(out)=static_cast<Object *>(object)->body;}
auto g_field_get_value=field;
int32_t length(void *text) {return static_cast<std::u16string *>(text)->size();}
const uint16_t *characters(void *text) {return reinterpret_cast<const uint16_t *>(static_cast<std::u16string *>(text)->data());}
auto g_string_length=length; auto g_string_chars=characters;
bool utf16_to_utf8(const uint16_t *text,int32_t length,std::string *out) {
    std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t> codec;
    *out=codec.to_bytes(reinterpret_cast<const char16_t *>(text),reinterpret_cast<const char16_t *>(text)+length);return true;
}
std::u16string raw=u"公開のお知らせ",chinese=u"公开公告",unknown=u"未翻訳";
void *translated_notice_body(void *source) {return source==&raw?&chinese:source;}
void *received=nullptr;
void original(void *owner,void *source,const void *) {
    auto *body=static_cast<Object *>(owner)->body;
    assert(body->wrap && body->overflow==0); // Actual game measures preferred height after assigning text.
    received=source; body->wrap=false; body->overflow=3;
}
auto g_original_notice_set_data=original;
#include "runtime-notice-under-test.inc"
int main() {
    Object body{12},other{13},owner{4,false,0,&body};
    replacement_notice_set_data(&owner,&raw,nullptr);
    assert(received==&chinese && body.wrap && body.overflow==0);
    assert(is_notice_text(&body,&chinese,true) && !is_notice_text(&other,&chinese,true));
    assert(!is_notice_text(&body,&raw,true));
    replacement_notice_set_data(&owner,&unknown,nullptr);
    assert(received==&unknown && is_notice_text(&body) && !is_notice_text(&body,&chinese,true));
    ++body.id; assert(!is_notice_text(&body));
    puts("Notice body: exact component provenance, untranslated preservation and pre-measure wrapping passed");
}
