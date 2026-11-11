#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFlowTextProof.h"
#include <cassert>
#include <codecvt>
#include <cstdio>
#include <locale>
#include <mutex>

struct Object { int id; void *body = nullptr; void *result = nullptr; };
struct Log { void *text; };
using List = std::vector<Log *>;
std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> codec;
int32_t length(void *s) { return static_cast<std::u16string *>(s)->size(); }
const uint16_t *chars(void *s) { return reinterpret_cast<const uint16_t *>(static_cast<std::u16string *>(s)->data()); }
auto g_string_length = length;
auto g_string_chars = chars;
bool utf16_to_utf8(const uint16_t *s, int32_t n, std::string *out) {
    *out = codec.to_bytes(reinterpret_cast<const char16_t *>(s), reinterpret_cast<const char16_t *>(s) + n); return true;
}
std::mutex g_font_state_lock;
#define pthread_mutex_lock(p) (p)->lock()
#define pthread_mutex_unlock(p) (p)->unlock()
struct ChoiceFlowState { int32_t id = 0; void *owner = nullptr; int32_t owner_id = 0; limbus_font_proof::FlowProof proof; };
std::unordered_map<void *, ChoiceFlowState> g_choice_owners, g_choice_targets;
limbus_font_proof::Catalogue g_font_catalogue, g_recent_font_outputs;
int id_method, count_method, item_method, log_field, content_field, button_field, result_field, log_class;
const void *g_object_get_instance_id_info = &id_method;
const void *g_choice_result_log_field = &log_field;
const void *g_choice_content_field = &content_field;
const void *g_choice_button_text_field = &button_field;
const void *g_choice_button_result_field = &result_field;
void *g_choice_result_log_class = &log_class;
void *get_class(void *) { return &log_class; }
auto g_object_get_class = get_class;
const void *find_collection_method(void *, const char *name, std::initializer_list<const char *>) {
    return std::string(name) == "get_Count" ? &count_method : &item_method;
}
bool invoke_int_getter(const void *method, void *object, int32_t *out) {
    if (!object) return false;
    *out = method == &id_method ? static_cast<Object *>(object)->id : static_cast<List *>(object)->size(); return true;
}
void field_get(void *object, const void *field, void *out) {
    *static_cast<void **>(out) = field == &log_field ? static_cast<Log *>(object)->text :
        field == &result_field ? static_cast<Object *>(object)->result : static_cast<Object *>(object)->body;
}
void field_set(void *object, const void *, void *value) { static_cast<Log *>(object)->text = value; }
auto g_field_get_value = field_get;
auto g_field_set_value = field_set;
void *invoke(const void *method, void *object, void **args, void **) {
    assert(method == &item_method);
    return static_cast<List *>(object)->at(*static_cast<int32_t *>(args[0]));
}
auto g_runtime_invoke = invoke;
bool ensure_tmp_font() { return true; }
void *translate_managed_string(void *s) { return s; }
std::u16string font_input;
void *new_managed_string(const std::string &s) { font_input = codec.from_bytes(s); return &font_input; }
std::unordered_set<void *> prepared_targets;
bool is_translated_managed_string(void *s) {
    return s && g_font_catalogue.contains(codec.to_bytes(*static_cast<std::u16string *>(s)));
}
using Read = void *(*)(void *, void *, void *, const void *);
using Set = void (*)(void *, void *, const void *);
Read g_original_choice_read_string, g_original_choice_read_list, g_original_choice_read_gift;
Set g_original_choice_set_total, g_original_choice_set_text;
void (*g_original_choice_clear)(void *, const void *);
void (*g_original_choice_button)(void *, int32_t, void *, void *, const void *);
#include "runtime-flow-under-test.inc"

void *prepare_tmp_text(void *target, void *text, bool restore) {
    assert(!restore && is_choice_text(target, text)); prepared_targets.insert(target); return text;
}

int calls = 0;
void *expected_target, *expected_input;
std::u16string probe;
void *read(void *, void *target, void *input, const void *) {
    assert(target == expected_target && input == expected_input);
    assert(is_choice_text(target, &probe) && prepared_targets.count(target)); ++calls; return input;
}
void set(void *owner, void *, const void *) {
    assert(is_choice_text(static_cast<Object *>(owner)->body, &probe)); ++calls;
}
void clear(void *, const void *) { ++calls; }
void button(void *owner, int32_t index, void *, void *, const void *) {
    assert(index == 2 && is_choice_text(static_cast<Object *>(owner)->body, &probe)); ++calls;
}
int main() {
    g_original_choice_read_string = g_original_choice_read_list = g_original_choice_read_gift = read;
    g_original_choice_set_total = g_original_choice_set_text = set;
    g_original_choice_clear = clear; g_original_choice_button = button;
    Object target{2}, owner{1, &target}, second{3};
    auto full = codec.from_bytes("得到饰品。<br>继续前进。");
    g_font_catalogue.add(codec.to_bytes(full));
    probe = u"继续"; expected_target = &target; expected_input = &full;
    replacement_choice_read_string(&owner, &target, &full, nullptr);
    Log log{&full}; List list{&log}; expected_input = &list;
    replacement_choice_read_list(&owner, &target, &list, nullptr);
    // The derived gift renderer must bind BEFORE its coroutine is constructed.
    g_choice_targets.clear();
    replacement_choice_read_gift(&owner, &target, &list, nullptr);
    assert(!is_choice_text(&second, &probe));
    g_choice_targets.clear();
    replacement_choice_set_total(&owner, &list, nullptr);
    g_choice_targets.clear();
    replacement_choice_set_text(&owner, &full, nullptr);
    auto unknown = codec.from_bytes("另一个未知事件。");
    assert(!is_choice_text(&target, &unknown));
    target.id = 8;
    assert(!is_choice_text(&target, &probe)); // Reused native address is not identity.
    target.id = 2;
    replacement_choice_clear(&owner, nullptr);
    assert(!is_choice_text(&target, &probe));
    assert(calls == 6);
    puts("Production event entries: base/derived, direct/skip, target identity, reset and paragraph proof passed");
}
