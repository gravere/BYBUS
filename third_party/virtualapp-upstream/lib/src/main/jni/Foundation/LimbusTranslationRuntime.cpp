#include "LimbusTranslationRuntime.h"
#include "LimbusCodeIntegrityCompat.h"
#include "LimbusUiLabels.h"
#include "LimbusTextProvenance.h"
#include "LimbusFontProof.h"
#include "LimbusFlowTextProof.h"
#include "LimbusManagedState.h"
#include "../Substrate/SubstrateHook.h"

#include <android/log.h>
#include <algorithm>
#include <arpa/inet.h>
#include <atomic>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <initializer_list>
#include <sys/mman.h>
#include <sys/stat.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <time.h>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <unistd.h>

#define LT_TAG "LimbusTranslation"
#define LT_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LT_TAG, __VA_ARGS__)
#define LT_LOGW(...) __android_log_print(ANDROID_LOG_WARN, LT_TAG, __VA_ARGS__)

namespace {

const unsigned char kIndexMagic[] = {'L', 'Z', 'T', 'I', '1', 0};
const uint32_t kIndexSchema = 9;
const uint32_t kMaxEntries = 1000000;
const uint32_t kMaxStringBytes = 16 * 1024 * 1024;

pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_font_state_lock = PTHREAD_MUTEX_INITIALIZER;
char g_active_pointer[PATH_MAX] = {};
pthread_mutex_t g_diagnostic_lock = PTHREAD_MUTEX_INITIALIZER;

// Only fixed native metadata calls this writer. No displayed text is accepted.
void append_native_metadata(const char *name, const char *entry) {
    std::string root(g_active_pointer);
    const auto slash = root.rfind('/');
    if (slash == std::string::npos || strlen(entry) > 2048) return;
    root.resize(slash);
    pthread_mutex_lock(&g_diagnostic_lock);
    FILE *file = fopen((root + "/" + name).c_str(), "a+");
    if (file != nullptr) {
        if (fseek(file, 0, SEEK_END) == 0) {
            long size = ftell(file);
            std::string tail;
            if (size >= 0 && size + static_cast<long>(strlen(entry)) + 128 > 32768) {
                // Keep recent complete rows; stopping at the cap hid later game pages.
                char buffer[16384];
                if (fseek(file, std::max(0L, size - static_cast<long>(sizeof(buffer))), SEEK_SET) == 0) {
                    size_t count = fread(buffer, 1, sizeof(buffer), file);
                    tail.assign(buffer, count);
                    const auto newline = tail.find('\n');
                    tail = newline == tail.npos ? std::string{} : tail.substr(newline + 1);
                    if (ftruncate(fileno(file), 0) == 0) { rewind(file); size = 0; }
                }
            }
            if (size >= 0 && size + static_cast<long>(strlen(entry)) + 128 <= 32768) {
                if (size == 0) {
                    char request[80] = {};
                    FILE *token = fopen((root + "/launch.request").c_str(), "r");
                    if (token != nullptr) { fgets(request, sizeof(request), token); fclose(token); }
                    request[strcspn(request, "\r\n")] = 0;
                    if (strlen(request) != 36 || strspn(request, "0123456789abcdef-") != 36) request[0] = 0;
                    fprintf(file, "request=%s pid=%d\n", request, getpid());
                    if (!tail.empty()) fwrite(tail.data(), 1, tail.size(), file);
                }
                fprintf(file, "%s\n", entry);
            }
        }
        fclose(file);
    }
    pthread_mutex_unlock(&g_diagnostic_lock);
}
std::unordered_map<std::string, std::string> g_index;
std::unordered_map<std::string, std::string> g_terms;
std::unordered_map<std::string, std::string> g_contextual_index;
std::atomic<uint64_t> g_contextual_hits{0};
// 部分人格列表会把汉化包标题中的换行压成空格或直接移除；该索引只保存由包内译文
// 推导出的原始分行，让运行时兼容不同页面，而无需硬编码任何人格名称或译文。
std::unordered_map<std::string, std::string> g_package_line_breaks;
limbus_font_proof::Catalogue g_font_catalogue;
struct TermTrieNode {
    std::unordered_map<unsigned char, size_t> children;
    const std::string *source = nullptr;
    const std::string *translation = nullptr;
};
std::vector<TermTrieNode> g_term_trie;
bool g_configured = false;
bool g_resolver_reported = false;
bool g_probe_started = false;
bool g_hook_installed = false;
bool g_font_attempted = false;
pthread_t g_unity_init_thread{};
void *g_tmp_font_asset = nullptr;
void *g_ui_font = nullptr;
limbus_managed::GcApi g_gc;
limbus_managed::Registry<bool> g_translated_objects(g_gc, 262144);
limbus_managed::RecentOutputs g_recent_font_outputs;
limbus_managed::Ref g_tmp_font_root;
limbus_managed::Ref g_ui_font_root;
// Called with g_font_state_lock held. Identity survives GC safely and content
// evidence is used only for styling copies, never to veto another source.
bool remember_translated_text(void *object, const std::string &text) {
    if (!g_translated_objects.put(object, 0, true)) return false;
    g_recent_font_outputs.add(text);
    return true;
}
struct TmpComponentState {
    void *font = nullptr;
    void *shared_material = nullptr;
    limbus_managed::Ref font_root;
    limbus_managed::Ref material_root;
    float line_spacing = 0.0f;
    bool has_line_spacing = false;
    float font_size = 0.0f;
    bool has_font_size = false;
    float font_size_min = 0.0f;
    bool has_font_size_min = false;
    float font_size_max = 0.0f;
    bool has_font_size_max = false;
    bool auto_sizing = false;
    bool has_auto_sizing = false;
    bool word_wrapping = false;
    bool has_word_wrapping = false;
    int32_t overflow_mode = 0;
    bool has_overflow_mode = false;
};
limbus_managed::Registry<TmpComponentState> g_original_tmp_states(g_gc, 8192);
struct UiComponentState {
    void *font = nullptr;
    limbus_managed::Ref font_root;
    float line_spacing = 1.0f;
    bool has_line_spacing = false;
    int32_t font_size = 0;
    bool has_font_size = false;
    int32_t resize_min_size = 0;
    bool has_resize_min_size = false;
    int32_t resize_max_size = 0;
    bool has_resize_max_size = false;
    bool resize_best_fit = false;
    bool has_resize_best_fit = false;
    int32_t horizontal_overflow = 0;
    bool has_horizontal_overflow = false;
    int32_t vertical_overflow = 0;
    bool has_vertical_overflow = false;
};
limbus_managed::Registry<UiComponentState> g_original_ui_states(g_gc, 4096);
uint64_t g_translation_hits = 0;
char g_il2cpp_path[PATH_MAX] = {};

struct Il2CppStringLayout {
    void *klass;
    void *monitor;
    int32_t length;
    uint16_t chars[1];
};

typedef int32_t (*string_length_fn)(void *);
typedef const uint16_t *(*string_chars_fn)(void *);
typedef void *(*string_new_utf16_fn)(const uint16_t *, int32_t);
typedef void *(*create_dynamic_font_fn)(void *, int32_t, const void *);
typedef void *(*create_tmp_font_fn)(void *, const void *);
typedef void *(*runtime_invoke_fn)(const void *, void *, void **, void **);
typedef void *(*object_get_class_fn)(void *);
typedef void *(*object_unbox_fn)(void *);
typedef const void *(*class_get_method_global_fn)(void *, const char *, int);
typedef void *(*il2cpp_init_fn)(const char *);
typedef void *(*dlsym_fn)(void *, const char *);
typedef void (*tmp_set_font_fn)(void *, void *, const void *);
typedef void *(*tmp_get_font_fn)(void *, const void *);
typedef void (*tmp_set_material_fn)(void *, void *, const void *);
typedef void (*tmp_set_text_fn)(void *, void *, const void *);
typedef void (*tmp_set_text_string_fn)(void *, void *, bool, const void *);
typedef void (*tmp_parse_input_fn)(void *, const void *);
typedef int32_t (*get_language_fn)(void *, const void *);
typedef void *(*class_get_parent_fn)(void *);
typedef const void *(*class_get_fields_fn)(void *, void **);
typedef const char *(*field_get_name_fn)(const void *);
typedef const void *(*field_get_type_fn)(const void *);
typedef void (*field_get_value_fn)(void *, const void *, void *);
typedef void (*field_set_value_fn)(void *, const void *, void *);

string_length_fn g_string_length = nullptr;
string_chars_fn g_string_chars = nullptr;
string_new_utf16_fn g_string_new_utf16 = nullptr;
create_dynamic_font_fn g_create_dynamic_font = nullptr;
create_tmp_font_fn g_create_tmp_font = nullptr;
runtime_invoke_fn g_runtime_invoke = nullptr;
object_get_class_fn g_object_get_class = nullptr;
object_unbox_fn g_object_unbox = nullptr;
class_get_method_global_fn g_class_get_method = nullptr;
const void *(*g_class_get_methods)(void *, void **) = nullptr;
const char *(*g_method_get_name)(const void *) = nullptr;
uint32_t (*g_method_get_param_count)(const void *) = nullptr;
const void *(*g_method_get_param)(const void *, uint32_t) = nullptr;
tmp_set_font_fn g_tmp_set_font = nullptr;
tmp_get_font_fn g_tmp_get_font = nullptr;
tmp_set_material_fn g_original_tmp_set_shared_material = nullptr;
tmp_set_material_fn g_original_tmp_set_material = nullptr;
tmp_set_text_fn g_original_tmp_set_text = nullptr;
tmp_set_text_fn g_original_tmp_set_text_one = nullptr;
tmp_set_text_fn g_original_ui_set_text = nullptr;
tmp_set_text_fn g_original_announcer_set_description = nullptr;
const void *g_announcer_description_field = nullptr;
const void *g_object_get_instance_id_info = nullptr;
const void *g_object_is_alive_info = nullptr;
std::unordered_map<void *, int32_t> g_announcer_text_ids;
typedef void *(*choice_read_fn)(void *, void *, void *, const void *);
choice_read_fn g_original_choice_read_string = nullptr;
choice_read_fn g_original_choice_read_list = nullptr;
tmp_set_text_fn g_original_choice_set_total = nullptr;
tmp_parse_input_fn g_original_choice_clear = nullptr;
void *g_choice_result_log_class = nullptr;
const void *g_choice_result_log_field = nullptr;
const void *g_choice_button_text_field = nullptr;
const void *g_choice_button_result_field = nullptr;
typedef void (*choice_button_fn)(void *, int32_t, void *, void *, const void *);
choice_button_fn g_original_choice_button = nullptr;
struct ChoiceFlowState {
    int32_t id = 0;
    void *owner = nullptr;
    int32_t owner_id = 0;
    limbus_font_proof::FlowProof proof;
};
std::unordered_map<void *, ChoiceFlowState> g_choice_owners;
std::unordered_map<void *, ChoiceFlowState> g_choice_targets;
tmp_set_text_string_fn g_original_tmp_set_text_string = nullptr;
tmp_parse_input_fn g_original_tmp_parse_input = nullptr;
const void *g_tmp_get_text_info = nullptr;
const void *g_tmp_set_text_info = nullptr;
thread_local bool g_inside_tmp_parse = false;
std::atomic<uint64_t> g_render_translation_hits{0};
const void *g_create_dynamic_font_info = nullptr;
const void *g_create_tmp_font_info = nullptr;
const void *g_tmp_set_font_info = nullptr;
const void *g_tmp_get_font_info = nullptr;
const void *g_get_fallback_fonts_info = nullptr;
const void *g_tmp_font_get_material_info = nullptr;
const void *g_tmp_get_font_material_info = nullptr;
const void *g_get_fallback_material_info = nullptr;
const void *g_retain_fallback_material_info = nullptr;
std::unordered_map<void *, void *> g_font_materials;
std::unordered_set<void *> g_converted_font_materials;
const void *g_tmp_get_shared_material_info = nullptr;
const void *g_tmp_set_shared_material_info = nullptr;
const void *g_tmp_get_line_spacing_info = nullptr;
const void *g_tmp_set_line_spacing_info = nullptr;
const void *g_tmp_get_font_size_info = nullptr;
const void *g_tmp_set_font_size_info = nullptr;
const void *g_tmp_get_font_size_min_info = nullptr;
const void *g_tmp_set_font_size_min_info = nullptr;
const void *g_tmp_get_font_size_max_info = nullptr;
const void *g_tmp_set_font_size_max_info = nullptr;
const void *g_tmp_get_auto_sizing_info = nullptr;
const void *g_tmp_set_auto_sizing_info = nullptr;
const void *g_tmp_get_word_wrapping_info = nullptr;
const void *g_tmp_set_word_wrapping_info = nullptr;
const void *g_tmp_get_overflow_mode_info = nullptr;
const void *g_tmp_set_overflow_mode_info = nullptr;
const void *g_tmp_get_rect_transform_info = nullptr;
const void *g_ui_get_font_info = nullptr;
const void *g_ui_set_font_info = nullptr;
const void *g_ui_get_line_spacing_info = nullptr;
const void *g_ui_set_line_spacing_info = nullptr;
const void *g_ui_get_font_size_info = nullptr;
const void *g_ui_set_font_size_info = nullptr;
const void *g_ui_get_resize_min_size_info = nullptr;
const void *g_ui_set_resize_min_size_info = nullptr;
const void *g_ui_get_resize_max_size_info = nullptr;
const void *g_ui_set_resize_max_size_info = nullptr;
const void *g_ui_get_resize_best_fit_info = nullptr;
const void *g_ui_set_resize_best_fit_info = nullptr;
const void *g_ui_get_horizontal_overflow_info = nullptr;
const void *g_ui_set_horizontal_overflow_info = nullptr;
const void *g_ui_get_vertical_overflow_info = nullptr;
const void *g_ui_set_vertical_overflow_info = nullptr;
const void *g_ui_get_rect_transform_info = nullptr;
const void *g_rect_transform_get_rect_info = nullptr;
const void *g_object_get_name_info = nullptr;
const void *g_component_get_transform_info = nullptr;
const void *g_transform_get_parent_info = nullptr;
const void *g_transform_get_child_info = nullptr;
const void *g_transform_get_child_count_info = nullptr;
const void *g_component_get_component_info = nullptr;
const void *g_behaviour_get_active_info = nullptr;
const void *g_component_get_game_object_info = nullptr;
const void *g_game_object_get_active_info = nullptr;
const void *g_tmp_runtime_type = nullptr;
const void *g_material_get_float_info = nullptr;
const void *g_material_set_float_info = nullptr;
const void *g_material_get_color_info = nullptr;
const void *g_material_set_color_info = nullptr;
il2cpp_init_fn g_original_il2cpp_init = nullptr;
dlsym_fn g_original_unity_dlsym = nullptr;
get_language_fn g_original_get_language = nullptr;
get_language_fn g_original_global_get_language = nullptr;
class_get_parent_fn g_class_get_parent = nullptr;
class_get_fields_fn g_class_get_fields = nullptr;
field_get_name_fn g_field_get_name = nullptr;
field_get_type_fn g_field_get_type = nullptr;
field_get_value_fn g_field_get_value = nullptr;
field_set_value_fn g_field_set_value = nullptr;
typedef char *(*type_get_name_owned_fn)(const void *);
typedef void (*il2cpp_free_owned_fn)(void *);
type_get_name_owned_fn g_type_get_name = nullptr;
il2cpp_free_owned_fn g_il2cpp_free = nullptr;
std::atomic<uint32_t> g_language_hook_hits{0};
std::atomic<uint64_t> g_acquisition_hits{0};
std::atomic<uint64_t> g_nested_list_hits{0};
std::atomic<uint64_t> g_tmp_primary_font_switches{0};
std::atomic<uint64_t> g_ui_primary_font_switches{0};
std::atomic<uint64_t> g_layout_decisions{0};

bool append_utf8(uint32_t codepoint, std::string *output) {
    if (codepoint <= 0x7f) {
        output->push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
        output->push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
        output->push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0xffff) {
        output->push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
        output->push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        output->push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0x10ffff) {
        output->push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
        output->push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
        output->push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        output->push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else {
        return false;
    }
    return true;
}

bool utf16_to_utf8(const uint16_t *input, int32_t length, std::string *output) {
    if (input == nullptr || length < 0) return false;
    output->clear();
    output->reserve(static_cast<size_t>(length));
    for (int32_t i = 0; i < length; ++i) {
        uint32_t codepoint = input[i];
        if (codepoint >= 0xd800 && codepoint <= 0xdbff) {
            if (++i >= length || input[i] < 0xdc00 || input[i] > 0xdfff) return false;
            codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (input[i] - 0xdc00);
        } else if (codepoint >= 0xdc00 && codepoint <= 0xdfff) {
            return false;
        }
        if (!append_utf8(codepoint, output)) return false;
    }
    return true;
}

bool utf8_to_utf16(const std::string &input, std::u16string *output) {
    output->clear();
    for (size_t i = 0; i < input.size();) {
        unsigned char first = static_cast<unsigned char>(input[i++]);
        uint32_t codepoint = 0;
        int continuation = 0;
        if (first <= 0x7f) codepoint = first;
        else if ((first & 0xe0) == 0xc0) { codepoint = first & 0x1f; continuation = 1; }
        else if ((first & 0xf0) == 0xe0) { codepoint = first & 0x0f; continuation = 2; }
        else if ((first & 0xf8) == 0xf0) { codepoint = first & 0x07; continuation = 3; }
        else return false;
        if (i + continuation > input.size()) return false;
        for (int j = 0; j < continuation; ++j) {
            unsigned char next = static_cast<unsigned char>(input[i++]);
            if ((next & 0xc0) != 0x80) return false;
            codepoint = (codepoint << 6) | (next & 0x3f);
        }
        if ((continuation == 1 && codepoint < 0x80) ||
            (continuation == 2 && codepoint < 0x800) ||
            (continuation == 3 && codepoint < 0x10000) ||
            codepoint > 0x10ffff || (codepoint >= 0xd800 && codepoint <= 0xdfff)) return false;
        if (codepoint <= 0xffff) {
            output->push_back(static_cast<char16_t>(codepoint));
        } else {
            codepoint -= 0x10000;
            output->push_back(static_cast<char16_t>(0xd800 + (codepoint >> 10)));
            output->push_back(static_cast<char16_t>(0xdc00 + (codepoint & 0x3ff)));
        }
    }
    return true;
}

bool is_usable_display_text(const std::string &value) {
    std::u16string utf16;
    if (value.empty() || !utf8_to_utf16(value, &utf16)) return false;
    for (char16_t character : utf16) {
        // U+FFFD 与非法控制字符都说明资源在进入索引前已经损坏。
        // 保持日文原文比继续渲染方框、不可见控制符或乱码更安全。
        if (character == 0xfffd ||
            (character < 0x20 && character != u'\n' && character != u'\r' &&
             character != u'\t') ||
            (character >= 0x7f && character <= 0x9f)) {
            return false;
        }
    }
    return true;
}

bool contains_japanese_kana(const std::string &value) {
    std::u16string utf16;
    if (!utf8_to_utf16(value, &utf16)) return false;
    return std::any_of(utf16.begin(), utf16.end(), [](char16_t character) {
        // 覆盖平假名、片假名、片假名扩展与半角片假名。日文汉字与中文共用码位，
        // 不能单凭汉字判定，否则会误伤“振動 -> 震颤”一类合法术语。
        return (character >= 0x3040 && character <= 0x30ff) ||
               (character >= 0x31f0 && character <= 0x31ff) ||
               (character >= 0xff66 && character <= 0xff9f);
    });
}

void *new_managed_string(const std::string &value) {
    std::u16string utf16;
    if (g_string_new_utf16 == nullptr || !utf8_to_utf16(value, &utf16)) return nullptr;
    return g_string_new_utf16(reinterpret_cast<const uint16_t *>(utf16.data()),
                              static_cast<int32_t>(utf16.size()));
}

void rebuild_term_trie() {
    g_term_trie.clear();
    g_term_trie.emplace_back();
    for (const auto &entry : g_terms) {
        if (!is_usable_display_text(entry.first) ||
            !is_usable_display_text(entry.second) ||
            limbus_font_proof::numeric_display(entry.first) ||
            entry.first == entry.second) {
            continue;
        }
        size_t node = 0;
        for (unsigned char byte : entry.first) {
            auto child = g_term_trie[node].children.find(byte);
            if (child == g_term_trie[node].children.end()) {
                size_t next = g_term_trie.size();
                g_term_trie[node].children[byte] = next;
                g_term_trie.emplace_back();
                node = next;
            } else {
                node = child->second;
            }
        }
        g_term_trie[node].source = &entry.first;
        g_term_trie[node].translation = &entry.second;
    }
}

bool translate_embedded_terms(const std::string &source, std::string *output) {
    if (g_term_trie.size() <= 1) return false;
    output->clear();
    output->reserve(source.size());
    bool changed = false;
    for (size_t offset = 0; offset < source.size();) {
        // TMP rich-text markup is control data, not display text.  In
        // particular, an ASCII term such as "or" must never rewrite the tail
        // of <color> into <col或>, which makes TMP display the broken tag.
        if (source[offset] == '<') {
            size_t tag_end = source.find('>', offset + 1);
            if (tag_end != std::string::npos) {
                output->append(source, offset, tag_end - offset + 1);
                offset = tag_end + 1;
                continue;
            }
        }
        size_t node = 0;
        size_t best_end = offset;
        const std::string *best_source = nullptr;
        const std::string *best = nullptr;
        for (size_t cursor = offset; cursor < source.size(); ++cursor) {
            unsigned char byte = static_cast<unsigned char>(source[cursor]);
            auto child = g_term_trie[node].children.find(byte);
            if (child == g_term_trie[node].children.end()) break;
            node = child->second;
            if (g_term_trie[node].translation != nullptr) {
                best_source = g_term_trie[node].source;
                best = g_term_trie[node].translation;
                best_end = cursor + 1;
            }
        }
        if (best != nullptr && best_source != nullptr) {
            // “呼吸 -> 呼吸法”“以上 -> 或以上”这类包内正式词条必须保留。
            // 若当前命中本来就位于完整译文内部，则跳过，保证对象获取层与 TMP 层
            // 多次经过同一字符串时不会不断追加前后缀。
            size_t translated_offset = best->find(*best_source);
            bool already_translated = false;
            while (translated_offset != std::string::npos) {
                if (offset >= translated_offset) {
                    size_t translated_start = offset - translated_offset;
                    if (source.compare(translated_start, best->size(), *best) == 0) {
                        already_translated = true;
                        break;
                    }
                }
                translated_offset = best->find(*best_source, translated_offset + 1);
            }
            if (already_translated) {
                best = nullptr;
                best_source = nullptr;
            }
        }
        if (best != nullptr) {
            bool ascii_word = true;
            for (size_t index = offset; index < best_end; ++index) {
                unsigned char byte = static_cast<unsigned char>(source[index]);
                if (!(byte >= '0' && byte <= '9') &&
                    !(byte >= 'A' && byte <= 'Z') &&
                    !(byte >= 'a' && byte <= 'z') && byte != '_') {
                    ascii_word = false;
                    break;
                }
            }
            auto is_ascii_word_byte = [](unsigned char byte) {
                return (byte >= '0' && byte <= '9') ||
                       (byte >= 'A' && byte <= 'Z') ||
                       (byte >= 'a' && byte <= 'z') || byte == '_';
            };
            if (ascii_word &&
                ((offset > 0 && is_ascii_word_byte(
                        static_cast<unsigned char>(source[offset - 1]))) ||
                 (best_end < source.size() && is_ascii_word_byte(
                        static_cast<unsigned char>(source[best_end]))))) {
                best = nullptr;
            }
        }
        if (best != nullptr) {
            output->append(*best);
            offset = best_end;
            changed = true;
        } else {
            output->push_back(source[offset++]);
        }
    }
    // 短术语只作为格式化后文本的补充通道。若替换后仍残留假名，说明当前文本
    // 只命中了局部词条；拒绝输出半中文半日文的碎片，等待完整映射或后续词表覆盖。
    if (changed && contains_japanese_kana(source) && contains_japanese_kana(*output)) {
        output->clear();
        return false;
    }
    return changed;
}

bool strip_line_height_tags(const std::string &source, std::string *output) {
    output->clear();
    output->reserve(source.size());
    bool changed = false;
    for (size_t offset = 0; offset < source.size();) {
        if (source.compare(offset, 13, "<line-height=") == 0) {
            size_t end = source.find('>', offset + 13);
            if (end != std::string::npos) {
                offset = end + 1;
                changed = true;
                continue;
            }
        }
        output->push_back(source[offset++]);
    }
    return changed;
}

std::string apply_dante_line_height(const std::string &translation) {
    std::string output;
    output.reserve(translation.size() + 64);
    for (size_t offset = 0; offset < translation.size(); ++offset) {
        if (translation[offset] != '\n') {
            output.push_back(translation[offset]);
            continue;
        }
        output += "<line-height=170%>\n";
        if (offset + 1 < translation.size() && translation[offset + 1] == '-') {
            output.push_back('-');
            output += "<line-height=100%>";
            ++offset;
        } else {
            output += "<line-height=100%>";
        }
    }
    return output;
}

std::string apply_uniform_line_height(const std::string &text, int32_t percentage) {
    std::string output;
    output.reserve(text.size() + 96);
    std::string line_height_tag = "<line-height=" + std::to_string(percentage) + "%>\n";
    for (char character : text) {
        if (character != '\n') {
            output.push_back(character);
            continue;
        }
        // TMP 在换行符前读取当前 line-height；换行后立即恢复 100%，避免标签
        // 继续影响同一组件之外的自动布局计算。
        output += line_height_tag;
        output += "<line-height=100%>";
    }
    return output;
}

float personality_title_character_width(uint32_t codepoint) {
    // 拉丁字母、数字与标点在当前中文字体中的实际宽度明显小于一个汉字；
    // 用近似字宽而不是字节数计算，避免把 LCA、E.G.O 等缩写过度拆行。
    if (codepoint == ' ') return 0.35f;
    if (codepoint <= 0x7f) return 0.55f;
    return 1.0f;
}

std::vector<std::string> split_utf8_codepoints(const std::string &text) {
    std::vector<std::string> codepoints;
    for (size_t offset = 0; offset < text.size();) {
        unsigned char lead = static_cast<unsigned char>(text[offset]);
        size_t length = 1;
        if ((lead & 0xe0) == 0xc0) length = 2;
        else if ((lead & 0xf0) == 0xe0) length = 3;
        else if ((lead & 0xf8) == 0xf0) length = 4;
        if (offset + length > text.size()) length = 1;
        codepoints.emplace_back(text.substr(offset, length));
        offset += length;
    }
    return codepoints;
}

uint32_t first_utf8_codepoint(const std::string &text) {
    if (text.empty()) return 0;
    const unsigned char *bytes = reinterpret_cast<const unsigned char *>(text.data());
    if ((bytes[0] & 0x80) == 0) return bytes[0];
    if ((bytes[0] & 0xe0) == 0xc0 && text.size() >= 2) {
        return ((bytes[0] & 0x1f) << 6) | (bytes[1] & 0x3f);
    }
    if ((bytes[0] & 0xf0) == 0xe0 && text.size() >= 3) {
        return ((bytes[0] & 0x0f) << 12) | ((bytes[1] & 0x3f) << 6) |
               (bytes[2] & 0x3f);
    }
    if ((bytes[0] & 0xf8) == 0xf0 && text.size() >= 4) {
        return ((bytes[0] & 0x07) << 18) | ((bytes[1] & 0x3f) << 12) |
               ((bytes[2] & 0x3f) << 6) | (bytes[3] & 0x3f);
    }
    return bytes[0];
}

std::vector<std::string> wrap_personality_title_line(const std::string &line,
                                                     float maximum_width) {
    float total_width = 0.0f;
    for (const std::string &codepoint : split_utf8_codepoints(line)) {
        total_width += personality_title_character_width(first_utf8_codepoint(codepoint));
    }
    // 汉化包分行拥有最高优先级：未超宽的原始行原样保留；超宽但没有空格的行
    // 也不按汉字硬拆，避免运行时自行改变译者确定的名称结构。
    if (total_width <= maximum_width || line.find(' ') == std::string::npos) {
        return {line};
    }
    std::vector<std::string> result;
    std::vector<std::string> codepoints = split_utf8_codepoints(line);
    size_t start = 0;
    while (start < codepoints.size()) {
        float width = 0.0f;
        size_t end = start;
        size_t last_space = std::string::npos;
        while (end < codepoints.size()) {
            uint32_t codepoint = first_utf8_codepoint(codepoints[end]);
            float next_width = personality_title_character_width(codepoint);
            if (end > start && width + next_width > maximum_width) break;
            width += next_width;
            if (codepoint == ' ') last_space = end;
            ++end;
        }
        if (end < codepoints.size() && last_space != std::string::npos && last_space > start) {
            end = last_space;
        } else if (end < codepoints.size()) {
            // 当前剩余片段找不到可用空格时保留整段，不在汉字中间制造新换行。
            end = codepoints.size();
        }
        std::string wrapped;
        for (size_t index = start; index < end; ++index) wrapped += codepoints[index];
        while (!wrapped.empty() && wrapped.back() == ' ') wrapped.pop_back();
        if (!wrapped.empty()) result.push_back(wrapped);
        start = end;
        while (start < codepoints.size() && codepoints[start] == " ") ++start;
    }
    if (result.empty()) result.push_back(line);
    return result;
}

std::string reflow_personality_title(const std::string &text, size_t *line_count) {
    std::vector<std::string> lines;
    size_t start = 0;
    while (start <= text.size()) {
        size_t end = text.find('\n', start);
        std::string line = text.substr(start, end == std::string::npos ?
                std::string::npos : end - start);
        // 230 宽、35 字号的人格卡可稳定容纳约 8.4 个等宽汉字；优先在包内空格处断行。
        std::vector<std::string> wrapped = wrap_personality_title_line(line, 8.4f);
        lines.insert(lines.end(), wrapped.begin(), wrapped.end());
        if (end == std::string::npos) break;
        start = end + 1;
    }
    if (line_count != nullptr) *line_count = lines.size();
    std::string output;
    for (size_t index = 0; index < lines.size(); ++index) {
        if (index > 0) output.push_back('\n');
        output += lines[index];
    }
    return output;
}

bool ensure_tmp_font();

void *translate_managed_string(void *managed_text, const std::string &stable_id = {},
                               const char *display_field = nullptr) {
    if (managed_text == nullptr || g_string_length == nullptr || g_string_chars == nullptr) {
        return managed_text;
    }
    int32_t length = g_string_length(managed_text);
    std::string source;
    if (length < 0 || length > 1024 * 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &source)) {
        return managed_text;
    }
    // A previously produced translation can cross multiple setters and render
    // passes. Preserve that exact result instead of applying another term pass.
    pthread_mutex_lock(&g_font_state_lock);
    bool already_translated = g_translated_objects.find(managed_text) != nullptr;
    pthread_mutex_unlock(&g_font_state_lock);
    if (already_translated) return managed_text;
    std::unordered_map<std::string, std::string>::const_iterator match = g_index.find(source);
    std::string translated;
    auto context = stable_id.empty() || display_field == nullptr ? g_contextual_index.end() :
            g_contextual_index.find(stable_id + '\x1f' + display_field + '\x1f' + source);
    // Copies of released Chinese strings are stable independent of lookup order.
    // Compiler contexts disambiguate original JP fields that share those bytes.
    if (context == g_contextual_index.end()) {
        pthread_mutex_lock(&g_font_state_lock);
        bool released_output = g_font_catalogue.contains(source);
        pthread_mutex_unlock(&g_font_state_lock);
        if (released_output) return managed_text;
    }
    // An unscoped numeric UI value is data, not a global glossary label. In
    // particular, a floor-name rule must never turn a timer's :30 into :30层.
    if (context == g_contextual_index.end() && limbus_font_proof::numeric_display(source)) return managed_text;
    if (context != g_contextual_index.end()) {
        translated = context->second;
        uint64_t hits = g_contextual_hits.fetch_add(1, std::memory_order_relaxed) + 1;
        if (hits == 1 || hits % 100 == 0) {
            LT_LOGI("Contextual translation hits=%llu", static_cast<unsigned long long>(hits));
        }
    } else if (match != g_index.end()) {
        translated = match->second;
    } else {
        std::string normalized;
        bool line_height_normalized = strip_line_height_tags(source, &normalized);
        if (line_height_normalized) {
            match = g_index.find(normalized);
        }
        if (line_height_normalized && match != g_index.end()) {
            translated = apply_dante_line_height(match->second);
        } else if (!translate_embedded_terms(source, &translated)) {
            return managed_text;
        }
    }
    if (!is_usable_display_text(translated)) return managed_text;
    if (translated == source) return managed_text;
    if (!ensure_tmp_font()) return managed_text;
    void *replacement = new_managed_string(translated);
    if (replacement != nullptr) {
        pthread_mutex_lock(&g_font_state_lock);
        // 只登记接管层确实生成过的完整译文，不能按“含非 ASCII”泛化为中文主字体。
        bool retained = remember_translated_text(replacement, translated);
        pthread_mutex_unlock(&g_font_state_lock);
        if (!retained) return managed_text;
        uint64_t hits = g_acquisition_hits.fetch_add(1, std::memory_order_relaxed) + 1;
        if (hits == 1 || hits % 500 == 0) {
            LT_LOGI("Text acquisition translation hits=%llu",
                    static_cast<unsigned long long>(hits));
        }
    }
    return replacement == nullptr ? managed_text : replacement;
}

bool is_translated_managed_string(void *managed_text) {
    if (managed_text == nullptr || g_string_length == nullptr || g_string_chars == nullptr) {
        return false;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 1024 * 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text)) {
        return false;
    }
    pthread_mutex_lock(&g_font_state_lock);
    bool translated = g_translated_objects.find(managed_text) != nullptr ||
            g_recent_font_outputs.contains(text);
    if (!translated) translated = g_font_catalogue.contains(text);
    pthread_mutex_unlock(&g_font_state_lock);
    return translated;
}

bool is_display_string_field(const char *name) {
    if (name == nullptr) return false;
    static const char *const fields[] = {
            "content", "dialog", "dlg", "teller", "name", "nameWithTitle",
            "desc", "description", "title", "summary", "flavor", "place",
            "shortName", "abName", "simpleDesc", "successDesc", "failureDesc",
            "message", "messageDesc", "result", "text", "subText", "mainText",
            "rawDesc", "acquisitionMethod", "skinItemTitle", "skinItemDesc",
            "oneLineTitle", "nickName", "longName", "specialName",
            "abnormalityName", "panicName", "behaveDesc", "eventDesc", "prevDesc",
            "subDesc", "panicDescription", "lowMoraleDescription", "codeName", "clue",
            "sentence", "story", "openCondition", "openConditionNumber",
            "relatedChapterText", "askLevelUp", "company", "area", "chapter",
            "chapterNumber", "chaptertitle", "parttitle", "timeline", "teacher",
            "add", "min", "variation", "variation2", "speaker", "displayName", "statText",
            "goalDescription1", "goalDescription2", "goalDescription3",
            "goalDescription4", "goalDescription5", "goalDescription6",
            "goalDescription7", "goalDescription8"
    };
    for (const char *field : fields) {
        if (strcmp(name, field) == 0) return true;
    }
    return false;
}

// Read identity through IL2CPP metadata. No guessed offsets, resource-wide id
// replacement, or inherited JSON path: a match also requires field and full source.
std::string read_text_object_id(void *instance, const char *identity_name = "id") {
    for (void *klass = g_object_get_class(instance); klass != nullptr;
         klass = g_class_get_parent(klass)) {
        void *iterator = nullptr;
        const void *field = nullptr;
        while ((field = g_class_get_fields(klass, &iterator)) != nullptr) {
            const char *name = g_field_get_name(field);
            if (name == nullptr || strcmp(name, identity_name) != 0) continue;
            char *type = g_type_get_name(g_field_get_type(field));
            std::string id;
            if (type != nullptr && strcmp(type, "System.String") == 0) {
                void *value = nullptr;
                g_field_get_value(instance, field, &value);
                int32_t length = value == nullptr ? 0 : g_string_length(value);
                if (length > 0 && length <= 256) {
                    utf16_to_utf8(g_string_chars(value), length, &id);
                }
            } else if (type != nullptr && strcmp(type, "System.Int32") == 0) {
                int32_t value = 0;
                g_field_get_value(instance, field, &value);
                id = std::to_string(value);
            } else if (type != nullptr && strcmp(type, "System.Int64") == 0) {
                int64_t value = 0;
                g_field_get_value(instance, field, &value);
                id = std::to_string(value);
            }
            if (type != nullptr) g_il2cpp_free(type);
            if (std::any_of(id.begin(), id.end(), [](unsigned char c) { return c < 32; })) id.clear();
            return id;
        }
    }
    return {};
}

const void *find_collection_method(void *klass, const char *name,
                                   std::initializer_list<const char *> parameters) {
    if (g_class_get_methods == nullptr || g_method_get_name == nullptr ||
        g_method_get_param_count == nullptr || g_method_get_param == nullptr) return nullptr;
    for (; klass != nullptr; klass = g_class_get_parent(klass)) {
        void *iterator = nullptr;
        while (const void *method = g_class_get_methods(klass, &iterator)) {
            const char *actual_name = g_method_get_name(method);
            if (actual_name == nullptr || strcmp(actual_name, name) != 0 ||
                g_method_get_param_count(method) != parameters.size()) continue;
            bool matches = true;
            uint32_t index = 0;
            for (const char *expected : parameters) {
                char *actual = g_type_get_name(g_method_get_param(method, index++));
                matches &= actual != nullptr && strcmp(actual, expected) == 0;
                if (actual != nullptr) g_il2cpp_free(actual);
            }
            if (matches) return method;
        }
    }
    return nullptr;
}

struct StringCollectionMethods {
    const void *count = nullptr;
    const void *get = nullptr;
    const void *set = nullptr;
};
// Text-data formatters can also run on loading workers. Keep their two known
// collection types per thread rather than sharing a mutable metadata cache.
thread_local std::unordered_map<void *, StringCollectionMethods> g_string_collection_methods;
std::atomic<uint64_t> g_string_collection_hits{0};

void translate_display_string_collection(void *collection, bool array, const std::string &stable_id,
                                         const char *display_field, std::vector<std::string> *translated_fields) {
    if (collection == nullptr || g_runtime_invoke == nullptr || g_object_unbox == nullptr) return;
    void *klass = g_object_get_class(collection);
    auto found = g_string_collection_methods.find(klass);
    if (found == g_string_collection_methods.end()) {
        StringCollectionMethods methods;
        methods.count = find_collection_method(klass, array ? "get_Length" : "get_Count", {});
        methods.get = find_collection_method(klass, array ? "GetValue" : "get_Item", {"System.Int32"});
        methods.set = array ? find_collection_method(klass, "SetValue", {"System.Object", "System.Int32"}) :
                find_collection_method(klass, "set_Item", {"System.Int32", "System.String"});
        found = g_string_collection_methods.emplace(klass, methods).first;
    }
    const auto methods = found->second;
    if (methods.count == nullptr || methods.get == nullptr || methods.set == nullptr) return;
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(methods.count, collection, nullptr, &exception);
    int32_t *count_value = exception == nullptr && boxed != nullptr ?
            static_cast<int32_t *>(g_object_unbox(boxed)) : nullptr;
    const int32_t count = count_value == nullptr ? 0 : *count_value;
    if (count < 0 || count > 512) return;
    for (int32_t index = 0; index < count; ++index) {
        void *get_args[] = {&index};
        exception = nullptr;
        void *source = g_runtime_invoke(methods.get, collection, get_args, &exception);
        if (exception != nullptr || source == nullptr) continue;
        void *translated = translate_managed_string(source, stable_id, display_field);
        if (translated != source) {
            void *set_args[] = {array ? translated : static_cast<void *>(&index),
                               array ? static_cast<void *>(&index) : translated};
            g_runtime_invoke(methods.set, collection, set_args, &exception);
            if (exception != nullptr) continue;
            uint64_t hits = g_string_collection_hits.fetch_add(1, std::memory_order_relaxed) + 1;
            if (hits == 1 || hits % 250 == 0) LT_LOGI("Display string collection translations=%llu field=%s",
                    static_cast<unsigned long long>(hits), display_field);
        }
        if (translated_fields != nullptr && translated_fields->size() < 64 &&
            is_translated_managed_string(translated)) {
            int32_t length = g_string_length(translated);
            std::string text;
            if (length > 0 && length <= 4096 && utf16_to_utf8(g_string_chars(translated), length, &text))
                translated_fields->push_back(std::move(text));
        }
    }
}

void translate_object_string_fields(void *instance, bool story_dialog = false,
                                    std::vector<std::string> *translated_fields = nullptr) {
    if (instance == nullptr || g_object_get_class == nullptr || g_class_get_parent == nullptr ||
        g_class_get_fields == nullptr || g_field_get_name == nullptr ||
        g_field_get_type == nullptr || g_field_get_value == nullptr ||
        g_field_set_value == nullptr || g_type_get_name == nullptr || g_il2cpp_free == nullptr) {
        return;
    }
    std::string stable_id = g_contextual_index.empty() ? std::string() :
            read_text_object_id(instance, story_dialog ? "OriginalId" : "id");
    if (!story_dialog && stable_id.empty() && !g_contextual_index.empty()) {
        stable_id = read_text_object_id(instance, "key");
    }
    for (void *klass = g_object_get_class(instance); klass != nullptr;
         klass = g_class_get_parent(klass)) {
        void *iterator = nullptr;
        const void *field = nullptr;
        while ((field = g_class_get_fields(klass, &iterator)) != nullptr) {
            const char *field_name = g_field_get_name(field);
            const char *display_name = field_name;
            if (story_dialog) {
                display_name = field_name == nullptr ? nullptr :
                    strcmp(field_name, "Content") == 0 ? "content" :
                    strcmp(field_name, "Title") == 0 ? "title" :
                    strcmp(field_name, "Teller") == 0 ? "teller" :
                    strcmp(field_name, "Place") == 0 ? "place" : nullptr;
            }
            if (!is_display_string_field(display_name)) continue;
            char *type_name = g_type_get_name(g_field_get_type(field));
            bool is_string = type_name != nullptr && strcmp(type_name, "System.String") == 0;
            bool array_field = strcmp(display_name, "successDesc") == 0 ||
                    strcmp(display_name, "failureDesc") == 0 || strcmp(display_name, "result") == 0;
            bool is_array = type_name != nullptr && strcmp(type_name, "System.String[]") == 0;
            bool is_list = type_name != nullptr && (strcmp(type_name, "System.Collections.Generic.List<System.String>") == 0 ||
                    strcmp(type_name, "System.Collections.Generic.List`1<System.String>") == 0);
            if (type_name != nullptr) g_il2cpp_free(type_name);
            if (array_field && (is_array || is_list)) {
                void *collection = nullptr;
                g_field_get_value(instance, field, &collection);
                translate_display_string_collection(collection, is_array, stable_id, display_name, translated_fields);
                continue;
            }
            if (!is_string) continue;
            void *value = nullptr;
            g_field_get_value(instance, field, &value);
            void *replacement = translate_managed_string(value, stable_id, display_name);
            if (replacement != value) g_field_set_value(instance, field, replacement);
            if (translated_fields != nullptr && translated_fields->size() < 64 &&
                replacement != nullptr && is_translated_managed_string(replacement)) {
                int32_t length = g_string_length(replacement);
                std::string text;
                if (length > 0 && length <= 4096 &&
                    utf16_to_utf8(g_string_chars(replacement), length, &text)) {
                    translated_fields->push_back(std::move(text));
                }
            }
        }
    }
}

typedef void (*story_dialog_ctor_fn)(void *, int32_t, int32_t, void *, void *, float, const void *);
story_dialog_ctor_fn g_original_story_dialog_ctor = nullptr;
std::atomic<uint64_t> g_story_dialog_hits{0};

void replacement_story_dialog_ctor(void *instance, int32_t arg0, int32_t arg1,
                                   void *script, void *effects, float time, const void *method) {
    g_original_story_dialog_ctor(instance, arg0, arg1, script, effects, time, method);
    translate_object_string_fields(instance, true);
    uint64_t hits = g_story_dialog_hits.fetch_add(1, std::memory_order_relaxed) + 1;
    if (hits == 1 || hits % 100 == 0) {
        LT_LOGI("Story dialog acquisition hits=%llu", static_cast<unsigned long long>(hits));
    }
}

bool is_nested_text_field(const char *name) {
    if (name == nullptr) return false;
    static const char *const fields[] = {
            "levelList", "levellist", "coinList", "coinlist",
            "coinDescs", "coindescs", "options"
    };
    for (const char *field : fields) {
        if (strcmp(name, field) == 0) return true;
    }
    return false;
}

void translate_object_graph_impl(void *instance, int depth,
                                 std::unordered_set<void *> *visited,
                                 std::vector<std::string> *translated_fields) {
    if (instance == nullptr || depth > 4 || visited == nullptr ||
        !visited->insert(instance).second) {
        return;
    }
    translate_object_string_fields(instance, false, translated_fields);
    if (g_object_get_class == nullptr || g_class_get_parent == nullptr ||
        g_class_get_fields == nullptr || g_field_get_name == nullptr ||
        g_field_get_value == nullptr || g_class_get_method == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return;
    }
    for (void *klass = g_object_get_class(instance); klass != nullptr;
         klass = g_class_get_parent(klass)) {
        void *iterator = nullptr;
        const void *field = nullptr;
        while ((field = g_class_get_fields(klass, &iterator)) != nullptr) {
            const char *field_name = g_field_get_name(field);
            if (!is_nested_text_field(field_name)) continue;
            void *list = nullptr;
            g_field_get_value(instance, field, &list);
            void *list_class = list == nullptr ? nullptr : g_object_get_class(list);
            const void *count_method = list_class == nullptr ? nullptr :
                    g_class_get_method(list_class, "get_Count", 0);
            const void *item_method = list_class == nullptr ? nullptr :
                    g_class_get_method(list_class, "get_Item", 1);
            if (count_method == nullptr || item_method == nullptr) continue;
            void *exception = nullptr;
            void *boxed_count = g_runtime_invoke(count_method, list, nullptr, &exception);
            int32_t *count_value = exception == nullptr && boxed_count != nullptr ?
                    static_cast<int32_t *>(g_object_unbox(boxed_count)) : nullptr;
            int32_t count = count_value == nullptr ? 0 : *count_value;
            if (count < 0 || count > 512) continue;
            uint64_t nested_hits = g_nested_list_hits.fetch_add(1, std::memory_order_relaxed) + 1;
            if (nested_hits == 1 || nested_hits % 1000 == 0) {
                LT_LOGI("Nested text list traversal hits=%llu field=%s count=%d depth=%d",
                        static_cast<unsigned long long>(nested_hits), field_name, count, depth);
            }
            for (int32_t index = 0; index < count; ++index) {
                void *args[] = {&index};
                exception = nullptr;
                void *child = g_runtime_invoke(item_method, list, args, &exception);
                if (exception == nullptr && child != nullptr) {
                    translate_object_graph_impl(child, depth + 1, visited, translated_fields);
                }
            }
        }
    }
}

void translate_object_graph(void *instance, std::vector<std::string> *translated_fields) {
    std::unordered_set<void *> visited;
    translate_object_graph_impl(instance, 0, &visited, translated_fields);
}

void *finish_formatted_translation(void *result, const std::vector<std::string> &fields) {
    void *translated = translate_managed_string(result);
    if (translated == nullptr || fields.empty() || is_translated_managed_string(translated)) {
        return translated;
    }
    int32_t length = g_string_length(translated);
    std::string text;
    if (length <= 0 || length > 65536 ||
        !utf16_to_utf8(g_string_chars(translated), length, &text)) return translated;
    for (const std::string &field : fields) {
        if (limbus_text_provenance::contains_translated_field(text, field)) {
            pthread_mutex_lock(&g_font_state_lock);
            remember_translated_text(translated, text);
            pthread_mutex_unlock(&g_font_state_lock);
            break;
        }
    }
    return translated;
}

typedef void *(*acquisition0_fn)(void *, const void *);
typedef void *(*rpg_format_fn)(void *, void *, const void *);
rpg_format_fn g_original_rpg_format = nullptr;

void *replacement_rpg_format(void *format, void *arguments, const void *method) {
    void *translated = translate_managed_string(format);
    std::vector<std::string> fields;
    if (translated != nullptr && is_translated_managed_string(translated)) {
        int32_t length = g_string_length(translated);
        std::string text;
        if (length > 0 && length <= 4096 && utf16_to_utf8(g_string_chars(translated), length, &text)) {
            fields.push_back(std::move(text));
        }
    }
    return finish_formatted_translation(g_original_rpg_format(translated, arguments, method), fields);
}

typedef void *(*acquisition1_fn)(void *, void *, const void *);
typedef void *(*acquisition2_fn)(void *, void *, void *, const void *);
const size_t kAcquisitionSlots = 64;
acquisition0_fn g_acquisition0[kAcquisitionSlots] = {};
acquisition1_fn g_acquisition1[kAcquisitionSlots] = {};
acquisition2_fn g_acquisition2[kAcquisitionSlots] = {};

void *invoke_acquisition0(size_t slot, void *instance, const void *method) {
    std::vector<std::string> fields;
    translate_object_graph(instance, &fields);
    return finish_formatted_translation(g_acquisition0[slot](instance, method), fields);
}
void *invoke_acquisition1(size_t slot, void *instance, void *arg0, const void *method) {
    std::vector<std::string> fields;
    translate_object_graph(instance, &fields);
    return finish_formatted_translation(g_acquisition1[slot](instance, arg0, method), fields);
}
void *invoke_acquisition2(size_t slot, void *instance, void *arg0, void *arg1,
                          const void *method) {
    std::vector<std::string> fields;
    translate_object_graph(instance, &fields);
    return finish_formatted_translation(g_acquisition2[slot](instance, arg0, arg1, method), fields);
}

#define ACQUISITION_SLOTS(X) \
    X(0) X(1) X(2) X(3) X(4) X(5) X(6) X(7) \
    X(8) X(9) X(10) X(11) X(12) X(13) X(14) X(15) \
    X(16) X(17) X(18) X(19) X(20) X(21) X(22) X(23) \
    X(24) X(25) X(26) X(27) X(28) X(29) X(30) X(31) \
    X(32) X(33) X(34) X(35) X(36) X(37) X(38) X(39) \
    X(40) X(41) X(42) X(43) X(44) X(45) X(46) X(47) \
    X(48) X(49) X(50) X(51) X(52) X(53) X(54) X(55) \
    X(56) X(57) X(58) X(59) X(60) X(61) X(62) X(63)
#define DEFINE_ACQUISITION_WRAPPERS(N) \
    void *replacement_acquisition0_##N(void *i, const void *m) { \
        return invoke_acquisition0(N, i, m); \
    } \
    void *replacement_acquisition1_##N(void *i, void *a, const void *m) { \
        return invoke_acquisition1(N, i, a, m); \
    } \
    void *replacement_acquisition2_##N(void *i, void *a, void *b, const void *m) { \
        return invoke_acquisition2(N, i, a, b, m); \
    }
ACQUISITION_SLOTS(DEFINE_ACQUISITION_WRAPPERS)
#undef DEFINE_ACQUISITION_WRAPPERS
#define ACQUISITION0_ENTRY(N) replacement_acquisition0_##N,
#define ACQUISITION1_ENTRY(N) replacement_acquisition1_##N,
#define ACQUISITION2_ENTRY(N) replacement_acquisition2_##N,
acquisition0_fn const kAcquisition0Replacements[] = {ACQUISITION_SLOTS(ACQUISITION0_ENTRY)};
acquisition1_fn const kAcquisition1Replacements[] = {ACQUISITION_SLOTS(ACQUISITION1_ENTRY)};
acquisition2_fn const kAcquisition2Replacements[] = {ACQUISITION_SLOTS(ACQUISITION2_ENTRY)};
#undef ACQUISITION0_ENTRY
#undef ACQUISITION1_ENTRY
#undef ACQUISITION2_ENTRY
#undef ACQUISITION_SLOTS

bool ensure_tmp_font() {
    pthread_mutex_lock(&g_lock);
    // TextData formatters can also run on resource workers. Unity asset creation
    // belongs to the initialization/UI thread; an early worker may defer text,
    // but must never permanently poison font readiness with a wrong-thread call.
    if (!g_font_attempted && !pthread_equal(pthread_self(), g_unity_init_thread)) {
        pthread_mutex_unlock(&g_lock);
        return false;
    }
    if (!g_font_attempted) {
        g_font_attempted = true;
        void *probe = new_managed_string("BYBUS font ownership");
        auto first = limbus_managed::retain(g_gc, probe);
        auto weak = std::make_shared<limbus_managed::Reference>(g_gc, probe, true);
        auto second = limbus_managed::retain(g_gc, probe);
        const bool handles_valid = probe && first && second &&
                first->get() == probe && weak->get() == probe && second->get() == probe;
        LT_LOGI("Managed font ownership round-trip=%d handleBits=%zu", handles_valid, sizeof(limbus_managed::Handle) * 8);
        if (!handles_valid) {
            pthread_mutex_unlock(&g_lock);
            return false;
        }
        std::string font_file(g_active_pointer);
        size_t separator = font_file.find_last_of('/');
        if (separator != std::string::npos) font_file.resize(separator);
        separator = font_file.find_last_of('/');
        if (separator != std::string::npos) font_file.resize(separator);
        font_file += "/runtime-font/ChineseFont-a56a06f1.ttf";
        void *font_path = new_managed_string(font_file);
        int32_t face_index = 0;
        int32_t sampling_size = 32;
        int32_t atlas_padding = 9;
        int32_t render_mode = 4169;  // GlyphRenderMode.SDFAA.
        // The bundled Sarasa CJK face has substantially larger glyph contours
        // than the system fallback.  A 1024 atlas fills during the first skill
        // screen and TMP then drops glyphs that were already visible.
        int32_t atlas_width = 4096;
        int32_t atlas_height = 4096;
        int32_t population_mode = 1;  // AtlasPopulationMode.Dynamic.
        uint8_t multi_atlas = 1;
        void *asset_args[] = {
                font_path, &face_index, &sampling_size, &atlas_padding, &render_mode,
                &atlas_width, &atlas_height, &population_mode, &multi_atlas
        };
        void *exception = nullptr;
        if (font_path != nullptr && g_runtime_invoke != nullptr) {
            g_tmp_font_asset = g_runtime_invoke(
                    g_create_tmp_font_info, nullptr, asset_args, &exception);
        }
        if (exception != nullptr) g_tmp_font_asset = nullptr;
        g_tmp_font_root = limbus_managed::retain(g_gc, g_tmp_font_asset);
        if (!g_tmp_font_root) g_tmp_font_asset = nullptr;
        // This Android player does not create a dynamic legacy Font from a TTF
        // path. Preserve the verified CJK-capable system fallback for UI.Text.
        void *ui_exception = nullptr;
        void *ui_font_name = new_managed_string("sans-serif");
        int32_t ui_font_size = 32;
        void *ui_font_args[] = {ui_font_name, &ui_font_size};
        if (ui_font_name && g_create_dynamic_font_info) {
            g_ui_font = g_runtime_invoke(g_create_dynamic_font_info, nullptr, ui_font_args, &ui_exception);
            if (ui_exception) g_ui_font = nullptr;
            g_ui_font_root = limbus_managed::retain(g_gc, g_ui_font);
            if (!g_ui_font_root) g_ui_font = nullptr;
        }
        void *fallback_list = nullptr;
        void *add_exception = nullptr;
        if (g_tmp_font_asset != nullptr && exception == nullptr &&
            g_get_fallback_fonts_info != nullptr && g_object_get_class != nullptr) {
            fallback_list = g_runtime_invoke(
                    g_get_fallback_fonts_info, nullptr, nullptr, &add_exception);
            void *list_class = fallback_list == nullptr ? nullptr :
                    g_object_get_class(fallback_list);
            const void *add_method = list_class == nullptr || g_class_get_method == nullptr ? nullptr :
                    g_class_get_method(list_class, "Add", 1);
            void *add_args[] = {g_tmp_font_asset};
            if (add_method != nullptr && add_exception == nullptr) {
                g_runtime_invoke(add_method, fallback_list, add_args, &add_exception);
            }
            if (!add_method || add_exception) { g_tmp_font_asset = nullptr; g_tmp_font_root.reset(); }
            LT_LOGI("Registered TMP fallback font list=%p add=%p exception=%p",
                    fallback_list, add_method, add_exception);
        }
        LT_LOGI("Dynamic CJK TMP font creation path=%s face=%d tmpFont=%p exception=%p",
                font_file.c_str(), face_index,
                g_tmp_font_asset, exception);
        if (!g_get_fallback_fonts_info) { g_tmp_font_asset = nullptr; g_tmp_font_root.reset(); }
        LT_LOGI("Dynamic CJK UI font creation source=android-sans-serif uiFont=%p exception=%p",
                g_ui_font, ui_exception);
    }
    bool ready = g_tmp_font_asset != nullptr;
    pthread_mutex_unlock(&g_lock);
    return ready;
}

bool invoke_float_getter(const void *method_info, void *instance, float *value) {
    if (method_info == nullptr || instance == nullptr || value == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return false;
    }
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(method_info, instance, nullptr, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    memcpy(value, unboxed, sizeof(*value));
    return true;
}

void invoke_float_setter(const void *method_info, void *instance, float value) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return;
    void *args[] = {&value};
    void *exception = nullptr;
    g_runtime_invoke(method_info, instance, args, &exception);
}

bool invoke_bool_getter(const void *method_info, void *instance, bool *value) {
    if (method_info == nullptr || instance == nullptr || value == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return false;
    }
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(method_info, instance, nullptr, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    uint8_t raw = 0;
    memcpy(&raw, unboxed, sizeof(raw));
    *value = raw != 0;
    return true;
}

void invoke_bool_setter(const void *method_info, void *instance, bool value) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return;
    uint8_t raw = value ? 1 : 0;
    void *args[] = {&raw};
    void *exception = nullptr;
    g_runtime_invoke(method_info, instance, args, &exception);
}

bool invoke_int_getter(const void *method_info, void *instance, int32_t *value) {
    if (method_info == nullptr || instance == nullptr || value == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return false;
    }
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(method_info, instance, nullptr, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    memcpy(value, unboxed, sizeof(*value));
    return true;
}

void invoke_int_setter(const void *method_info, void *instance, int32_t value) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return;
    void *args[] = {&value};
    void *exception = nullptr;
    g_runtime_invoke(method_info, instance, args, &exception);
}

void *invoke_object_getter(const void *method_info, void *instance) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return nullptr;
    void *exception = nullptr;
    void *result = g_runtime_invoke(method_info, instance, nullptr, &exception);
    return exception == nullptr ? result : nullptr;
}

void invoke_object_setter(const void *method_info, void *instance, void *value) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return;
    void *args[] = {value};
    void *exception = nullptr;
    g_runtime_invoke(method_info, instance, args, &exception);
}

struct ComponentRect {
    float width = 0.0f;
    float height = 0.0f;
    bool valid = false;
};

ComponentRect read_component_rect(void *instance, const void *get_rect_transform_info) {
    ComponentRect result;
    if (instance == nullptr || get_rect_transform_info == nullptr ||
        g_rect_transform_get_rect_info == nullptr || g_runtime_invoke == nullptr ||
        g_object_unbox == nullptr) {
        return result;
    }
    void *rect_transform = invoke_object_getter(get_rect_transform_info, instance);
    if (rect_transform == nullptr) return result;
    void *exception = nullptr;
    void *boxed_rect = g_runtime_invoke(
            g_rect_transform_get_rect_info, rect_transform, nullptr, &exception);
    void *unboxed_rect = exception == nullptr && boxed_rect != nullptr ?
            g_object_unbox(boxed_rect) : nullptr;
    if (unboxed_rect == nullptr) return result;
    // UnityEngine.Rect 的顺序为 x、y、width、height；这里只读取排版需要的后两个值。
    float rect_values[4] = {};
    memcpy(rect_values, unboxed_rect, sizeof(rect_values));
    result.width = rect_values[2];
    result.height = rect_values[3];
    result.valid = result.width > 0.0f && result.height > 0.0f &&
            result.width < 100000.0f && result.height < 100000.0f;
    return result;
}

std::string read_unity_object_name(void *instance) {
    std::string name;
    void *managed_name = invoke_object_getter(g_object_get_name_info, instance);
    if (managed_name == nullptr || g_string_length == nullptr || g_string_chars == nullptr) {
        return name;
    }
    int32_t length = g_string_length(managed_name);
    if (length < 0 || length > 1024 ||
        !utf16_to_utf8(g_string_chars(managed_name), length, &name)) {
        name.clear();
    }
    return name;
}

std::string read_unity_parent_name(void *component) {
    void *transform = invoke_object_getter(g_component_get_transform_info, component);
    void *parent = invoke_object_getter(g_transform_get_parent_info, transform);
    return read_unity_object_name(parent);
}

float read_material_number(void *material, const char *property) {
    if (!material || !g_material_get_float_info || !g_object_unbox) return -999.0f;
    void *key = new_managed_string(property), *exception = nullptr;
    if (!key) return -999.0f;
    void *args[] = {key};
    void *boxed = g_runtime_invoke(g_material_get_float_info, material, args, &exception);
    void *raw = !exception && boxed ? g_object_unbox(boxed) : nullptr;
    float value = -999.0f;
    if (raw) memcpy(&value, raw, sizeof(value));
    return value;
}

// Only fixed UI glyphs survive; numeric values, tag arguments and other text
// (including possible user-generated names) are never written to diagnostics.
std::string popup_text_shape(const std::string &text) {
    const std::string allowed = "在后恢复脑啡肽制作模块要补充吗最大上限拥有使用狂气盒子取消确认。？：，";
    std::string shape;
    for (size_t i = 0; i < text.size() && shape.size() < 512;) {
        if (text[i] == '<') {
            auto end = text.find('>', i + 1);
            if (end != text.npos) {
                auto name_end = text.find_first_of("= >", i + 1);
                auto name = text.substr(i + 1, name_end - i - 1);
                auto bare = !name.empty() && name[0] == '/' ? name.substr(1) : name;
                bool known = bare == "font" || bare == "sprite" || bare == "nobr" ||
                        bare == "br" || bare == "noparse" || limbus_font_proof::decorative(name);
                shape += known ? "<" + name + ">" : "<?>";
                i = end + 1;
                continue;
            }
        }
        unsigned char c = text[i];
        size_t count = c < 128 ? 1 : ((c & 0xe0) == 0xc0 ? 2 : ((c & 0xf0) == 0xe0 ? 3 : 4));
        auto glyph = text.substr(i, count);
        if (c >= '0' && c <= '9') shape += '#';
        else if (c == ' ' || c == '\t' || c == '\n' || c == '\r') shape += '_';
        else if (c == ':' || c == '.' || c == '{' || c == '}' || c == '/') shape += c;
        else if (c >= 128 && allowed.find(glyph) != allowed.npos) shape += glyph;
        else shape += '?';
        i += count;
    }
    return shape;
}

bool is_translated_tmp_fragment(void *instance, void *managed_text, std::vector<void *> *literal_components = nullptr) {
    if (!instance || !managed_text || !g_string_length || !g_string_chars ||
        !g_transform_get_child_info || !g_transform_get_child_count_info ||
        !g_component_get_component_info || !g_behaviour_get_active_info || !g_tmp_runtime_type ||
        !g_component_get_game_object_info || !g_game_object_get_active_info) return false;
    auto length = g_string_length(managed_text);
    std::string text;
    if (length < 1 || length > 256 || !utf16_to_utf8(g_string_chars(managed_text), length, &text) ||
        !g_font_catalogue.fragment_candidate(text)) return false;
    static thread_local bool reading_group = false;
    if (reading_group) return false;
    struct Guard { bool &value; explicit Guard(bool &v) : value(v) { value = true; } ~Guard() { value = false; } } guard(reading_group);
    auto rejected = [&](const char *stage, int value) {
        if (text == "脑啡肽。") {
            auto row = std::string("fragment_group reject=") + stage + " value=" + std::to_string(value);
            static std::unordered_set<std::string> seen;
            if (seen.size() < 16 && seen.insert(row).second) append_native_metadata("font-state.log", row.c_str());
        }
        return false;
    };
    void *transform = invoke_object_getter(g_component_get_transform_info, instance);
    void *parent = invoke_object_getter(g_transform_get_parent_info, transform);
    int32_t count = 0;
    if (!parent || !invoke_int_getter(g_transform_get_child_count_info, parent, &count) || count < 3 || count > 17) return rejected("child_count", count);
    std::vector<std::string> parts;
    std::vector<void *> components;
    size_t target = 17;
    for (int32_t i = 0; i < count; ++i) {
        void *exception = nullptr;
        void *index_args[] = {&i};
        void *child = g_runtime_invoke(g_transform_get_child_info, parent, index_args, &exception);
        if (exception || !child) return rejected("child", i);
        void *game_object = invoke_object_getter(g_component_get_game_object_info, child);
        bool active_self = false;
        if (!game_object || !invoke_bool_getter(g_game_object_get_active_info, game_object, &active_self)) return rejected("active_self", i);
        // Ignore inactive pool templates, but allow a fully configured group
        // whose parent popup has not been enabled yet.
        if (!active_self) continue;
        void *type_args[] = {const_cast<void *>(g_tmp_runtime_type)};
        void *component = g_runtime_invoke(g_component_get_component_info, child, type_args, &exception);
        bool active = false;
        if (exception || !component) return rejected("text_component", i);
        if (!invoke_bool_getter(g_behaviour_get_active_info, component, &active)) return rejected("enabled", i);
        if (!active) continue;
        void *value = component == instance ? managed_text : invoke_object_getter(g_tmp_get_text_info, component);
        if (!value) return rejected("text", i);
        auto size = g_string_length(value);
        std::string part;
        if (size < 1 || size > 256 || !utf16_to_utf8(g_string_chars(value), size, &part)) return rejected("text_size", i);
        if (component == instance) target = parts.size();
        parts.push_back(std::move(part));
        components.push_back(component);
    }
    bool proven = g_font_catalogue.contains_fragments(parts, target);
    if (proven && literal_components) {
        for (size_t i = 0; i < parts.size(); i += 2) {
            if (g_font_catalogue.fragment_candidate(parts[i])) literal_components->push_back(components[i]);
        }
    }
    if (text == "脑啡肽。") {
        std::string row = "fragment_group count=" + std::to_string(count) + " match=" + (proven ? "1" : "0") + " parts=";
        for (const auto &part : parts) row += "[" + popup_text_shape(part) + "]";
        static std::string last;
        if (last != row) { last = row; append_native_metadata("font-state.log", row.c_str()); }
    }
    return proven;
}

bool is_announcer_description(void *instance) {
    int32_t expected = 0;
    pthread_mutex_lock(&g_font_state_lock);
    auto found = g_announcer_text_ids.find(instance);
    if (found != g_announcer_text_ids.end()) expected = found->second;
    pthread_mutex_unlock(&g_font_state_lock);
    int32_t current = 0;
    return expected && invoke_int_getter(g_object_get_instance_id_info, instance, &current) && current == expected;
}

bool read_flow_text(void *managed, std::string *text) {
    if (!managed || !g_string_length || !g_string_chars) return false;
    const auto length = g_string_length(managed);
    return length >= 0 && length <= 16384 &&
            utf16_to_utf8(g_string_chars(managed), length, text) && text->size() <= 16384;
}

bool is_choice_text(void *instance, void *managed = nullptr) {
    // Do not call a Unity API for every TMP component; only registered event
    // targets need the instance-ID check. Native addresses alone are not IDs.
    pthread_mutex_lock(&g_font_state_lock);
    const bool registered = g_choice_targets.count(instance) != 0;
    pthread_mutex_unlock(&g_font_state_lock);
    if (!registered) return false;
    int32_t id = 0;
    if (!invoke_int_getter(g_object_get_instance_id_info, instance, &id) || !id) return false;
    std::string text;
    if (managed && !read_flow_text(managed, &text)) return false;
    pthread_mutex_lock(&g_font_state_lock);
    auto found = g_choice_targets.find(instance);
    bool valid = found != g_choice_targets.end() && found->second.id == id &&
            (!managed || found->second.proof.matches(text));
    pthread_mutex_unlock(&g_font_state_lock);
    return valid;
}

void clear_choice_flow(void *owner) {
    pthread_mutex_lock(&g_font_state_lock);
    g_choice_owners.erase(owner);
    for (auto it = g_choice_targets.begin(); it != g_choice_targets.end();) {
        if (it->second.owner == owner) it = g_choice_targets.erase(it);
        else ++it;
    }
    pthread_mutex_unlock(&g_font_state_lock);
}

bool read_choice_list(void *list, std::string *complete, bool *verified) {
    if (!list || !g_object_get_class || !g_choice_result_log_class || !g_choice_result_log_field ||
        !g_field_get_value || !g_field_set_value) return false;
    void *klass = g_object_get_class(list);
    const void *count_method = find_collection_method(klass, "get_Count", {});
    const void *get_method = find_collection_method(klass, "get_Item", {"System.Int32"});
    int32_t count = 0;
    if (!get_method || !invoke_int_getter(count_method, list, &count) || count < 1 || count > 128) return false;
    *verified = true;
    const bool font_ready = ensure_tmp_font();
    for (int32_t i = 0; i < count; ++i) {
        void *args[] = {&i}, *exception = nullptr;
        void *log = g_runtime_invoke(get_method, list, args, &exception);
        if (exception || !log || g_object_get_class(log) != g_choice_result_log_class) return false;
        void *part = nullptr;
        g_field_get_value(log, g_choice_result_log_field, &part);
        // A reward-only log may have no text. Its reward identifier is outside
        // this display pass; continue validating the remaining textual logs.
        if (!part) continue;
        void *translated = font_ready ? translate_managed_string(part) : part;
        if (translated != part) g_field_set_value(log, g_choice_result_log_field, translated);
        part = translated;
        std::string text;
        if (exception || !read_flow_text(part, &text)) return false;
        if (i) *complete += '\n';
        *complete += text;
        if (complete->size() > 16384) return false;
        if (!limbus_font_proof::canonical(text).empty())
            *verified &= is_translated_managed_string(part);
    }
    if (!*verified) {
        pthread_mutex_lock(&g_font_state_lock);
        *verified = g_font_catalogue.contains(*complete) || g_recent_font_outputs.contains(*complete);
        pthread_mutex_unlock(&g_font_state_lock);
    }
    return true;
}

void bind_choice_target(void *owner, void *target, const std::string &text, bool verified) {
    int32_t owner_id = 0, target_id = 0;
    if (!owner || !target || !invoke_int_getter(g_object_get_instance_id_info, owner, &owner_id) ||
        !invoke_int_getter(g_object_get_instance_id_info, target, &target_id) || !owner_id || !target_id) return;
    ChoiceFlowState state;
    state.id = target_id;
    state.owner = owner;
    state.owner_id = owner_id;
    pthread_mutex_lock(&g_font_state_lock);
    auto found = g_choice_owners.find(owner);
    if (found != g_choice_owners.end() && found->second.id == owner_id && found->second.proof.matches(text))
        state.proof = found->second.proof;
    else state.proof.assign(text, verified);
    if (g_choice_targets.size() >= 32 && !g_choice_targets.count(target))
        g_choice_targets.erase(g_choice_targets.begin());
    g_choice_targets[target] = std::move(state);
    pthread_mutex_unlock(&g_font_state_lock);
}

void replacement_choice_set_total(void *instance, void *list, const void *method) {
    // Only the exact ChoiceEventResultLog display field is touched. Reward IDs
    // and the list's ordering/identity remain owned by the game.
    std::string text;
    bool verified = false;
    int32_t id = 0;
    const bool readable = read_choice_list(list, &text, &verified);
    if (invoke_int_getter(g_object_get_instance_id_info, instance, &id) && id) {
        ChoiceFlowState state;
        state.id = id;
        state.proof.assign(text, readable && verified);
        pthread_mutex_lock(&g_font_state_lock);
        // Coroutine factories can bind their target before SetTotalContent
        // executes. Refresh those bindings instead of discarding their role.
        for (auto it = g_choice_targets.begin(); it != g_choice_targets.end();) {
            if (it->second.owner != instance) { ++it; continue; }
            if (it->second.owner_id != id) it = g_choice_targets.erase(it);
            else { it->second.proof = state.proof; ++it; }
        }
        if (g_choice_owners.size() >= 32 && !g_choice_owners.count(instance))
            g_choice_owners.erase(g_choice_owners.begin());
        g_choice_owners[instance] = std::move(state);
        pthread_mutex_unlock(&g_font_state_lock);
    } else clear_choice_flow(instance);
    g_original_choice_set_total(instance, list, method);
}

void *replacement_choice_read_string(void *instance, void *target, void *source, const void *method) {
    void *translated = translate_managed_string(source);
    if (translated != source && !ensure_tmp_font()) translated = source;
    std::string text;
    read_flow_text(translated, &text);
    bind_choice_target(instance, target, text, is_translated_managed_string(translated));
    return g_original_choice_read_string(instance, target, translated, method);
}

void *replacement_choice_read_list(void *instance, void *target, void *list, const void *method) {
    std::string text;
    bool verified = false;
    if (!read_choice_list(list, &text, &verified)) { text.clear(); verified = false; }
    bind_choice_target(instance, target, text, verified);
    return g_original_choice_read_list(instance, target, list, method);
}

void replacement_choice_button(void *instance, int32_t selection, void *message,
                                void *result_description, const void *method) {
    void *translated = translate_managed_string(message);
    void *translated_result = translate_managed_string(result_description);
    if ((translated != message || translated_result != result_description) && !ensure_tmp_font()) {
        translated = message;
        translated_result = result_description;
    }
    void *button = nullptr, *description = nullptr;
    g_field_get_value(instance, g_choice_button_text_field, &button);
    g_field_get_value(instance, g_choice_button_result_field, &description);
    std::string text, result;
    read_flow_text(translated, &text);
    bind_choice_target(instance, button, text, is_translated_managed_string(translated));
    read_flow_text(translated_result, &result);
    bind_choice_target(instance, description, result, is_translated_managed_string(translated_result));
    g_original_choice_button(instance, selection, translated, translated_result, method);
}

void replacement_choice_clear(void *instance, const void *method) {
    clear_choice_flow(instance);
    g_original_choice_clear(instance, method);
}

// Observation only: these labels select metadata; they do not grant font provenance.
void observe_popup_font(void *instance, void *managed_text, bool legacy) {
    if (!instance || !managed_text || !g_string_length || !g_string_chars || !g_class_get_method) return;
    const auto length = g_string_length(managed_text);
    std::string text;
    const bool choice_text = !legacy && is_choice_text(instance);
    if (length < 0 || length > (choice_text ? 16384 : 512) ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text)) return;
    int kind = 0;
    if (text.find("恢复") != text.npos && text.find("脑啡肽") != text.npos) kind = 1;
    else if (text.find("脑啡肽") != text.npos) kind = 2;
    else if (text.find("拥有") != text.npos) kind = 3;
    else if (text == "制作模块" || text == "使用狂气" || text == "使用盒子") kind = 4;
    else if (text == "取消" || text == "确认") kind = 5;
    else if (text == "在" || text == "后恢复") kind = 6;
    else if (text.find("■■■■■") != text.npos) kind = 7;
    else if (text == "实力" || text == "手" || text == "红派" || text == "瓦吉特" ||
             text == "指令" || text == "哔哔大人" || text == "火光" ||
             text == "学习" || text == "雨声") kind = 8;
    else if (text.find("镜") != text.npos || text.find("迷宫") != text.npos ||
             text.find("星芒") != text.npos || text.find("恩惠") != text.npos ||
             text.find("饰品") != text.npos || text.find("投影") != text.npos ||
             text.find("每周") != text.npos) kind = 9;
    else if (!legacy && is_announcer_description(instance)) kind = 10;
    if (choice_text) kind = 11;
    if (!kind) return;
    const auto shape = popup_text_shape(text);
    const bool proof = is_translated_managed_string(managed_text) ||
            (choice_text && is_choice_text(instance, managed_text)) ||
            (!legacy && is_translated_tmp_fragment(instance, managed_text));
    struct Sample { uint64_t next_ms = 0; std::string input; std::string last; };
    static std::unordered_map<void *, Sample> samples;
    if (samples.size() >= 128 && !samples.count(instance)) samples.erase(samples.begin());
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    uint64_t millis = now.tv_sec * 1000ull + now.tv_nsec / 1000000;
    auto &sample = samples[instance];
    const auto input = shape + (proof ? "+" : "-");
    // Event typing changes its input every frame. Keep metadata sampling off
    // that hot path even when the next visible character changes the shape.
    if (millis < sample.next_ms && (choice_text || sample.input == input)) return;
    sample.input = input;
    sample.next_ms = millis + 5000;
    void *font = invoke_object_getter(legacy ? g_ui_get_font_info : g_tmp_get_font_info, instance);
    void *material = legacy ? nullptr : invoke_object_getter(g_tmp_get_shared_material_info, instance);
    auto font_name = read_unity_object_name(font);
    font_name.resize(std::min<size_t>(font_name.size(), 96));
    for (auto &c : font_name) if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
            (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.')) c = '_';
    void *klass = g_object_get_class(instance);
    int32_t style = -1, weight = -1;
    invoke_int_getter(g_class_get_method(klass, "get_fontStyle", 0), instance, &style);
    if (!legacy) invoke_int_getter(g_class_get_method(klass, "get_fontWeight", 0), instance, &weight);
    int32_t overflow = -1;
    bool wrapping = false;
    if (!legacy) {
        invoke_int_getter(g_tmp_get_overflow_mode_info, instance, &overflow);
        invoke_bool_getter(g_tmp_get_word_wrapping_info, instance, &wrapping);
    }
    ComponentRect rect = read_component_rect(instance, legacy ? g_ui_get_rect_transform_info : g_tmp_get_rect_transform_info);
    uint64_t text_hash = 14695981039346656037ull;
    for (unsigned char c : text) text_hash = (text_hash ^ c) * 1099511628211ull;
    char row[2048];
    snprintf(row, sizeof(row), "kind=%d type=%s component=%p proof=%d primary=%d font=%s style=%d weight=%d material=%p converted=%d dilate=%.4f normal=%.4f bold=%.4f outline=%.4f scale=%.4f gradient=%.4f tags_font=%d tags_sprite=%d wrap=%d overflow=%d rect=%.1fx%.1f breaks=%zu text_hash=%016llx shape=%s",
            kind, legacy ? "ui" : "tmp", instance, proof,
            font && font == (legacy ? g_ui_font : g_tmp_font_asset), font_name.c_str(), style, weight,
            material, g_converted_font_materials.count(material) != 0,
            read_material_number(material, "_FaceDilate"), read_material_number(material, "_WeightNormal"),
            read_material_number(material, "_WeightBold"), read_material_number(material, "_OutlineWidth"),
            read_material_number(material, "_ScaleRatioA"), read_material_number(material, "_GradientScale"),
            text.find("<font") != text.npos, text.find("<sprite") != text.npos,
            wrapping, overflow, rect.width, rect.height,
            static_cast<size_t>(std::count(text.begin(), text.end(), '\n')),
            static_cast<unsigned long long>(text_hash), shape.c_str());
    if (sample.last == row) return;
    sample.last = row;
    append_native_metadata("font-state.log", row);
}

struct MaterialOutlineStyle {
    float width = 0.0f;
    float color[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    bool has_width = false;
    bool has_color = false;
};

MaterialOutlineStyle read_material_outline(void *material) {
    MaterialOutlineStyle style;
    if (material == nullptr || g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return style;
    }
    void *width_name = new_managed_string("_OutlineWidth");
    void *color_name = new_managed_string("_OutlineColor");
    if (width_name != nullptr && g_material_get_float_info != nullptr) {
        void *args[] = {width_name};
        void *exception = nullptr;
        void *boxed = g_runtime_invoke(g_material_get_float_info, material, args, &exception);
        void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
        if (unboxed != nullptr) {
            memcpy(&style.width, unboxed, sizeof(style.width));
            style.has_width = true;
        }
    }
    if (color_name != nullptr && g_material_get_color_info != nullptr) {
        void *args[] = {color_name};
        void *exception = nullptr;
        void *boxed = g_runtime_invoke(g_material_get_color_info, material, args, &exception);
        void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
        if (unboxed != nullptr) {
            memcpy(style.color, unboxed, sizeof(style.color));
            style.has_color = true;
        }
    }
    return style;
}

void apply_material_outline(void *material, const MaterialOutlineStyle &style) {
    if (material == nullptr || !style.has_width || style.width <= 0.0001f ||
        g_runtime_invoke == nullptr) {
        return;
    }
    void *width_name = new_managed_string("_OutlineWidth");
    void *color_name = new_managed_string("_OutlineColor");
    if (width_name != nullptr && g_material_set_float_info != nullptr) {
        float width = style.width;
        void *args[] = {width_name, &width};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_float_info, material, args, &exception);
    }
    if (color_name != nullptr && g_material_set_color_info != nullptr) {
        // 拼点/技能组件原材质已有描边宽度时，中文材质继承宽度并统一为不透明黑边。
        // 这样既保留游戏层级感，也不会给原本没有描边的普通正文强行加边。
        float black[4] = {0.0f, 0.0f, 0.0f,
                          style.has_color ? std::max(style.color[3], 0.95f) : 1.0f};
        void *args[] = {color_name, black};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_color_info, material, args, &exception);
    }
}

bool unity_object_alive(void *instance) {
    if (!instance || !g_object_is_alive_info) return false;
    void *args[] = {instance}, *exception = nullptr;
    void *boxed = g_runtime_invoke(g_object_is_alive_info, nullptr, args, &exception);
    return !exception && boxed && *static_cast<uint8_t *>(g_object_unbox(boxed));
}

int32_t component_id(void *instance) {
    int32_t id = 0;
    if (!invoke_int_getter(g_object_get_instance_id_info, instance, &id)) return 0;
    return id;
}

bool capture_tmp_component_state(void *instance, void *font, TmpComponentState *state) {
    int32_t id = component_id(instance);
    if (!id || !g_object_is_alive_info) return false;
    TmpComponentState candidate;
    candidate.font = font;
    candidate.font_root = limbus_managed::retain(g_gc, font);
    if (font && !candidate.font_root) return false;
    candidate.shared_material = invoke_object_getter(g_tmp_get_shared_material_info, instance);
    candidate.material_root = limbus_managed::retain(g_gc, candidate.shared_material);
    if (candidate.shared_material && !candidate.material_root) return false;
    candidate.has_line_spacing = invoke_float_getter(
            g_tmp_get_line_spacing_info, instance, &candidate.line_spacing);
    candidate.has_font_size = invoke_float_getter(
            g_tmp_get_font_size_info, instance, &candidate.font_size);
    candidate.has_font_size_min = invoke_float_getter(
            g_tmp_get_font_size_min_info, instance, &candidate.font_size_min);
    candidate.has_font_size_max = invoke_float_getter(
            g_tmp_get_font_size_max_info, instance, &candidate.font_size_max);
    candidate.has_auto_sizing = invoke_bool_getter(
            g_tmp_get_auto_sizing_info, instance, &candidate.auto_sizing);
    candidate.has_word_wrapping = invoke_bool_getter(
            g_tmp_get_word_wrapping_info, instance, &candidate.word_wrapping);
    candidate.has_overflow_mode = invoke_int_getter(
            g_tmp_get_overflow_mode_info, instance, &candidate.overflow_mode);
    pthread_mutex_lock(&g_font_state_lock);
    bool saved = g_original_tmp_states.put(instance, id, candidate);
    pthread_mutex_unlock(&g_font_state_lock);
    if (saved && state) *state = candidate;
    return saved;
}

bool find_tmp_component_state(void *instance, TmpComponentState *state) {
    int32_t id = component_id(instance);
    if (!id) return false;
    pthread_mutex_lock(&g_font_state_lock);
    // Also collect when a page reuses existing components without new captures.
    static uint32_t reads = 0;
    if ((++reads & 63) == 0) g_original_tmp_states.collect(16);
    auto saved = g_original_tmp_states.find(instance, id);
    bool found = saved != nullptr;
    if (found && state != nullptr) *state = *saved;
    pthread_mutex_unlock(&g_font_state_lock);
    return found;
}

void *translated_tmp_material(void *instance, void *requested_material) {
    TmpComponentState state;
    if (instance == nullptr || g_tmp_get_font == nullptr ||
        g_tmp_get_font(instance, g_tmp_get_font_info) != g_tmp_font_asset) {
        return requested_material;
    }
    // 游戏会在页签选择、E.G.O 等级切换和角色刷新时重新写入原日文字体材质。
    // 材质仍指向旧图集时，即使文本和中文字体正确，也会随机显示成黑块、黄块或碎字。
    void *font_material = invoke_object_getter(
            g_tmp_font_get_material_info, g_tmp_font_asset);
    if (font_material == nullptr) return requested_material;
    // Losing restoration metadata must never bind the Japanese atlas to a
    // Chinese font. The matching default is safer than corrupt glyph sampling.
    if (!find_tmp_component_state(instance, &state)) return font_material;
    if (requested_material == nullptr || requested_material == font_material) return font_material;
    if (g_converted_font_materials.count(requested_material)) return requested_material;
    // Keep the latest game preset for recycled components, including selection
    // and disabled states. TMP's material manager copies style but replaces the
    // atlas and its metrics, preventing a Japanese atlas or lost outline.
    int32_t id = component_id(instance);
    auto material_root = limbus_managed::retain(g_gc, requested_material);
    if (!material_root) return font_material;
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_tmp_states.find(instance, id);
    if (saved) { saved->shared_material = requested_material; saved->material_root = material_root; }
    pthread_mutex_unlock(&g_font_state_lock);
    auto cached = g_font_materials.find(requested_material);
    if (g_get_fallback_material_info == nullptr || g_retain_fallback_material_info == nullptr ||
        (cached == g_font_materials.end() && g_converted_font_materials.size() >= 128)) return font_material;
    void *args[] = {requested_material, font_material};
    void *exception = nullptr;
    // Even a known source preset may have changed in place. Let TMP refresh its
    // own cached conversion instead of returning our previous material blindly.
    void *converted = g_runtime_invoke(g_get_fallback_material_info, nullptr, args, &exception);
    if (exception != nullptr || converted == nullptr) return font_material;
    if (g_converted_font_materials.count(converted)) return converted;
    if (g_converted_font_materials.size() >= 128) return font_material;
    void *retain_args[] = {converted};
    g_runtime_invoke(g_retain_fallback_material_info, nullptr, retain_args, &exception);
    if (exception != nullptr) return font_material;
    // At most 128 TMP-owned materials are retained for this guest process.
    g_font_materials[requested_material] = converted;
    g_converted_font_materials.insert(converted);
    return converted;
}

void replacement_tmp_set_shared_material(void *instance, void *material, const void *method) {
    g_original_tmp_set_shared_material(
            instance, translated_tmp_material(instance, material), method);
}

void replacement_tmp_set_material(void *instance, void *material, const void *method) {
    g_original_tmp_set_material(
            instance, translated_tmp_material(instance, material), method);
}

void restore_tmp_component_state(void *instance) {
    TmpComponentState state;
    int32_t id = component_id(instance);
    if (!id) return;
    pthread_mutex_lock(&g_font_state_lock);
    bool restore = g_original_tmp_states.take(instance, id, &state);
    pthread_mutex_unlock(&g_font_state_lock);
    if (!restore) return;
    if (g_tmp_get_font == nullptr ||
        g_tmp_get_font(instance, g_tmp_get_font_info) != g_tmp_font_asset) return;
    // 组件会被列表复用；必须恢复字体、材质与全部排版约束，避免中文缩放泄漏到日文页面。
    if (g_tmp_set_font != nullptr) g_tmp_set_font(instance, state.font, g_tmp_set_font_info);
    if (state.shared_material != nullptr) {
        invoke_object_setter(g_tmp_set_shared_material_info, instance, state.shared_material);
    }
    if (state.has_line_spacing) {
        invoke_float_setter(g_tmp_set_line_spacing_info, instance, state.line_spacing);
    }
    if (state.has_auto_sizing) {
        invoke_bool_setter(g_tmp_set_auto_sizing_info, instance, state.auto_sizing);
    }
    if (state.has_word_wrapping) {
        invoke_bool_setter(g_tmp_set_word_wrapping_info, instance, state.word_wrapping);
    }
    if (state.has_overflow_mode) {
        invoke_int_setter(g_tmp_set_overflow_mode_info, instance, state.overflow_mode);
    }
    if (state.has_font_size_min) {
        invoke_float_setter(g_tmp_set_font_size_min_info, instance, state.font_size_min);
    }
    if (state.has_font_size_max) {
        invoke_float_setter(g_tmp_set_font_size_max_info, instance, state.font_size_max);
    }
    if (state.has_font_size) {
        invoke_float_setter(g_tmp_set_font_size_info, instance, state.font_size);
    }
}

bool capture_ui_component_state(void *instance) {
    int32_t id = component_id(instance);
    if (!id || !g_object_is_alive_info) return false;
    UiComponentState candidate;
    candidate.font = invoke_object_getter(g_ui_get_font_info, instance);
    candidate.font_root = limbus_managed::retain(g_gc, candidate.font);
    if (candidate.font && !candidate.font_root) return false;
    candidate.has_line_spacing = invoke_float_getter(
            g_ui_get_line_spacing_info, instance, &candidate.line_spacing);
    candidate.has_font_size = invoke_int_getter(
            g_ui_get_font_size_info, instance, &candidate.font_size);
    candidate.has_resize_min_size = invoke_int_getter(
            g_ui_get_resize_min_size_info, instance, &candidate.resize_min_size);
    candidate.has_resize_max_size = invoke_int_getter(
            g_ui_get_resize_max_size_info, instance, &candidate.resize_max_size);
    candidate.has_resize_best_fit = invoke_bool_getter(
            g_ui_get_resize_best_fit_info, instance, &candidate.resize_best_fit);
    candidate.has_horizontal_overflow = invoke_int_getter(
            g_ui_get_horizontal_overflow_info, instance, &candidate.horizontal_overflow);
    candidate.has_vertical_overflow = invoke_int_getter(
            g_ui_get_vertical_overflow_info, instance, &candidate.vertical_overflow);
    pthread_mutex_lock(&g_font_state_lock);
    bool saved = g_original_ui_states.put(instance, id, candidate);
    pthread_mutex_unlock(&g_font_state_lock);
    return saved;
}

bool find_ui_component_state(void *instance, UiComponentState *state) {
    int32_t id = component_id(instance);
    if (!id) return false;
    pthread_mutex_lock(&g_font_state_lock);
    static uint32_t reads = 0;
    if ((++reads & 63) == 0) g_original_ui_states.collect(16);
    auto saved = g_original_ui_states.find(instance, id);
    bool found = saved != nullptr;
    if (found && state != nullptr) *state = *saved;
    pthread_mutex_unlock(&g_font_state_lock);
    return found;
}

void restore_ui_component_state(void *instance) {
    UiComponentState state;
    int32_t id = component_id(instance);
    if (!id) return;
    pthread_mutex_lock(&g_font_state_lock);
    bool restore = g_original_ui_states.take(instance, id, &state);
    pthread_mutex_unlock(&g_font_state_lock);
    if (!restore) return;
    if (invoke_object_getter(g_ui_get_font_info, instance) != g_ui_font) return;
    // 列表和页签会复用旧 UI.Text；离开中文文本时恢复原设置，避免影响未汉化资源。
    if (state.font != nullptr) invoke_object_setter(g_ui_set_font_info, instance, state.font);
    if (state.has_line_spacing) {
        invoke_float_setter(g_ui_set_line_spacing_info, instance, state.line_spacing);
    }
    if (state.has_resize_best_fit) {
        invoke_bool_setter(g_ui_set_resize_best_fit_info, instance, state.resize_best_fit);
    }
    if (state.has_resize_min_size) {
        invoke_int_setter(g_ui_set_resize_min_size_info, instance, state.resize_min_size);
    }
    if (state.has_resize_max_size) {
        invoke_int_setter(g_ui_set_resize_max_size_info, instance, state.resize_max_size);
    }
    if (state.has_horizontal_overflow) {
        invoke_int_setter(
                g_ui_set_horizontal_overflow_info, instance, state.horizontal_overflow);
    }
    if (state.has_vertical_overflow) {
        invoke_int_setter(g_ui_set_vertical_overflow_info, instance, state.vertical_overflow);
    }
    if (state.has_font_size) {
        invoke_int_setter(g_ui_set_font_size_info, instance, state.font_size);
    }
}

size_t count_visible_codepoints(const std::string &text) {
    size_t count = 0;
    for (size_t offset = 0; offset < text.size();) {
        // 富文本标签不占显示宽度，不能让颜色、样式参数把短标签误判成长正文。
        if (text[offset] == '<') {
            size_t tag_end = text.find('>', offset + 1);
            if (tag_end != std::string::npos) {
                offset = tag_end + 1;
                continue;
            }
        }
        unsigned char byte = static_cast<unsigned char>(text[offset++]);
        if ((byte & 0xc0) != 0x80) ++count;
    }
    return count;
}

void apply_translated_tmp_style(void *instance, void *managed_text,
                                const TmpComponentState &state) {
    if (managed_text == nullptr || g_string_length == nullptr ||
        g_string_chars == nullptr) {
        return;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 1024 * 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text)) {
        return;
    }

    // The announcer's custom scroll task owns its layout, including later parses.
    if (is_announcer_description(instance) || is_choice_text(instance) ||
        !state.has_font_size || state.font_size <= 0.0f) return;

    size_t visible_length = count_visible_codepoints(text);
    bool has_explicit_break = limbus_text_provenance::explicit_line_break(text);
    ComponentRect rect = read_component_rect(instance, g_tmp_get_rect_transform_info);
    bool short_text = visible_length <= 48;
    std::string component_name;
    std::string parent_name;
    bool needs_component_identity = has_explicit_break ||
            (rect.width > 0.0f && rect.width < 140.0f && rect.height <= 0.0f);
    if (needs_component_identity) {
        component_name = read_unity_object_name(instance);
        parent_name = read_unity_parent_name(instance);
    }
    // 编队页、人格排列页与人格选择页会复用同名文本组件，但父节点名称并不一致。
    // 使用精确组件名覆盖三类页面，避免“公主”等包内已有换行只在部分页面生效。
    bool personality_group_name = component_name == "[Text]GroupName";
    bool formation_deck_label = component_name == "[Text]Deck" &&
            parent_name == "[Image]Mask";
    bool single_line = limbus_text_provenance::single_line_label(visible_length, has_explicit_break,
            state.has_word_wrapping && !state.word_wrapping, rect.height, state.font_size,
            state.has_overflow_mode ? state.overflow_mode : -1);
    if (state.has_overflow_mode && state.overflow_mode >= 2) return;

    if (single_line) {
        // 页签、技能名和横幅必须保持单行。之前只启用自动字号却没有关闭换行，
        // TMP 会先把中文拆成数行，再被一行高的遮罩裁成黄块或黑块。
        invoke_bool_setter(g_tmp_set_word_wrapping_info, instance, false);
        // Ellipsis 与自动字号配合：先在可读字号范围内缩小，仍放不下时才显示省略号。
        // 战斗技能名因此不会换出第二行，也不会为了显示全名缩成难以辨认的小字。
        invoke_int_setter(g_tmp_set_overflow_mode_info, instance,
                          rect.valid ? 1 : 0);  // 有有效宽度时 Ellipsis，否则 Overflow。
        float scale = visible_length <= 8 ? 1.0f :
                (visible_length <= 20 ? 0.96f : 0.90f);
        // 左侧编队槽原始字号为 30，在手机上辨识度不足；该控件高度由遮罩动态提供，
        // 因此固定为用户指定的 35，避免自动字号再次把长编队名缩小。
        float maximum = formation_deck_label ? 35.0f : state.font_size * scale;
        float minimum = formation_deck_label ? 35.0f :
                std::min(maximum, std::max(9.0f, state.font_size * 0.55f));
        invoke_float_setter(g_tmp_set_font_size_min_info, instance, minimum);
        invoke_float_setter(g_tmp_set_font_size_max_info, instance, maximum);
        invoke_float_setter(g_tmp_set_font_size_info, instance, maximum);
        invoke_bool_setter(g_tmp_set_auto_sizing_info, instance, true);
        if (state.has_line_spacing) {
            invoke_float_setter(g_tmp_set_line_spacing_info, instance, state.line_spacing);
        }
    } else if (short_text && has_explicit_break && personality_group_name) {
        // Only this verified card title uses fixed lines. Dialogue keeps wrapping.
        invoke_bool_setter(g_tmp_set_word_wrapping_info, instance, false);
        invoke_int_setter(g_tmp_set_overflow_mode_info, instance, 0);  // Overflow.
        invoke_bool_setter(g_tmp_set_auto_sizing_info, instance, false);
        invoke_float_setter(g_tmp_set_font_size_info, instance, state.font_size);
        // The title's explicit line-height already controls its paragraph spacing.
        invoke_float_setter(g_tmp_set_line_spacing_info, instance, 0.0f);
    } else {
        // 长说明恢复页面原有换行与溢出策略，字号不再统一缩小 8%；正文优先保持可读。
        if (state.has_word_wrapping) {
            invoke_bool_setter(g_tmp_set_word_wrapping_info, instance, state.word_wrapping);
        }
        if (state.has_overflow_mode) {
            invoke_int_setter(g_tmp_set_overflow_mode_info, instance, state.overflow_mode);
        }
        if (state.has_line_spacing) {
            invoke_float_setter(g_tmp_set_line_spacing_info, instance, state.line_spacing);
        }
        if (state.has_auto_sizing) {
            invoke_bool_setter(g_tmp_set_auto_sizing_info, instance, state.auto_sizing);
        }
        if (state.has_font_size_min) {
            invoke_float_setter(g_tmp_set_font_size_min_info, instance, state.font_size_min);
        }
        if (state.has_font_size_max) {
            invoke_float_setter(g_tmp_set_font_size_max_info, instance, state.font_size_max);
        }
        invoke_float_setter(g_tmp_set_font_size_info, instance, state.font_size * 0.98f);
    }

    uint64_t decisions = g_layout_decisions.fetch_add(1, std::memory_order_relaxed) + 1;
    if (decisions <= 20 || decisions % 500 == 0) {
        // 诊断只记录几何和分类，不输出正文内容；用户回传日志即可判断控件为何选了单/双行。
        LT_LOGI("TMP layout decision=%llu chars=%zu rect=%.1fx%.1f font=%.1f single=%d short=%d",
                static_cast<unsigned long long>(decisions), visible_length,
                rect.width, rect.height, state.font_size, single_line, short_text);
    }
}

void *apply_tmp_component_layout_markup(void *instance, void *managed_text) {
    if (instance == nullptr || managed_text == nullptr ||
        g_string_length == nullptr || g_string_chars == nullptr) {
        return managed_text;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 4096 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text) ||
        text.find("<line-height=") != std::string::npos) {
        return managed_text;
    }
    std::string component_name = read_unity_object_name(instance);
    if (component_name != "[Text]GroupName") {
        return managed_text;
    }
    // 某些页面会把包内换行压成空格或直接去掉。通过当前汉化包自动生成的兼容索引
    // 恢复原始分行，确保任意人格标题都遵循包内排版，而不是只修复已知个例。
    auto package_layout = g_package_line_breaks.find(text);
    if (package_layout != g_package_line_breaks.end()) {
        text = package_layout->second;
    }
    if (text.find('\n') == std::string::npos) {
        return managed_text;
    }
    // 人格名称控件会忽略运行时 lineSpacing，却会在生成网格时遵守 TMP 富文本行高。
    // 只移动空格处的换行，不增删或改写汉化包正文，并统一使用用户指定的 150% 行高。
    size_t line_count = 0;
    std::string reflowed = reflow_personality_title(text, &line_count);
    // 用户要求所有人格卡底栏保持一致，二行与三行标题统一采用 150% 行高。
    std::string formatted = apply_uniform_line_height(reflowed, 150);
    void *replacement = new_managed_string(formatted);
    if (replacement == nullptr) return managed_text;
    pthread_mutex_lock(&g_font_state_lock);
    bool retained = remember_translated_text(replacement, formatted);
    pthread_mutex_unlock(&g_font_state_lock);
    return retained ? replacement : managed_text;
}

void *prepare_tmp_text(void *instance, void *managed_text, bool restore_untranslated = true) {
    void *replacement = managed_text;
    if (instance != nullptr && managed_text != nullptr && g_string_length != nullptr &&
        g_string_chars != nullptr && g_string_new_utf16 != nullptr) {
        int32_t length = g_string_length(managed_text);
        std::string source;
        if (length >= 0 && length <= 1024 * 1024 &&
            utf16_to_utf8(g_string_chars(managed_text), length, &source)) {
            // TextData formatter hooks can translate before the value reaches
            // TMP_Text.  In that case the setter sees an already-Chinese string
            // and cannot use pointer inequality to know that a fallback font is
            // required.  Prepare the fallback before any non-ASCII text is laid
            // out; it remains fallback-only, so Japanese/Latin primary styling
            // is unchanged.
            bool needs_fallback = std::any_of(source.begin(), source.end(),
                    [](unsigned char byte) { return (byte & 0x80) != 0; });
            if (needs_fallback) ensure_tmp_font();
            // Event typewriter fragments are already translated. Do not run a
            // second glossary pass over an in-progress sentence.
            const bool choice_proof = is_choice_text(instance, managed_text);
            replacement = choice_proof ? managed_text : translate_managed_string(managed_text);
            if (replacement == managed_text) {
                const std::string status = limbus_ui_labels::translate_status(source);
                if (!status.empty() && ensure_tmp_font()) {
                    void *label = new_managed_string(status);
                    if (label != nullptr) {
                        pthread_mutex_lock(&g_font_state_lock);
                        if (remember_translated_text(label, status)) replacement = label;
                        pthread_mutex_unlock(&g_font_state_lock);
                    }
                }
            }
            if (replacement != managed_text && !ensure_tmp_font()) {
                return managed_text;
            }
            replacement = apply_tmp_component_layout_markup(instance, replacement);
            bool translated = choice_proof || is_translated_managed_string(replacement);
            std::vector<void *> font_targets;
            if (!translated) translated = is_translated_tmp_fragment(instance, replacement, &font_targets);
            if (translated && ensure_tmp_font() && g_tmp_get_font != nullptr &&
                g_tmp_set_font != nullptr) {
                if (font_targets.empty()) font_targets.push_back(instance);
                for (void *font_target : font_targets) {
                    void *current_font = g_tmp_get_font(font_target, g_tmp_get_font_info);
                    if (current_font != g_tmp_font_asset) {
                        TmpComponentState original_style;
                        if (!capture_tmp_component_state(font_target, current_font, &original_style)) {
                            if (font_target == instance) return managed_text;
                            continue;
                        }
                        g_tmp_set_font(font_target, g_tmp_font_asset, g_tmp_set_font_info);
                        // TMP 组件可能保留旧字体的共享材质；字体图集与材质不匹配时会把
                        // 正确字形采样成碎片或白块，因此在字体切换后显式绑定中文字体材质。
                        void *font_material = translated_tmp_material(font_target, original_style.shared_material);
                        if (font_material != nullptr) {
                            invoke_object_setter(
                                    g_tmp_set_shared_material_info, font_target, font_material);
                        }
                        uint64_t switches = g_tmp_primary_font_switches.fetch_add(
                                1, std::memory_order_relaxed) + 1;
                        if (switches == 1 || switches % 500 == 0) {
                            // 周期日志会保留到较晚导出的诊断包，便于确认主字体切换确实发生。
                            LT_LOGI("TMP Chinese primary font switches=%llu font=%p",
                                    static_cast<unsigned long long>(switches), g_tmp_font_asset);
                        }
                    }
                }
            } else if (!translated && restore_untranslated) {
                restore_tmp_component_state(instance);
            }
            if (replacement != managed_text) {
                uint64_t hits = __atomic_add_fetch(&g_translation_hits, 1, __ATOMIC_RELAXED);
                if (hits == 1 || hits % 500 == 0) {
                    LT_LOGI("TMP translation hits=%llu", static_cast<unsigned long long>(hits));
                }
            }
        }
    }
    return replacement;
}

void finalize_tmp_text(void *instance, void *managed_text) {
    if (instance == nullptr || managed_text == nullptr ||
        !(is_translated_managed_string(managed_text) || is_choice_text(instance, managed_text))) {
        return;
    }
    TmpComponentState state;
    if (find_tmp_component_state(instance, &state)) {
        // 必须在原始 set_text/SetText 完成后再应用字号和换行；否则游戏 setter 会用
        // 旧文本重新计算布局，导致我们设置的自动缩放没有真正处理新中文。
        apply_translated_tmp_style(instance, managed_text, state);
    }
}

void apply_translated_ui_style(void *instance, void *managed_text,
                               const UiComponentState &state) {
    if (managed_text == nullptr || !state.has_font_size || state.font_size <= 0 ||
        g_string_length == nullptr || g_string_chars == nullptr) {
        return;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 1024 * 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text)) {
        return;
    }
    size_t visible_length = count_visible_codepoints(text);
    bool has_explicit_break = text.find('\n') != std::string::npos ||
            text.find('\r') != std::string::npos;
    bool short_text = visible_length <= 48;
    ComponentRect rect = read_component_rect(instance, g_ui_get_rect_transform_info);
    bool single_line = limbus_text_provenance::single_line_label(visible_length, has_explicit_break,
            state.has_horizontal_overflow && state.horizontal_overflow == 1, rect.height,
            static_cast<float>(state.font_size));

    if (single_line) {
        // 旧 UI.Text 没有 TMP 的单行自动缩放。根据矩形宽度直接计算安全字号，
        // 然后关闭换行和裁切，保证横幅与页签不会只剩零碎笔画。
        float scale = 1.0f;
        if (rect.valid && visible_length > 0) {
            float estimated_width = static_cast<float>(visible_length * state.font_size);
            scale = std::min(1.0f, rect.width / std::max(1.0f, estimated_width));
        }
        int32_t safe_size = std::max(7, static_cast<int32_t>(
                static_cast<float>(state.font_size) * scale * 0.96f));
        invoke_bool_setter(g_ui_set_resize_best_fit_info, instance, false);
        invoke_int_setter(g_ui_set_font_size_info, instance, safe_size);
        invoke_int_setter(g_ui_set_horizontal_overflow_info, instance, 1);  // Overflow.
        invoke_int_setter(g_ui_set_vertical_overflow_info, instance, 1);    // Overflow.
        if (state.has_line_spacing) {
            invoke_float_setter(g_ui_set_line_spacing_info, instance,
                                std::max(1.0f, state.line_spacing));
        }
    } else if (short_text && has_explicit_break) {
        // 汉化包明确含换行时保留原字号，并用 Unity UI 的行高倍率拉开上下距离。
        invoke_bool_setter(g_ui_set_resize_best_fit_info, instance, false);
        invoke_int_setter(g_ui_set_font_size_info, instance, state.font_size);
        invoke_int_setter(g_ui_set_horizontal_overflow_info, instance, 0);  // Wrap.
        invoke_int_setter(g_ui_set_vertical_overflow_info, instance, 1);    // Overflow.
        invoke_float_setter(g_ui_set_line_spacing_info, instance,
                            state.has_line_spacing ? std::max(1.24f, state.line_spacing) : 1.24f);
    } else {
        // 正文保留游戏原排版，避免为了塞进一屏而缩成难以阅读的小字。
        if (state.has_resize_best_fit) {
            invoke_bool_setter(
                    g_ui_set_resize_best_fit_info, instance, state.resize_best_fit);
        }
        if (state.has_resize_min_size) {
            invoke_int_setter(
                    g_ui_set_resize_min_size_info, instance, state.resize_min_size);
        }
        if (state.has_resize_max_size) {
            invoke_int_setter(
                    g_ui_set_resize_max_size_info, instance, state.resize_max_size);
        }
        if (state.has_horizontal_overflow) {
            invoke_int_setter(g_ui_set_horizontal_overflow_info, instance,
                              state.horizontal_overflow);
        }
        if (state.has_vertical_overflow) {
            invoke_int_setter(
                    g_ui_set_vertical_overflow_info, instance, state.vertical_overflow);
        }
        if (state.has_line_spacing) {
            invoke_float_setter(g_ui_set_line_spacing_info, instance, state.line_spacing);
        }
        invoke_int_setter(g_ui_set_font_size_info, instance, state.font_size);
    }
}

void *prepare_ui_text(void *instance, void *managed_text) {
    void *replacement = translate_managed_string(managed_text);
    bool translated = replacement != nullptr && is_translated_managed_string(replacement);
    if (!translated) {
        restore_ui_component_state(instance);
        return replacement;
    }
    ensure_tmp_font();
    if (instance == nullptr || g_ui_font == nullptr) return managed_text;
    void *current_font = invoke_object_getter(g_ui_get_font_info, instance);
    if (current_font != g_ui_font) {
        if (!capture_ui_component_state(instance)) return managed_text;
        invoke_object_setter(g_ui_set_font_info, instance, g_ui_font);
        uint64_t switches = g_ui_primary_font_switches.fetch_add(
                1, std::memory_order_relaxed) + 1;
        if (switches == 1 || switches % 500 == 0) {
            LT_LOGI("UI.Text Chinese primary font switches=%llu font=%p",
                    static_cast<unsigned long long>(switches), g_ui_font);
        }
    }
    return replacement;
}

void finalize_ui_text(void *instance, void *managed_text) {
    if (instance == nullptr || managed_text == nullptr ||
        !is_translated_managed_string(managed_text)) {
        return;
    }
    UiComponentState state;
    if (find_ui_component_state(instance, &state)) {
        apply_translated_ui_style(instance, managed_text, state);
    }
}

void replacement_tmp_set_text(void *instance, void *managed_text, const void *method) {
    void *replacement = prepare_tmp_text(instance, managed_text);
    g_original_tmp_set_text(instance, replacement, method);
    finalize_tmp_text(instance, replacement);
    observe_popup_font(instance, replacement, false);
}

void replacement_tmp_parse_input(void *instance, const void *method) {
    if (g_original_tmp_parse_input == nullptr) return;
    if (instance == nullptr || g_inside_tmp_parse || g_tmp_get_text_info == nullptr ||
        g_original_tmp_set_text == nullptr) {
        g_original_tmp_parse_input(instance, method);
        return;
    }
    // Serialized prefab text and char-buffer inputs can bypass all string
    // setters. Read through TMP's getter at its common parsing boundary, never
    // through a guessed IL2CPP field offset. Font/layout APIs may re-enter this
    // method while measuring text; nested calls must retain Unity's own path.
    struct ParseGuard {
        ParseGuard() { g_inside_tmp_parse = true; }
        ~ParseGuard() { g_inside_tmp_parse = false; }
    } guard;
    void *source = invoke_object_getter(g_tmp_get_text_info, instance);
    // Parsing also runs for numeric buffers and recycled objects that never
    // passed through a translated setter. It must not restore stale layout.
    void *replacement = prepare_tmp_text(instance, source, false);
    if (replacement != source) {
        g_original_tmp_set_text(instance, replacement, g_tmp_set_text_info);
        uint64_t hits = g_render_translation_hits.fetch_add(1, std::memory_order_relaxed) + 1;
        if (hits == 1 || hits % 500 == 0) {
            LT_LOGI("TMP render-boundary translation hits=%llu",
                    static_cast<unsigned long long>(hits));
        }
    }
    finalize_tmp_text(instance, replacement);
    g_original_tmp_parse_input(instance, method);
    observe_popup_font(instance, replacement, false);
}

void replacement_tmp_set_text_one(void *instance, void *managed_text, const void *method) {
    void *replacement = prepare_tmp_text(instance, managed_text);
    g_original_tmp_set_text_one(instance, replacement, method);
    finalize_tmp_text(instance, replacement);
    observe_popup_font(instance, replacement, false);
}

void replacement_ui_set_text(void *instance, void *managed_text, const void *method) {
    void *replacement = prepare_ui_text(instance, managed_text);
    g_original_ui_set_text(instance, replacement, method);
    finalize_ui_text(instance, replacement);
    observe_popup_font(instance, replacement, true);
}

void replacement_announcer_set_description(void *instance, void *managed_text, const void *method) {
    void *description = nullptr;
    if (instance && g_announcer_description_field && g_field_get_value)
        g_field_get_value(instance, g_announcer_description_field, &description);
    int32_t id = 0;
    if (description && invoke_int_getter(g_object_get_instance_id_info, description, &id) && id) {
        pthread_mutex_lock(&g_font_state_lock);
        if (g_announcer_text_ids.size() >= 32 && !g_announcer_text_ids.count(description)) g_announcer_text_ids.clear();
        g_announcer_text_ids[description] = id;
        pthread_mutex_unlock(&g_font_state_lock);
    }
    // Translate before the original method measures text and starts scrolling.
    void *translated = translate_managed_string(managed_text);
    if (translated != managed_text && !ensure_tmp_font()) translated = managed_text;
    g_original_announcer_set_description(instance, translated, method);
}

void replacement_tmp_set_text_string(void *instance, void *managed_text, bool sync_input,
                                     const void *method) {
    void *replacement = prepare_tmp_text(instance, managed_text);
    g_original_tmp_set_text_string(instance, replacement, sync_input, method);
    finalize_tmp_text(instance, replacement);
    observe_popup_font(instance, replacement, false);
}

int32_t replacement_get_language(void *instance, const void *method) {
    uint32_t hits = g_language_hook_hits.fetch_add(1, std::memory_order_relaxed) + 1;
    if (hits <= 5 || hits % 5000 == 0) {
        LT_LOGI("Japanese language getter hit=%u source=LocalGameOptionData instance=%p method=%p",
                hits, instance, method);
    }
    return 2;  // LOCALIZE_LANGUAGE.JP
}

int32_t replacement_global_get_language(void *instance, const void *method) {
    uint32_t hits = g_language_hook_hits.fetch_add(1, std::memory_order_relaxed) + 1;
    if (hits <= 5 || hits % 5000 == 0) {
        LT_LOGI("Japanese language getter hit=%u source=GlobalGameManager.Lang instance=%p method=%p",
                hits, instance, method);
    }
    return 2;  // LOCALIZE_LANGUAGE.JP
}

bool read_exact(FILE *file, void *buffer, size_t size) {
    return size == 0 || fread(buffer, 1, size, file) == size;
}

bool read_u32(FILE *file, uint32_t *value) {
    uint32_t encoded = 0;
    if (!read_exact(file, &encoded, sizeof(encoded))) return false;
    *value = ntohl(encoded);
    return true;
}

bool read_string(FILE *file, std::string *value) {
    uint32_t size = 0;
    if (!read_u32(file, &size) || size > kMaxStringBytes) return false;
    value->resize(size);
    return read_exact(file, size == 0 ? nullptr : &(*value)[0], size);
}

void rebuild_package_line_break_index(
        const std::unordered_map<std::string, std::string> &translations) {
    g_package_line_breaks.clear();
    std::unordered_set<std::string> ambiguous;
    const auto register_variant = [&ambiguous](const std::string &variant,
                                               const std::string &translation) {
        if (variant == translation || ambiguous.find(variant) != ambiguous.end()) return;
        auto existing = g_package_line_breaks.find(variant);
        if (existing == g_package_line_breaks.end()) {
            g_package_line_breaks.emplace(variant, translation);
        } else if (existing->second != translation) {
            // 展平形式有歧义时宁可保留游戏文本，也不能恢复成另一个人格的换行结构。
            g_package_line_breaks.erase(existing);
            ambiguous.insert(variant);
        }
    };
    for (const auto &entry : translations) {
        const std::string &translation = entry.second;
        if (translation.find('\n') == std::string::npos) continue;
        std::string spaced = translation;
        std::replace(spaced.begin(), spaced.end(), '\n', ' ');
        register_variant(spaced, translation);
        std::string compact = translation;
        compact.erase(std::remove(compact.begin(), compact.end(), '\n'), compact.end());
        register_variant(compact, translation);
    }
}

// Bounded metadata only: never includes game text, account information or resource contents.
void write_runtime_receipt(const char *state) {
    std::string root(g_active_pointer);
    const size_t slash = root.rfind('/');
    if (slash == std::string::npos) return;
    root.resize(slash);
    char request[80] = {};
    FILE *token = fopen((root + "/launch.request").c_str(), "r");
    if (token == nullptr) return;
    const bool read = fgets(request, sizeof(request), token) != nullptr;
    fclose(token);
    request[strcspn(request, "\r\n")] = 0;
    if (!read || strlen(request) != 36 || strspn(request, "0123456789abcdef-") != 36) return;
    const char *profile = getenv("V_LIMBUS_NATIVE_PROFILE");
    if (profile == nullptr || strlen(profile) > 64 || strspn(profile, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-") != strlen(profile)) profile = "";
    const std::string target = root + "/runtime-state.properties";
    const std::string temporary = target + "." + std::to_string(getpid()) + ".tmp";
    FILE *output = fopen(temporary.c_str(), "w");
    if (output == nullptr) return;
    fprintf(output, "request=%s\nstate=%s\npid=%d\nentries=%zu\nterms=%zu\nnativeProfile=%s\n",
            request, state, getpid(), g_index.size(), g_terms.size(), profile);
    fflush(output);
    fsync(fileno(output));
    fclose(output);
    if (rename(temporary.c_str(), target.c_str()) != 0) unlink(temporary.c_str());
}

bool load_index_file(const char *path) {
    FILE *file = fopen(path, "rb");
    if (file == nullptr) {
        LT_LOGW("Index file is not readable path=%s errno=%d", path, errno);
        return false;
    }
    unsigned char magic[sizeof(kIndexMagic)] = {};
    uint32_t schema = 0;
    uint32_t count = 0;
    uint32_t term_count = 0;
    uint32_t contextual_count = 0;
    bool valid = read_exact(file, magic, sizeof(magic)) &&
                 memcmp(magic, kIndexMagic, sizeof(magic)) == 0 &&
                 read_u32(file, &schema) && schema == kIndexSchema &&
                 read_u32(file, &count) && count <= kMaxEntries &&
                 read_u32(file, &term_count) && term_count <= kMaxEntries &&
                 read_u32(file, &contextual_count) && contextual_count <= kMaxEntries;
    std::unordered_map<std::string, std::string> loaded;
    std::unordered_map<std::string, std::string> loaded_terms;
    std::unordered_map<std::string, std::string> loaded_contextual;
    if (valid) {
        loaded.reserve(count);
        for (uint32_t i = 0; i < count; ++i) {
            std::string source;
            std::string translation;
            if (!read_string(file, &source) || !read_string(file, &translation) || source.empty()) {
                valid = false;
                break;
            }
            loaded[source] = translation;
        }
        loaded_terms.reserve(term_count);
        for (uint32_t i = 0; valid && i < term_count; ++i) {
            std::string source;
            std::string translation;
            if (!read_string(file, &source) || !read_string(file, &translation) || source.empty()) {
                valid = false;
                break;
            }
            loaded_terms[source] = translation;
        }
        loaded_contextual.reserve(contextual_count);
        for (uint32_t i = 0; valid && i < contextual_count; ++i) {
            std::string key;
            std::string translation;
            if (!read_string(file, &key) || !read_string(file, &translation) || key.empty()) {
                valid = false;
                break;
            }
            loaded_contextual[key] = translation;
        }
    }
    if (valid && fgetc(file) != EOF) valid = false;
    fclose(file);
    if (!valid || loaded.size() != count || loaded_terms.size() != term_count ||
        loaded_contextual.size() != contextual_count) {
        LT_LOGW("Reject invalid translation index path=%s schema=%u declared=%u/%u/%u loaded=%zu/%zu/%zu",
                path, schema, count, term_count, contextual_count,
                loaded.size(), loaded_terms.size(), loaded_contextual.size());
        return false;
    }
    g_font_catalogue.clear();
    g_index.swap(loaded);
    g_terms.swap(loaded_terms);
    g_contextual_index.swap(loaded_contextual);
    for (const auto *table : {&g_index, &g_contextual_index}) {
        for (const auto &entry : *table) {
            const auto separator = entry.first.rfind('\x1f');
            const std::string source = table == &g_contextual_index && separator != std::string::npos
                    ? entry.first.substr(separator + 1) : entry.first;
            // Identity guards may contain untranslated kana or placeholder
            // metadata. They block a wrong replacement, not authorize a font.
            if (source != entry.second || !contains_japanese_kana(source))
                g_font_catalogue.add(entry.second);
        }
    }
    LT_LOGI("Font provenance catalogue labels=%zu templates=%zu",
            g_font_catalogue.label_count(), g_font_catalogue.pattern_count());
    rebuild_package_line_break_index(g_index);
    rebuild_term_trie();
    LT_LOGI("Loaded Japanese full-text translation index entries=%zu terms=%zu contexts=%zu lineBreaks=%zu path=%s",
            g_index.size(), g_terms.size(), g_contextual_index.size(), g_package_line_breaks.size(), path);
    return true;
}

bool load_active_index() {
    FILE *pointer = fopen(g_active_pointer, "r");
    if (pointer == nullptr) {
        LT_LOGI("No active translation index pointer path=%s", g_active_pointer);
        return false;
    }
    char index_path[PATH_MAX] = {};
    bool valid = fgets(index_path, sizeof(index_path), pointer) != nullptr;
    fclose(pointer);
    if (!valid) {
        LT_LOGW("Active translation index pointer is empty path=%s", g_active_pointer);
        return false;
    }
    index_path[strcspn(index_path, "\r\n")] = 0;
    if (index_path[0] != '/') {
        LT_LOGW("Reject non-absolute translation index path=%s", index_path);
        return false;
    }
    std::string allowed(g_active_pointer);
    allowed.resize(allowed.rfind('/') + 1);
    allowed += "versions/";
    char canonical[PATH_MAX] = {};
    char canonical_root[PATH_MAX] = {};
    if (realpath(allowed.c_str(), canonical_root) == nullptr) return false;
    allowed = std::string(canonical_root) + "/";
    if (realpath(index_path, canonical) == nullptr || strncmp(canonical, allowed.c_str(), allowed.size()) != 0) {
        LT_LOGW("Reject translation index outside generation directory");
        return false;
    }
    return load_index_file(canonical);
}

class MappedElfResolver {
public:
    MappedElfResolver() : mapping_(MAP_FAILED), size_(0), base_(0), symbols_(nullptr),
                          symbol_count_(0), strings_(nullptr), strings_size_(0) {}

    ~MappedElfResolver() {
        if (mapping_ != MAP_FAILED) munmap(mapping_, size_);
    }

    bool open(const char *path) {
        FILE *maps = fopen("/proc/self/maps", "r");
        char line[PATH_MAX + 160] = {};
        while (maps != nullptr && fgets(line, sizeof(line), maps) != nullptr) {
            unsigned long start = 0;
            unsigned long offset = 1;
            if (strstr(line, path) != nullptr &&
                sscanf(line, "%lx-%*lx %*s %lx", &start, &offset) == 2 && offset == 0) {
                if (static_cast<uintptr_t>(start) > base_) {
                    base_ = static_cast<uintptr_t>(start);
                }
            }
        }
        if (maps != nullptr) fclose(maps);
        int fd = ::open(path, O_RDONLY | O_CLOEXEC);
        struct stat state = {};
        if (base_ == 0 || fd < 0 || fstat(fd, &state) != 0 || state.st_size <= 0) {
            if (fd >= 0) close(fd);
            return false;
        }
        size_ = static_cast<size_t>(state.st_size);
        mapping_ = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (mapping_ == MAP_FAILED || size_ < sizeof(Elf64_Ehdr)) return false;
        const Elf64_Ehdr *header = reinterpret_cast<const Elf64_Ehdr *>(mapping_);
        if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 ||
            header->e_ident[EI_CLASS] != ELFCLASS64 || header->e_type != ET_DYN ||
            header->e_shentsize != sizeof(Elf64_Shdr) || header->e_shnum == 0 ||
            header->e_shoff > size_ ||
            header->e_shnum > (size_ - header->e_shoff) / sizeof(Elf64_Shdr)) return false;
        const Elf64_Shdr *sections = reinterpret_cast<const Elf64_Shdr *>(
                reinterpret_cast<const unsigned char *>(mapping_) + header->e_shoff);
        for (size_t i = 0; i < header->e_shnum; ++i) {
            const Elf64_Shdr &section = sections[i];
            if (section.sh_type != SHT_DYNSYM || section.sh_entsize != sizeof(Elf64_Sym) ||
                section.sh_link >= header->e_shnum || section.sh_offset > size_ ||
                section.sh_size > size_ - section.sh_offset) continue;
            const Elf64_Shdr &strings = sections[section.sh_link];
            if (strings.sh_type != SHT_STRTAB || strings.sh_offset > size_ ||
                strings.sh_size > size_ - strings.sh_offset) continue;
            symbols_ = reinterpret_cast<const Elf64_Sym *>(
                    reinterpret_cast<const unsigned char *>(mapping_) + section.sh_offset);
            symbol_count_ = section.sh_size / sizeof(Elf64_Sym);
            strings_ = reinterpret_cast<const char *>(mapping_) + strings.sh_offset;
            strings_size_ = strings.sh_size;
            return true;
        }
        return false;
    }

    template <typename T>
    T resolve(const char *name) const {
        for (size_t i = 0; i < symbol_count_; ++i) {
            const Elf64_Sym &symbol = symbols_[i];
            if (symbol.st_name >= strings_size_ || symbol.st_shndx == SHN_UNDEF) continue;
            if (strcmp(strings_ + symbol.st_name, name) == 0) {
                return reinterpret_cast<T>(base_ + symbol.st_value);
            }
        }
        return nullptr;
    }

    void **resolve_import_slot(const char *name) const {
        if (mapping_ == MAP_FAILED) return nullptr;
        const Elf64_Ehdr *header = reinterpret_cast<const Elf64_Ehdr *>(mapping_);
        const Elf64_Shdr *sections = reinterpret_cast<const Elf64_Shdr *>(
                reinterpret_cast<const unsigned char *>(mapping_) + header->e_shoff);
        for (size_t i = 0; i < header->e_shnum; ++i) {
            const Elf64_Shdr &relocations = sections[i];
            if (relocations.sh_type != SHT_RELA || relocations.sh_entsize != sizeof(Elf64_Rela) ||
                relocations.sh_link >= header->e_shnum || relocations.sh_offset > size_ ||
                relocations.sh_size > size_ - relocations.sh_offset) continue;
            const Elf64_Shdr &symbols = sections[relocations.sh_link];
            if (symbols.sh_type != SHT_DYNSYM || symbols.sh_entsize != sizeof(Elf64_Sym) ||
                symbols.sh_link >= header->e_shnum || symbols.sh_offset > size_ ||
                symbols.sh_size > size_ - symbols.sh_offset) continue;
            const Elf64_Shdr &strings = sections[symbols.sh_link];
            if (strings.sh_type != SHT_STRTAB || strings.sh_offset > size_ ||
                strings.sh_size > size_ - strings.sh_offset) continue;
            const Elf64_Rela *entries = reinterpret_cast<const Elf64_Rela *>(
                    reinterpret_cast<const unsigned char *>(mapping_) + relocations.sh_offset);
            const Elf64_Sym *symbol_table = reinterpret_cast<const Elf64_Sym *>(
                    reinterpret_cast<const unsigned char *>(mapping_) + symbols.sh_offset);
            const char *string_table = reinterpret_cast<const char *>(mapping_) + strings.sh_offset;
            size_t relocation_count = relocations.sh_size / sizeof(Elf64_Rela);
            size_t symbol_count = symbols.sh_size / sizeof(Elf64_Sym);
            for (size_t relocation = 0; relocation < relocation_count; ++relocation) {
                uint32_t type = ELF64_R_TYPE(entries[relocation].r_info);
                size_t symbol_index = ELF64_R_SYM(entries[relocation].r_info);
                if ((type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) ||
                    symbol_index >= symbol_count || symbol_table[symbol_index].st_name >= strings.sh_size) {
                    continue;
                }
                if (strcmp(string_table + symbol_table[symbol_index].st_name, name) == 0) {
                    return reinterpret_cast<void **>(base_ + entries[relocation].r_offset);
                }
            }
        }
        return nullptr;
    }

private:
    void *mapping_;
    size_t size_;
    uintptr_t base_;
    const Elf64_Sym *symbols_;
    size_t symbol_count_;
    const char *strings_;
    size_t strings_size_;
};

bool report_il2cpp_resolver(const MappedElfResolver &resolver, bool query_domain) {
    typedef void *(*domain_get_fn)();
    typedef const void **(*domain_get_assemblies_fn)(const void *, size_t *);
    typedef const void *(*assembly_get_image_fn)(const void *);
    typedef size_t (*image_get_class_count_fn)(const void *);
    typedef void *(*image_get_class_fn)(const void *, size_t);
    typedef const char *(*class_get_name_fn)(void *);
    typedef void *(*class_from_name_fn)(const void *, const char *, const char *);
    typedef const void *(*class_get_method_from_name_fn)(void *, const char *, int);
    typedef const void *(*class_get_methods_fn)(void *, void **);
    typedef const char *(*method_get_name_fn)(const void *);
    typedef uint32_t (*method_get_param_count_fn)(const void *);
    typedef uint32_t (*method_get_flags_fn)(const void *, uint32_t *);
    typedef const void *(*method_get_param_fn)(const void *, uint32_t);
    typedef const void *(*method_get_return_type_fn)(const void *);
    typedef char *(*type_get_name_fn)(const void *);
    typedef void (*il2cpp_free_fn)(void *);

    domain_get_fn domain_get = resolver.resolve<domain_get_fn>("il2cpp_domain_get");
    domain_get_assemblies_fn domain_get_assemblies =
            resolver.resolve<domain_get_assemblies_fn>("il2cpp_domain_get_assemblies");
    assembly_get_image_fn assembly_get_image =
            resolver.resolve<assembly_get_image_fn>("il2cpp_assembly_get_image");
    image_get_class_count_fn image_get_class_count =
            resolver.resolve<image_get_class_count_fn>("il2cpp_image_get_class_count");
    image_get_class_fn image_get_class =
            resolver.resolve<image_get_class_fn>("il2cpp_image_get_class");
    class_get_name_fn class_get_name =
            resolver.resolve<class_get_name_fn>("il2cpp_class_get_name");
    class_get_name_fn class_get_namespace =
            resolver.resolve<class_get_name_fn>("il2cpp_class_get_namespace");
    class_from_name_fn class_from_name =
            resolver.resolve<class_from_name_fn>("il2cpp_class_from_name");
    class_get_method_from_name_fn class_get_method_from_name =
            resolver.resolve<class_get_method_from_name_fn>("il2cpp_class_get_method_from_name");
    class_get_methods_fn class_get_methods =
            resolver.resolve<class_get_methods_fn>("il2cpp_class_get_methods");
    method_get_name_fn method_get_name =
            resolver.resolve<method_get_name_fn>("il2cpp_method_get_name");
    method_get_param_count_fn method_get_param_count =
            resolver.resolve<method_get_param_count_fn>("il2cpp_method_get_param_count");
    method_get_flags_fn method_get_flags =
            resolver.resolve<method_get_flags_fn>("il2cpp_method_get_flags");
    method_get_param_fn method_get_param =
            resolver.resolve<method_get_param_fn>("il2cpp_method_get_param");
    method_get_return_type_fn method_get_return_type =
            resolver.resolve<method_get_return_type_fn>("il2cpp_method_get_return_type");
    type_get_name_fn type_get_name = resolver.resolve<type_get_name_fn>("il2cpp_type_get_name");
    il2cpp_free_fn il2cpp_free = resolver.resolve<il2cpp_free_fn>("il2cpp_free");
    string_length_fn string_length = resolver.resolve<string_length_fn>("il2cpp_string_length");
    string_chars_fn string_chars = resolver.resolve<string_chars_fn>("il2cpp_string_chars");
    string_new_utf16_fn string_new_utf16 =
            resolver.resolve<string_new_utf16_fn>("il2cpp_string_new_utf16");
    runtime_invoke_fn runtime_invoke = resolver.resolve<runtime_invoke_fn>("il2cpp_runtime_invoke");
    object_get_class_fn object_get_class =
            resolver.resolve<object_get_class_fn>("il2cpp_object_get_class");
    object_unbox_fn object_unbox = resolver.resolve<object_unbox_fn>("il2cpp_object_unbox");
    limbus_managed::GcApi gc;
    gc.retain = resolver.resolve<decltype(gc.retain)>("il2cpp_gchandle_new");
    gc.weak = resolver.resolve<decltype(gc.weak)>("il2cpp_gchandle_new_weakref");
    gc.target = resolver.resolve<decltype(gc.target)>("il2cpp_gchandle_get_target");
    gc.release = resolver.resolve<decltype(gc.release)>("il2cpp_gchandle_free");
    class_get_parent_fn class_get_parent =
            resolver.resolve<class_get_parent_fn>("il2cpp_class_get_parent");
    class_get_fields_fn class_get_fields =
            resolver.resolve<class_get_fields_fn>("il2cpp_class_get_fields");
    field_get_name_fn field_get_name =
            resolver.resolve<field_get_name_fn>("il2cpp_field_get_name");
    field_get_type_fn field_get_type =
            resolver.resolve<field_get_type_fn>("il2cpp_field_get_type");
    field_get_value_fn field_get_value =
            resolver.resolve<field_get_value_fn>("il2cpp_field_get_value");
    field_set_value_fn field_set_value =
            resolver.resolve<field_set_value_fn>("il2cpp_field_set_value");
    if (domain_get == nullptr || domain_get_assemblies == nullptr || assembly_get_image == nullptr ||
        class_from_name == nullptr || class_get_method_from_name == nullptr) {
        LT_LOGW("IL2CPP exports missing domain=%p assemblies=%p image=%p class=%p method=%p",
                reinterpret_cast<void *>(domain_get), reinterpret_cast<void *>(domain_get_assemblies),
                reinterpret_cast<void *>(assembly_get_image), reinterpret_cast<void *>(class_from_name),
                reinterpret_cast<void *>(class_get_method_from_name));
        return false;
    }
    if (!query_domain) return false;
    void *domain = domain_get();
    size_t assembly_count = 0;
    const void **assemblies = domain == nullptr ? nullptr : domain_get_assemblies(domain, &assembly_count);
    if (assemblies == nullptr || assembly_count == 0) return false;
    const void *tmp_method = nullptr;
    const void *ui_method = nullptr;
    const void *tmp_set_font_method = nullptr;
    const void *tmp_get_font_method = nullptr;
    const void *create_tmp_font_method = nullptr;
    const void *create_dynamic_font_method = nullptr;
    const void *tmp_set_text_one_method = nullptr;
    const void *tmp_set_text_string_method = nullptr;
    void *tmp_text_class = nullptr;
    void *story_dialog_class = nullptr;
    void *announcer_ui_class = nullptr;
    void *choice_scroll_class = nullptr;
    void *choice_button_class = nullptr;
    void *ui_text_class = nullptr;
    void *tmp_font_class = nullptr;
    void *unity_font_class = nullptr;
    void *unity_object_class = nullptr;
    void *component_class = nullptr;
    void *transform_class = nullptr;
    void *behaviour_class = nullptr;
    void *game_object_class = nullptr;
    void *rect_transform_class = nullptr;
    void *material_class = nullptr;
    void *tmp_settings_class = nullptr;
    void *tmp_material_manager_class = nullptr;
    void *rpg_format_class = nullptr;
    const void *get_language_method = nullptr;
    const void *get_global_language_method = nullptr;
    struct AcquisitionTarget {
        std::string name;
        void *klass;
    };
    std::vector<AcquisitionTarget> acquisition_targets;
    std::unordered_set<void *> acquisition_classes;
    std::vector<uintptr_t> managed_method_addresses;
    for (size_t i = 0; assemblies != nullptr && i < assembly_count; ++i) {
        const void *image = assembly_get_image(assemblies[i]);
        if (image == nullptr) continue;
        if (image_get_class_count != nullptr && image_get_class != nullptr &&
            class_get_name != nullptr) {
            size_t class_count = image_get_class_count(image);
            for (size_t class_index = 0; class_index < class_count; ++class_index) {
                void *klass = image_get_class(image, class_index);
                const char *name = klass == nullptr ? nullptr : class_get_name(klass);
                if (klass != nullptr && class_get_methods != nullptr) {
                    void *method_iterator = nullptr;
                    const void *known_method = nullptr;
                    while ((known_method = class_get_methods(klass, &method_iterator)) != nullptr) {
                        void *address = nullptr;
                        memcpy(&address, known_method, sizeof(address));
                        if (address != nullptr) managed_method_addresses.push_back(reinterpret_cast<uintptr_t>(address));
                    }
                }
                const char *space = klass == nullptr || class_get_namespace == nullptr ? nullptr :
                        class_get_namespace(klass);
                bool rpg_localized_model = name != nullptr && space != nullptr &&
                        strcmp(space, "RPGSystem") == 0 &&
                        (strncmp(name, "RpgLocText_", 11) == 0 ||
                         strcmp(name, "RpgLocDialogueText") == 0 ||
                         strcmp(name, "RpgLocNarrationText") == 0 ||
                         strcmp(name, "RpgLocQuestStep") == 0);
                if (name != nullptr && (strncmp(name, "TextData_", 9) == 0 || rpg_localized_model) &&
                    acquisition_classes.insert(klass).second) {
                    acquisition_targets.push_back({name, klass});
                }
            }
        }
        if (tmp_method == nullptr) {
            void *klass = class_from_name(image, "TMPro", "TMP_Text");
            if (klass != nullptr) {
                tmp_text_class = klass;
                tmp_method = class_get_method_from_name(klass, "set_text", 1);
            }
        }
        if (story_dialog_class == nullptr) {
            story_dialog_class = class_from_name(image, "StorySystem", "Dialog");
        }
        if (announcer_ui_class == nullptr)
            announcer_ui_class = class_from_name(image, "BattleUI.Announcer", "BattleAnnouncerUI");
        if (choice_scroll_class == nullptr)
            choice_scroll_class = class_from_name(image, "", "ChoiceScrollViewBox");
        if (choice_button_class == nullptr)
            choice_button_class = class_from_name(image, "", "ActionChoiceButton");
        if (g_choice_result_log_class == nullptr)
            g_choice_result_log_class = class_from_name(image, "", "ChoiceEventResultLog");
        if (ui_method == nullptr) {
            void *klass = class_from_name(image, "UnityEngine.UI", "Text");
            if (klass != nullptr) {
                ui_text_class = klass;
                ui_method = class_get_method_from_name(klass, "set_text", 1);
            }
        }
        if (tmp_font_class == nullptr) {
            tmp_font_class = class_from_name(image, "TMPro", "TMP_FontAsset");
        }
        if (unity_font_class == nullptr) {
            unity_font_class = class_from_name(image, "UnityEngine", "Font");
        }
        if (unity_object_class == nullptr) {
            unity_object_class = class_from_name(image, "UnityEngine", "Object");
        }
        if (component_class == nullptr) {
            component_class = class_from_name(image, "UnityEngine", "Component");
        }
        if (transform_class == nullptr) {
            transform_class = class_from_name(image, "UnityEngine", "Transform");
        }
        if (behaviour_class == nullptr) {
            behaviour_class = class_from_name(image, "UnityEngine", "Behaviour");
        }
        if (game_object_class == nullptr) {
            game_object_class = class_from_name(image, "UnityEngine", "GameObject");
        }
        if (rect_transform_class == nullptr) {
            rect_transform_class = class_from_name(image, "UnityEngine", "RectTransform");
        }
        if (material_class == nullptr) {
            material_class = class_from_name(image, "UnityEngine", "Material");
        }
        if (tmp_settings_class == nullptr) {
            tmp_settings_class = class_from_name(image, "TMPro", "TMP_Settings");
        }
        if (tmp_material_manager_class == nullptr) {
            tmp_material_manager_class = class_from_name(image, "TMPro", "TMP_MaterialManager");
        }
        if (rpg_format_class == nullptr) {
            rpg_format_class = class_from_name(image, "RPGSystem", "RpgLocFormat");
        }
        if (get_language_method == nullptr) {
            void *klass = class_from_name(image, "LocalSave", "LocalGameOptionData");
            if (klass != nullptr) {
                get_language_method = class_get_method_from_name(klass, "GetLanguage", 0);
            }
        }
        if (get_global_language_method == nullptr) {
            void *klass = class_from_name(image, "", "GlobalGameManager");
            if (klass != nullptr) {
                get_global_language_method = class_get_method_from_name(klass, "get_Lang", 0);
            }
        }
    }
    std::sort(managed_method_addresses.begin(), managed_method_addresses.end());
    managed_method_addresses.erase(std::unique(managed_method_addresses.begin(), managed_method_addresses.end()),
                                   managed_method_addresses.end());
    std::sort(acquisition_targets.begin(), acquisition_targets.end(),
              [](const AcquisitionTarget &left, const AcquisitionTarget &right) {
                  return left.name < right.name;
              });
    LT_LOGI("Discovered TextData formatter classes=%zu", acquisition_targets.size());
    void *tmp_pointer = nullptr;
    void *ui_pointer = nullptr;
    if (tmp_method != nullptr) memcpy(&tmp_pointer, tmp_method, sizeof(tmp_pointer));
    if (ui_method != nullptr) memcpy(&ui_pointer, ui_method, sizeof(ui_pointer));
    LT_LOGI("IL2CPP diagnostic assemblies=%zu TMP_Text.set_text info=%p pointer=%p"
            " UI.Text.set_text info=%p pointer=%p indexEntries=%zu",
            assembly_count, tmp_method, tmp_pointer, ui_method, ui_pointer, g_index.size());
    if (class_get_methods != nullptr && method_get_name != nullptr &&
        method_get_param_count != nullptr && method_get_param != nullptr &&
        type_get_name != nullptr && il2cpp_free != nullptr) {
        struct TargetClass { const char *label; void *klass; };
        TargetClass targets[] = {
                {"TMP_Text", tmp_text_class},
                {"TMP_FontAsset", tmp_font_class},
                {"UnityEngine.Font", unity_font_class},
                {"StorySystem.Dialog", story_dialog_class},
        };
        const char *interesting[] = {
                "set_font", "get_font", "CreateFontAsset", "CreateDynamicFontFromOSFont",
                "SetText", "SetCharArray", ".ctor", "SetData"
        };
        for (const TargetClass &target : targets) {
            void *iterator = nullptr;
            const void *method = nullptr;
            while (target.klass != nullptr && (method = class_get_methods(target.klass, &iterator)) != nullptr) {
                const char *method_name = method_get_name(method);
                bool matched = false;
                for (const char *name : interesting) {
                    if (method_name != nullptr && strcmp(method_name, name) == 0) matched = true;
                }
                if (!matched) continue;
                uint32_t parameter_count = method_get_param_count(method);
                std::string signature;
                for (uint32_t parameter = 0; parameter < parameter_count; ++parameter) {
                    char *type_name = type_get_name(method_get_param(method, parameter));
                    if (parameter > 0) signature += ",";
                    signature += type_name == nullptr ? "?" : type_name;
                    if (type_name != nullptr) il2cpp_free(type_name);
                }
                void *pointer = nullptr;
                memcpy(&pointer, method, sizeof(pointer));
                LT_LOGI("IL2CPP method inventory class=%s method=%s params=(%s) info=%p pointer=%p",
                        target.label, method_name, signature.c_str(), method, pointer);
                if (target.klass == tmp_font_class && strcmp(method_name, "CreateFontAsset") == 0 &&
                    signature.find("System.String,") == 0 && parameter_count == 9) {
                    create_tmp_font_method = method;
                }
                if (target.klass == unity_font_class &&
                    strcmp(method_name, "CreateDynamicFontFromOSFont") == 0 &&
                    signature == "System.String,System.Int32") {
                    create_dynamic_font_method = method;
                }
                if (target.klass == tmp_text_class && strcmp(method_name, "SetText") == 0 &&
                    signature == "System.String") {
                    tmp_set_text_one_method = method;
                }
                if (target.klass == tmp_text_class && strcmp(method_name, "SetText") == 0 &&
                    signature == "System.String,System.Boolean") {
                    tmp_set_text_string_method = method;
                }
            }
        }
    }
    if (tmp_text_class != nullptr) {
        tmp_set_font_method = class_get_method_from_name(tmp_text_class, "set_font", 1);
        tmp_get_font_method = class_get_method_from_name(tmp_text_class, "get_font", 0);
    }
    auto find_method_by_signature = [&](void *klass, const char *expected_name,
                                        const char *expected_signature) -> const void * {
        if (klass == nullptr || class_get_methods == nullptr || method_get_name == nullptr ||
            method_get_param_count == nullptr || method_get_param == nullptr ||
            type_get_name == nullptr || il2cpp_free == nullptr) {
            return nullptr;
        }
        void *iterator = nullptr;
        const void *method = nullptr;
        while ((method = class_get_methods(klass, &iterator)) != nullptr) {
            const char *method_name = method_get_name(method);
            if (method_name == nullptr || strcmp(method_name, expected_name) != 0) continue;
            std::string signature;
            uint32_t parameter_count = method_get_param_count(method);
            for (uint32_t parameter = 0; parameter < parameter_count; ++parameter) {
                char *type_name = type_get_name(method_get_param(method, parameter));
                if (parameter > 0) signature += ",";
                signature += type_name == nullptr ? "?" : type_name;
                if (type_name != nullptr) il2cpp_free(type_name);
            }
            if (signature == expected_signature) return method;
        }
        return nullptr;
    };
    const void *tmp_get_font_material_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontMaterial", 0);
    const void *tmp_set_font_material_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontMaterial", 1);
    const void *tmp_font_get_material_method = tmp_font_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_font_class, "get_material", 0);
    const void *tmp_get_shared_material_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontSharedMaterial", 0);
    const void *tmp_set_shared_material_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontSharedMaterial", 1);
    const void *tmp_get_line_spacing_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_lineSpacing", 0);
    const void *tmp_set_line_spacing_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_lineSpacing", 1);
    const void *tmp_get_font_size_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontSize", 0);
    const void *tmp_set_font_size_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontSize", 1);
    const void *tmp_get_font_size_min_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontSizeMin", 0);
    const void *tmp_set_font_size_min_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontSizeMin", 1);
    const void *tmp_get_font_size_max_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontSizeMax", 0);
    const void *tmp_set_font_size_max_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontSizeMax", 1);
    const void *tmp_get_auto_sizing_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_enableAutoSizing", 0);
    const void *tmp_set_auto_sizing_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_enableAutoSizing", 1);
    const void *tmp_get_word_wrapping_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_enableWordWrapping", 0);
    const void *tmp_set_word_wrapping_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_enableWordWrapping", 1);
    const void *tmp_get_overflow_mode_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_overflowMode", 0);
    const void *tmp_set_overflow_mode_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_overflowMode", 1);
    const void *tmp_get_rect_transform_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_rectTransform", 0);
    const void *ui_get_font_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_font", 0);
    const void *ui_set_font_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_font", 1);
    const void *ui_get_line_spacing_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_lineSpacing", 0);
    const void *ui_set_line_spacing_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_lineSpacing", 1);
    const void *ui_get_font_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_fontSize", 0);
    const void *ui_set_font_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_fontSize", 1);
    const void *ui_get_resize_min_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_resizeTextMinSize", 0);
    const void *ui_set_resize_min_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_resizeTextMinSize", 1);
    const void *ui_get_resize_max_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_resizeTextMaxSize", 0);
    const void *ui_set_resize_max_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_resizeTextMaxSize", 1);
    const void *ui_get_resize_best_fit_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_resizeTextForBestFit", 0);
    const void *ui_set_resize_best_fit_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_resizeTextForBestFit", 1);
    const void *ui_get_horizontal_overflow_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_horizontalOverflow", 0);
    const void *ui_set_horizontal_overflow_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_horizontalOverflow", 1);
    const void *ui_get_vertical_overflow_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_verticalOverflow", 0);
    const void *ui_set_vertical_overflow_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_verticalOverflow", 1);
    const void *ui_get_rect_transform_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_rectTransform", 0);
    const void *rect_transform_get_rect_method = rect_transform_class == nullptr ? nullptr :
            class_get_method_from_name(rect_transform_class, "get_rect", 0);
    const void *object_get_name_method = unity_object_class == nullptr ? nullptr :
            class_get_method_from_name(unity_object_class, "get_name", 0);
    const void *component_get_transform_method = component_class == nullptr ? nullptr :
            class_get_method_from_name(component_class, "get_transform", 0);
    const void *transform_get_parent_method = transform_class == nullptr ? nullptr :
            class_get_method_from_name(transform_class, "get_parent", 0);
    const void *material_get_float_method = find_method_by_signature(
            material_class, "GetFloat", "System.String");
    const void *material_set_float_method = find_method_by_signature(
            material_class, "SetFloat", "System.String,System.Single");
    const void *material_get_color_method = find_method_by_signature(
            material_class, "GetColor", "System.String");
    const void *material_set_color_method = find_method_by_signature(
            material_class, "SetColor", "System.String,UnityEngine.Color");
    const void *get_fallback_fonts_method = tmp_settings_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_settings_class, "get_fallbackFontAssets", 0);
    const void *get_fallback_material_method = find_method_by_signature(
            tmp_material_manager_class, "GetFallbackMaterial", "UnityEngine.Material,UnityEngine.Material");
    const void *retain_fallback_material_method = find_method_by_signature(
            tmp_material_manager_class, "AddFallbackMaterialReference", "UnityEngine.Material");
    LT_LOGI("TMP fallback getter class=%p method=%p", tmp_settings_class, get_fallback_fonts_method);
    LT_LOGI("TMP style methods material=%p fontAssetMaterial=%p fontMaterial=%p/%p shared=%p/%p spacing=%p/%p"
            " fontSize=%p/%p min=%p/%p max=%p/%p auto=%p/%p wrap=%p/%p overflow=%p/%p"
            " rect=%p/%p outline=%p/%p/%p/%p",
            material_class, tmp_font_get_material_method,
            tmp_get_font_material_method, tmp_set_font_material_method,
            tmp_get_shared_material_method,
            tmp_set_shared_material_method, tmp_get_line_spacing_method,
            tmp_set_line_spacing_method, tmp_get_font_size_method, tmp_set_font_size_method,
            tmp_get_font_size_min_method, tmp_set_font_size_min_method,
            tmp_get_font_size_max_method, tmp_set_font_size_max_method,
            tmp_get_auto_sizing_method, tmp_set_auto_sizing_method,
            tmp_get_word_wrapping_method, tmp_set_word_wrapping_method,
            tmp_get_overflow_mode_method, tmp_set_overflow_mode_method,
            tmp_get_rect_transform_method, rect_transform_get_rect_method,
            material_get_float_method, material_set_float_method,
            material_get_color_method, material_set_color_method);
    LT_LOGI("UI.Text style methods font=%p/%p spacing=%p/%p fontSize=%p/%p min=%p/%p"
            " max=%p/%p bestFit=%p/%p horizontal=%p/%p vertical=%p/%p rect=%p/%p",
            ui_get_font_method, ui_set_font_method,
            ui_get_line_spacing_method, ui_set_line_spacing_method,
            ui_get_font_size_method, ui_set_font_size_method,
            ui_get_resize_min_size_method, ui_set_resize_min_size_method,
            ui_get_resize_max_size_method, ui_set_resize_max_size_method,
            ui_get_resize_best_fit_method, ui_set_resize_best_fit_method,
            ui_get_horizontal_overflow_method, ui_set_horizontal_overflow_method,
            ui_get_vertical_overflow_method, ui_set_vertical_overflow_method,
            ui_get_rect_transform_method, rect_transform_get_rect_method);
    LT_LOGI("Unity hierarchy methods name=%p transform=%p parent=%p",
            object_get_name_method, component_get_transform_method,
            transform_get_parent_method);
    if (tmp_font_class != nullptr && create_tmp_font_method == nullptr) {
        create_tmp_font_method = class_get_method_from_name(tmp_font_class, "CreateFontAsset", 9);
    }
    if (unity_font_class != nullptr && create_dynamic_font_method == nullptr) {
        create_dynamic_font_method =
                class_get_method_from_name(unity_font_class, "CreateDynamicFontFromOSFont", 2);
    }
    void *tmp_set_font_pointer = nullptr;
    void *tmp_get_font_pointer = nullptr;
    void *create_tmp_font_pointer = nullptr;
    void *create_dynamic_font_pointer = nullptr;
    void *get_language_pointer = nullptr;
    void *get_global_language_pointer = nullptr;
    if (tmp_set_font_method != nullptr) memcpy(&tmp_set_font_pointer, tmp_set_font_method, sizeof(void *));
    if (tmp_get_font_method != nullptr) memcpy(&tmp_get_font_pointer, tmp_get_font_method, sizeof(void *));
    if (create_tmp_font_method != nullptr) memcpy(&create_tmp_font_pointer, create_tmp_font_method, sizeof(void *));
    if (create_dynamic_font_method != nullptr) memcpy(&create_dynamic_font_pointer, create_dynamic_font_method, sizeof(void *));
    if (get_language_method != nullptr) memcpy(&get_language_pointer, get_language_method, sizeof(void *));
    if (get_global_language_method != nullptr) {
        memcpy(&get_global_language_pointer, get_global_language_method, sizeof(void *));
    }
    bool hook_ready = gc.ready() &&
            tmp_pointer != nullptr && tmp_set_font_pointer != nullptr &&
            tmp_get_font_pointer != nullptr &&
            create_tmp_font_pointer != nullptr && create_dynamic_font_pointer != nullptr &&
            get_global_language_pointer != nullptr &&
            string_length != nullptr && string_chars != nullptr && string_new_utf16 != nullptr &&
            runtime_invoke != nullptr && object_get_class != nullptr && object_unbox != nullptr;
    if (hook_ready && !g_hook_installed) {
        g_gc = gc;
        g_string_length = string_length;
        g_string_chars = string_chars;
        g_string_new_utf16 = string_new_utf16;
        g_runtime_invoke = runtime_invoke;
        g_object_get_class = object_get_class;
        g_object_unbox = object_unbox;
        g_class_get_method = class_get_method_from_name;
        g_class_get_methods = class_get_methods;
        g_method_get_name = method_get_name;
        g_method_get_param_count = method_get_param_count;
        g_method_get_param = method_get_param;
        g_class_get_parent = class_get_parent;
        g_class_get_fields = class_get_fields;
        g_field_get_name = field_get_name;
        g_field_get_type = field_get_type;
        g_field_get_value = field_get_value;
        g_field_set_value = field_set_value;
        g_type_get_name = type_get_name;
        g_il2cpp_free = il2cpp_free;
        g_create_dynamic_font = reinterpret_cast<create_dynamic_font_fn>(create_dynamic_font_pointer);
        g_create_tmp_font = reinterpret_cast<create_tmp_font_fn>(create_tmp_font_pointer);
        g_tmp_set_font = reinterpret_cast<tmp_set_font_fn>(tmp_set_font_pointer);
        g_tmp_get_font = reinterpret_cast<tmp_get_font_fn>(tmp_get_font_pointer);
        g_create_dynamic_font_info = create_dynamic_font_method;
        g_create_tmp_font_info = create_tmp_font_method;
        g_tmp_set_font_info = tmp_set_font_method;
        g_tmp_get_font_info = tmp_get_font_method;
        g_get_fallback_fonts_info = get_fallback_fonts_method;
        g_tmp_font_get_material_info = tmp_font_get_material_method;
        g_tmp_get_font_material_info = tmp_get_font_material_method;
        g_get_fallback_material_info = get_fallback_material_method;
        g_retain_fallback_material_info = retain_fallback_material_method;
        g_tmp_get_shared_material_info = tmp_get_shared_material_method;
        g_tmp_set_shared_material_info = tmp_set_shared_material_method;
        g_tmp_get_line_spacing_info = tmp_get_line_spacing_method;
        g_tmp_set_line_spacing_info = tmp_set_line_spacing_method;
        g_tmp_get_font_size_info = tmp_get_font_size_method;
        g_tmp_set_font_size_info = tmp_set_font_size_method;
        g_tmp_get_font_size_min_info = tmp_get_font_size_min_method;
        g_tmp_set_font_size_min_info = tmp_set_font_size_min_method;
        g_tmp_get_font_size_max_info = tmp_get_font_size_max_method;
        g_tmp_set_font_size_max_info = tmp_set_font_size_max_method;
        g_tmp_get_auto_sizing_info = tmp_get_auto_sizing_method;
        g_tmp_set_auto_sizing_info = tmp_set_auto_sizing_method;
        g_tmp_get_word_wrapping_info = tmp_get_word_wrapping_method;
        g_tmp_set_word_wrapping_info = tmp_set_word_wrapping_method;
        g_tmp_get_overflow_mode_info = tmp_get_overflow_mode_method;
        g_tmp_set_overflow_mode_info = tmp_set_overflow_mode_method;
        g_tmp_get_rect_transform_info = tmp_get_rect_transform_method;
        g_ui_get_font_info = ui_get_font_method;
        g_ui_set_font_info = ui_set_font_method;
        g_ui_get_line_spacing_info = ui_get_line_spacing_method;
        g_ui_set_line_spacing_info = ui_set_line_spacing_method;
        g_ui_get_font_size_info = ui_get_font_size_method;
        g_ui_set_font_size_info = ui_set_font_size_method;
        g_ui_get_resize_min_size_info = ui_get_resize_min_size_method;
        g_ui_set_resize_min_size_info = ui_set_resize_min_size_method;
        g_ui_get_resize_max_size_info = ui_get_resize_max_size_method;
        g_ui_set_resize_max_size_info = ui_set_resize_max_size_method;
        g_ui_get_resize_best_fit_info = ui_get_resize_best_fit_method;
        g_ui_set_resize_best_fit_info = ui_set_resize_best_fit_method;
        g_ui_get_horizontal_overflow_info = ui_get_horizontal_overflow_method;
        g_ui_set_horizontal_overflow_info = ui_set_horizontal_overflow_method;
        g_ui_get_vertical_overflow_info = ui_get_vertical_overflow_method;
        g_ui_set_vertical_overflow_info = ui_set_vertical_overflow_method;
        g_ui_get_rect_transform_info = ui_get_rect_transform_method;
        g_rect_transform_get_rect_info = rect_transform_get_rect_method;
        g_object_get_name_info = object_get_name_method;
        g_object_get_instance_id_info = find_method_by_signature(unity_object_class, "GetInstanceID", "");
        g_object_is_alive_info = find_method_by_signature(unity_object_class, "op_Implicit", "UnityEngine.Object");
        g_original_tmp_states.set_alive(unity_object_alive);
        g_original_ui_states.set_alive(unity_object_alive);
        g_component_get_transform_info = component_get_transform_method;
        g_transform_get_parent_info = transform_get_parent_method;
        g_transform_get_child_info = find_method_by_signature(transform_class, "GetChild", "System.Int32");
        g_transform_get_child_count_info = find_method_by_signature(transform_class, "get_childCount", "");
        g_component_get_component_info = find_method_by_signature(component_class, "GetComponent", "System.Type");
        g_behaviour_get_active_info = find_method_by_signature(behaviour_class, "get_enabled", "");
        g_component_get_game_object_info = find_method_by_signature(component_class, "get_gameObject", "");
        g_game_object_get_active_info = find_method_by_signature(game_object_class, "get_activeSelf", "");
        auto class_get_type = resolver.resolve<const void *(*)(void *)>("il2cpp_class_get_type");
        auto type_get_object = resolver.resolve<void *(*)(const void *)>("il2cpp_type_get_object");
        if (class_get_type && type_get_object && tmp_text_class) g_tmp_runtime_type = type_get_object(class_get_type(tmp_text_class));
        append_native_metadata("font-state.log", g_transform_get_child_info && g_transform_get_child_count_info &&
                g_component_get_component_info && g_behaviour_get_active_info && g_tmp_runtime_type &&
                g_component_get_game_object_info && g_game_object_get_active_info ?
                "fragment_group_apis=1" : "fragment_group_apis=0");
        g_material_get_float_info = material_get_float_method;
        g_material_set_float_info = material_set_float_method;
        g_material_get_color_info = material_get_color_method;
        g_material_set_color_info = material_set_color_method;
        hook_limbus_owned_function(tmp_pointer, reinterpret_cast<void *>(replacement_tmp_set_text),
                       reinterpret_cast<void **>(&g_original_tmp_set_text));
        auto has_inline_hook_space = [&](void *klass, void *pointer) {
            if (klass == nullptr || pointer == nullptr) return false;
            uintptr_t address = reinterpret_cast<uintptr_t>(pointer);
            auto position = std::lower_bound(managed_method_addresses.begin(), managed_method_addresses.end(), address);
            return position != managed_method_addresses.end() && *position == address &&
                    position + 1 != managed_method_addresses.end() &&
                    *(position + 1) - address >= ((address & 7u) ? 20u : 16u);
        };
        const void *announcer_set = find_method_by_signature(announcer_ui_class, "SetDescription", "System.String");
        if (announcer_set && method_get_flags && method_get_return_type && class_get_fields &&
            field_get_name && field_get_type && field_get_value && g_object_get_instance_id_info) {
            uint32_t flags = 0;
            char *returns = type_get_name(method_get_return_type(announcer_set));
            bool valid = returns && strcmp(returns, "System.Void") == 0 &&
                         (method_get_flags(announcer_set, &flags) & 0x0010u) == 0;
            if (returns) il2cpp_free(returns);
            void *iterator = nullptr;
            while (const void *field = class_get_fields(announcer_ui_class, &iterator)) {
                const char *name = field_get_name(field);
                if (!name || strcmp(name, "tmp_announce") != 0) continue;
                char *type = type_get_name(field_get_type(field));
                if (type && (strcmp(type, "TMPro.TextMeshProUGUI") == 0 || strcmp(type, "TMPro.TMP_Text") == 0))
                    g_announcer_description_field = field;
                if (type) il2cpp_free(type);
            }
            void *pointer = nullptr;
            memcpy(&pointer, announcer_set, sizeof(pointer));
            if (valid && g_announcer_description_field && has_inline_hook_space(announcer_ui_class, pointer))
                hook_limbus_owned_function(pointer, reinterpret_cast<void *>(replacement_announcer_set_description),
                                           reinterpret_cast<void **>(&g_original_announcer_set_description));
        }
        append_native_metadata("font-state.log", g_original_announcer_set_description ?
                               "announcer_pre_layout=1" : "announcer_pre_layout=0");
        auto choice_field_flags = resolver.resolve<uint32_t (*)(const void *)>("il2cpp_field_get_flags");
        auto choice_field = [&](void *klass, const char *wanted, const char *expected) -> const void * {
            if (!klass || !class_get_fields || !field_get_name || !field_get_type ||
                !field_get_value || !field_set_value || !choice_field_flags) return nullptr;
            void *iterator = nullptr;
            while (const void *field = class_get_fields(klass, &iterator)) {
                const char *name = field_get_name(field);
                if (!name || strcmp(name, wanted) != 0 || (choice_field_flags(field) & 0x0010u)) continue;
                char *type = type_get_name(field_get_type(field));
                bool valid = type && strcmp(type, expected) == 0;
                if (type) il2cpp_free(type);
                if (valid) return field;
            }
            return nullptr;
        };
        g_choice_result_log_field = choice_field(g_choice_result_log_class, "_resultLog", "System.String");
        g_choice_button_text_field = choice_field(choice_button_class, "tmp_button", "TMPro.TextMeshProUGUI");
        g_choice_button_result_field = choice_field(choice_button_class, "_activeResultDesc", "TMPro.TextMeshProUGUI");
        auto hook_choice_method = [&](void *klass, const char *name, const char *signature, const char *expected_return,
                                      void *replacement, void **original) {
            const void *entry = find_method_by_signature(klass, name, signature);
            if (!entry || !method_get_flags || !method_get_return_type || !g_object_get_instance_id_info) return;
            uint32_t flags = 0;
            char *returns = type_get_name(method_get_return_type(entry));
            bool valid = returns && strcmp(returns, expected_return) == 0 &&
                    (method_get_flags(entry, &flags) & 0x0010u) == 0;
            if (returns) il2cpp_free(returns);
            void *pointer = nullptr;
            memcpy(&pointer, entry, sizeof(pointer));
            if (valid && has_inline_hook_space(klass, pointer))
                hook_limbus_owned_function(pointer, replacement, original);
        };
        hook_choice_method(choice_scroll_class, "ReadingOneLineAtATime", "TMPro.TextMeshProUGUI,System.String",
                "System.Collections.IEnumerator", reinterpret_cast<void *>(replacement_choice_read_string),
                reinterpret_cast<void **>(&g_original_choice_read_string));
        for (const char *list_type : {"System.Collections.Generic.List<ChoiceEventResultLog>",
                                     "System.Collections.Generic.List`1<ChoiceEventResultLog>"}) {
            if (!g_choice_result_log_field) break;
            if (!g_original_choice_read_list) {
                auto signature = std::string("TMPro.TextMeshProUGUI,") + list_type;
                hook_choice_method(choice_scroll_class, "ReadingOneLineAtATime", signature.c_str(), "System.Collections.IEnumerator",
                        reinterpret_cast<void *>(replacement_choice_read_list),
                        reinterpret_cast<void **>(&g_original_choice_read_list));
            }
            if (!g_original_choice_set_total)
                hook_choice_method(choice_scroll_class, "SetTotalContent", list_type, "System.Void",
                        reinterpret_cast<void *>(replacement_choice_set_total),
                        reinterpret_cast<void **>(&g_original_choice_set_total));
        }
        if (!g_original_choice_button && g_choice_button_text_field && g_choice_button_result_field) {
            hook_choice_method(choice_button_class, "SetButtonData", "System.Int32,System.String,System.String",
                    "System.Void", reinterpret_cast<void *>(replacement_choice_button),
                    reinterpret_cast<void **>(&g_original_choice_button));
        }
        hook_choice_method(choice_scroll_class, "Clear", "", "System.Void", reinterpret_cast<void *>(replacement_choice_clear),
                reinterpret_cast<void **>(&g_original_choice_clear));
        char choice_hooks[160];
        snprintf(choice_hooks, sizeof(choice_hooks), "choice_flow total=%d string=%d list=%d clear=%d button=%d log_field=%d",
                g_original_choice_set_total != nullptr, g_original_choice_read_string != nullptr,
                g_original_choice_read_list != nullptr, g_original_choice_clear != nullptr,
                g_original_choice_button != nullptr, g_choice_result_log_field != nullptr);
        append_native_metadata("font-state.log", choice_hooks);
        for (void *choice_class : {choice_scroll_class, choice_button_class}) {
        if (choice_class && class_get_methods && method_get_name && method_get_param_count &&
            method_get_param && type_get_name && il2cpp_free) {
            void *iterator = nullptr;
            while (const void *entry = class_get_methods(choice_class, &iterator)) {
                const char *name = method_get_name(entry);
                if (!name || (strcmp(name, "SetTotalContent") != 0 && strcmp(name, "ReadingOneLineAtATime") != 0 &&
                              strcmp(name, "SetButtonData") != 0)) continue;
                std::string signature = std::string("choice_signature ") + name;
                for (uint32_t p = 0; p < method_get_param_count(entry) && p < 3; ++p) {
                    char *type = type_get_name(method_get_param(entry, p));
                    signature += std::string(" ") + (type ? type : "?");
                    if (type) il2cpp_free(type);
                }
                if (signature.size() < 512) append_native_metadata("font-state.log", signature.c_str());
            }
        }
        }
        const void *story_ctor = find_method_by_signature(story_dialog_class, ".ctor",
                "System.Int32,System.Int32,SimpleJSON.JSONNode,SimpleJSON.JSONNode,System.Single");
        const void *rpg_format = find_method_by_signature(rpg_format_class, "Apply",
                "System.String,System.Object[]");
        uint32_t rpg_flags = 0;
        if (rpg_format != nullptr && method_get_flags != nullptr && method_get_return_type != nullptr &&
            (method_get_flags(rpg_format, &rpg_flags) & 0x0010u) != 0) {
            char *return_type = type_get_name(method_get_return_type(rpg_format));
            bool returns_string = return_type != nullptr && strcmp(return_type, "System.String") == 0;
            if (return_type != nullptr) il2cpp_free(return_type);
            void *pointer = nullptr;
            memcpy(&pointer, rpg_format, sizeof(pointer));
            if (returns_string && has_inline_hook_space(rpg_format_class, pointer)) {
                hook_limbus_owned_function(pointer, reinterpret_cast<void *>(replacement_rpg_format),
                                           reinterpret_cast<void **>(&g_original_rpg_format));
            }
        }
        LT_LOGI("RPG localized template formatter installed=%d", g_original_rpg_format != nullptr);
        void *story_ctor_pointer = nullptr;
        if (story_ctor != nullptr) memcpy(&story_ctor_pointer, story_ctor, sizeof(story_ctor_pointer));
        if (has_inline_hook_space(story_dialog_class, story_ctor_pointer)) {
            hook_limbus_owned_function(story_ctor_pointer,
                    reinterpret_cast<void *>(replacement_story_dialog_ctor),
                    reinterpret_cast<void **>(&g_original_story_dialog_ctor));
        }
        LT_LOGI("Story dialog hook installed=%d", g_original_story_dialog_ctor != nullptr);
        const void *parse_input_method = class_get_method_from_name(
                tmp_text_class, "ParseInputText", 0);
        g_tmp_get_text_info = class_get_method_from_name(tmp_text_class, "get_text", 0);
        g_tmp_set_text_info = tmp_method;
        void *parse_input_pointer = nullptr;
        if (parse_input_method != nullptr) {
            memcpy(&parse_input_pointer, parse_input_method, sizeof(parse_input_pointer));
        }
        if (g_tmp_get_text_info != nullptr &&
            has_inline_hook_space(tmp_text_class, parse_input_pointer)) {
            hook_limbus_owned_function(parse_input_pointer,
                    reinterpret_cast<void *>(replacement_tmp_parse_input),
                    reinterpret_cast<void **>(&g_original_tmp_parse_input));
        }
        LT_LOGI("TMP render-boundary hook parser=%p/%p getter=%p",
                parse_input_pointer, reinterpret_cast<void *>(g_original_tmp_parse_input),
                g_tmp_get_text_info);
        void *set_shared_material_pointer = nullptr;
        if (tmp_set_shared_material_method != nullptr) {
            memcpy(&set_shared_material_pointer, tmp_set_shared_material_method,
                   sizeof(set_shared_material_pointer));
        }
        if (set_shared_material_pointer != nullptr &&
            has_inline_hook_space(tmp_text_class, set_shared_material_pointer)) {
            hook_limbus_owned_function(set_shared_material_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_shared_material),
                           reinterpret_cast<void **>(&g_original_tmp_set_shared_material));
        } else if (set_shared_material_pointer != nullptr) {
            LT_LOGW("Skip unsafe adjacent TMP shared material setter=%p",
                    set_shared_material_pointer);
        }
        void *set_material_pointer = nullptr;
        if (tmp_set_font_material_method != nullptr) {
            memcpy(&set_material_pointer, tmp_set_font_material_method,
                   sizeof(set_material_pointer));
        }
        if (set_material_pointer != nullptr &&
            set_material_pointer != set_shared_material_pointer &&
            has_inline_hook_space(tmp_text_class, set_material_pointer)) {
            hook_limbus_owned_function(set_material_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_material),
                           reinterpret_cast<void **>(&g_original_tmp_set_material));
        } else if (set_material_pointer != nullptr &&
                   set_material_pointer != set_shared_material_pointer) {
            LT_LOGW("Skip unsafe adjacent TMP material setter=%p", set_material_pointer);
        }
        LT_LOGI("TMP material hooks shared=%p/%p instance=%p/%p",
                set_shared_material_pointer,
                reinterpret_cast<void *>(g_original_tmp_set_shared_material),
                set_material_pointer,
                reinterpret_cast<void *>(g_original_tmp_set_material));
        if (ui_pointer != nullptr && has_inline_hook_space(ui_text_class, ui_pointer)) {
            hook_limbus_owned_function(ui_pointer, reinterpret_cast<void *>(replacement_ui_set_text),
                           reinterpret_cast<void **>(&g_original_ui_set_text));
        } else if (ui_pointer != nullptr) {
            LT_LOGW("Skip unsafe adjacent UI.Text.set_text pointer=%p", ui_pointer);
        }
        void *set_text_one_pointer = nullptr;
        if (tmp_set_text_one_method != nullptr) {
            memcpy(&set_text_one_pointer, tmp_set_text_one_method, sizeof(void *));
            hook_limbus_owned_function(set_text_one_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_text_one),
                           reinterpret_cast<void **>(&g_original_tmp_set_text_one));
        }
        void *set_text_string_pointer = nullptr;
        if (tmp_set_text_string_method != nullptr) {
            memcpy(&set_text_string_pointer, tmp_set_text_string_method, sizeof(void *));
            hook_limbus_owned_function(set_text_string_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_text_string),
                           reinterpret_cast<void **>(&g_original_tmp_set_text_string));
        }
        if (get_language_pointer != nullptr) {
            hook_limbus_owned_function(get_language_pointer,
                           reinterpret_cast<void *>(replacement_get_language),
                           reinterpret_cast<void **>(&g_original_get_language));
        }
        hook_limbus_owned_function(get_global_language_pointer,
                       reinterpret_cast<void *>(replacement_global_get_language),
                       reinterpret_cast<void **>(&g_original_global_get_language));
        size_t acquisition_counts[] = {0, 0, 0};
        std::unordered_set<void *> acquisition_pointers;
        bool acquisition_ready = class_get_methods != nullptr && method_get_name != nullptr &&
                method_get_param_count != nullptr && method_get_param != nullptr &&
                method_get_return_type != nullptr &&
                method_get_flags != nullptr &&
                type_get_name != nullptr && il2cpp_free != nullptr &&
                class_get_parent != nullptr && class_get_fields != nullptr &&
                field_get_name != nullptr && field_get_type != nullptr &&
                field_get_value != nullptr && field_set_value != nullptr;
        if (acquisition_ready) {
            for (const AcquisitionTarget &target : acquisition_targets) {
                void *iterator = nullptr;
                const void *method = nullptr;
                while (target.klass != nullptr &&
                       (method = class_get_methods(target.klass, &iterator)) != nullptr) {
                    const char *method_name = method_get_name(method);
                    if (method_name == nullptr ||
                        (strncmp(method_name, "Get", 3) != 0 &&
                         strncmp(method_name, "get_", 4) != 0)) {
                        continue;
                    }
                    char *return_name = type_get_name(method_get_return_type(method));
                    bool returns_string = return_name != nullptr &&
                            strcmp(return_name, "System.String") == 0;
                    if (return_name != nullptr) il2cpp_free(return_name);
                    uint32_t parameter_count = method_get_param_count(method);
                    // Zero-argument methods can format too (for example, stripping
                    // title newlines). Select by return type, instance ABI and safe
                    // body size, not by a page/class whitelist or argument count > 0.
                    // Static methods lack `this` and cannot use these wrappers.
                    uint32_t implementation_flags = 0;
                    bool selected_formatter = parameter_count <= 2 &&
                            (method_get_flags(method, &implementation_flags) & 0x0010u) == 0;
                    for (uint32_t arg = 0; selected_formatter && arg < parameter_count; ++arg) {
                        char *parameter_type = type_get_name(method_get_param(method, arg));
                        bool compatible = limbus_text_provenance::is_gp_formatter_parameter(parameter_type);
                        if (!compatible) {
                            LT_LOGW("Skip unsupported formatter ABI class=%s method=%s argument=%s",
                                    target.name.c_str(), method_name,
                                    parameter_type == nullptr ? "unknown" : parameter_type);
                        }
                        if (parameter_type != nullptr) il2cpp_free(parameter_type);
                        selected_formatter = compatible;
                    }
                    // The spacing check below still rejects adjacent 4-8 byte
                    // getters; those keep the final TMP/UI exact-match fallback.
                    if (!returns_string || !selected_formatter) continue;
                    void *pointer = nullptr;
                    memcpy(&pointer, method, sizeof(pointer));
                    if (pointer == nullptr || acquisition_pointers.count(pointer) != 0 ||
                        acquisition_counts[parameter_count] >= kAcquisitionSlots) {
                        continue;
                    }
                    if (!has_inline_hook_space(target.klass, pointer)) {
                        LT_LOGW("Skip unsafe adjacent text formatter class=%s method=%s pointer=%p",
                                target.name.c_str(), method_name, pointer);
                        continue;
                    }
                    size_t slot = acquisition_counts[parameter_count];
                    if (parameter_count == 0) {
                        hook_limbus_owned_function(pointer,
                                reinterpret_cast<void *>(kAcquisition0Replacements[slot]),
                                reinterpret_cast<void **>(&g_acquisition0[slot]));
                        if (g_acquisition0[slot] == nullptr) continue;
                    } else if (parameter_count == 1) {
                        hook_limbus_owned_function(pointer,
                                reinterpret_cast<void *>(kAcquisition1Replacements[slot]),
                                reinterpret_cast<void **>(&g_acquisition1[slot]));
                        if (g_acquisition1[slot] == nullptr) continue;
                    } else {
                        hook_limbus_owned_function(pointer,
                                reinterpret_cast<void *>(kAcquisition2Replacements[slot]),
                                reinterpret_cast<void **>(&g_acquisition2[slot]));
                        if (g_acquisition2[slot] == nullptr) continue;
                    }
                    acquisition_pointers.insert(pointer);
                    acquisition_counts[parameter_count]++;
                    LT_LOGI("Text acquisition hook class=%s method=%s argc=%u pointer=%p",
                            target.name.c_str(), method_name, parameter_count, pointer);
                }
            }
        }
        g_hook_installed = g_original_tmp_set_text != nullptr;
        LT_LOGI("TMP translation hook installed=%d setter=%p/%p SetText1=%p/%p SetText2=%p/%p",
                g_hook_installed, tmp_pointer, reinterpret_cast<void *>(g_original_tmp_set_text),
                set_text_one_pointer, reinterpret_cast<void *>(g_original_tmp_set_text_one),
                set_text_string_pointer, reinterpret_cast<void *>(g_original_tmp_set_text_string));
        LT_LOGI("UI.Text translation hook setter=%p/%p",
                ui_pointer, reinterpret_cast<void *>(g_original_ui_set_text));
        LT_LOGI("Japanese language hooks installed option=%p/%p global=%p/%p",
                get_language_pointer, reinterpret_cast<void *>(g_original_get_language),
                get_global_language_pointer,
                reinterpret_cast<void *>(g_original_global_get_language));
        LT_LOGI("Text acquisition hooks installed argc0=%zu argc1=%zu argc2=%zu ready=%d",
                acquisition_counts[0], acquisition_counts[1], acquisition_counts[2],
                acquisition_ready);
    } else if (!hook_ready) {
        LT_LOGW("TMP translation hook prerequisites missing text=%p setFont=%p createTmp=%p"
                " createDynamic=%p language=%p/%p strings=%p/%p/%p invoke=%p",
                tmp_pointer, tmp_set_font_pointer, create_tmp_font_pointer,
                create_dynamic_font_pointer, get_language_pointer, get_global_language_pointer,
                reinterpret_cast<void *>(string_length),
                reinterpret_cast<void *>(string_chars), reinterpret_cast<void *>(string_new_utf16),
                reinterpret_cast<void *>(runtime_invoke));
    }
    return tmp_method != nullptr && tmp_pointer != nullptr;
}

void *replacement_il2cpp_init(const char *domain_name) {
    void *domain = g_original_il2cpp_init(domain_name);
    g_unity_init_thread = pthread_self();
    MappedElfResolver resolver;
    bool resolved = resolver.open(g_il2cpp_path) && report_il2cpp_resolver(resolver, true);
    pthread_mutex_lock(&g_lock);
    g_resolver_reported = resolved;
    write_runtime_receipt(g_index.empty() ? "no_index"
            : (resolved && g_hook_installed ? "hooks_installed" : "hook_setup_incomplete"));
    pthread_mutex_unlock(&g_lock);
    LT_LOGI("IL2CPP init boundary domain=%p translationReady=%d path=%s",
            domain, resolved, g_il2cpp_path);
    return domain;
}

void *replacement_unity_dlsym(void *handle, const char *name) {
    void *resolved = g_original_unity_dlsym(handle, name);
    if (name != nullptr && strcmp(name, "il2cpp_init") == 0 && resolved != nullptr) {
        g_original_il2cpp_init = reinterpret_cast<il2cpp_init_fn>(resolved);
        LT_LOGI("Unity requested il2cpp_init original=%p replacement=%p",
                resolved, reinterpret_cast<void *>(replacement_il2cpp_init));
        return reinterpret_cast<void *>(replacement_il2cpp_init);
    }
    return resolved;
}

bool install_global_dlsym_lifecycle_hook() {
    pthread_mutex_lock(&g_lock);
    if (g_probe_started) {
        pthread_mutex_unlock(&g_lock);
        return true;
    }
    pthread_mutex_unlock(&g_lock);
    void *target = dlsym(RTLD_DEFAULT, "dlsym");
    if (target == nullptr) return false;
    hook_limbus_owned_function(target, reinterpret_cast<void *>(replacement_unity_dlsym),
                   reinterpret_cast<void **>(&g_original_unity_dlsym));
    if (g_original_unity_dlsym == nullptr) {
        LT_LOGW("Unable to hook dlsym lifecycle target=%p", target);
        return false;
    }
    pthread_mutex_lock(&g_lock);
    g_probe_started = true;
    const char *real_lib_dir = getenv("V_LIMBUS_REAL_LIB_DIR");
    if (real_lib_dir != nullptr) {
        snprintf(g_il2cpp_path, sizeof(g_il2cpp_path), "%s/libil2cpp.so", real_lib_dir);
    }
    pthread_mutex_unlock(&g_lock);
    LT_LOGI("Hooked dlsym lifecycle target=%p trampoline=%p",
            target, reinterpret_cast<void *>(g_original_unity_dlsym));
    return true;
}

int mapping_protection(void *address) {
    FILE *maps = fopen("/proc/self/maps", "r");
    char line[PATH_MAX + 160] = {};
    uintptr_t target = reinterpret_cast<uintptr_t>(address);
    int protection = PROT_READ;
    while (maps != nullptr && fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long start = 0;
        unsigned long end = 0;
        char permissions[5] = {};
        if (sscanf(line, "%lx-%lx %4s", &start, &end, permissions) == 3 &&
            target >= start && target < end) {
            protection = 0;
            if (permissions[0] == 'r') protection |= PROT_READ;
            if (permissions[1] == 'w') protection |= PROT_WRITE;
            if (permissions[2] == 'x') protection |= PROT_EXEC;
            break;
        }
    }
    if (maps != nullptr) fclose(maps);
    return protection;
}

bool install_unity_dlsym_redirect(const char *il2cpp_path) {
    if (il2cpp_path == nullptr) return false;
    pthread_mutex_lock(&g_lock);
    if (g_probe_started) {
        pthread_mutex_unlock(&g_lock);
        return true;
    }
    pthread_mutex_unlock(&g_lock);
    std::string unity_path(il2cpp_path);
    size_t slash = unity_path.rfind('/');
    if (slash == std::string::npos) return false;
    unity_path.replace(slash + 1, std::string::npos, "libunity.so");
    MappedElfResolver resolver;
    if (!resolver.open(unity_path.c_str())) return false;
    void **slot = resolver.resolve_import_slot("dlsym");
    if (slot == nullptr || *slot == nullptr) return false;
    long page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page = reinterpret_cast<uintptr_t>(slot) & ~(static_cast<uintptr_t>(page_size) - 1);
    int original_protection = mapping_protection(slot);
    if (mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(page_size),
                 original_protection | PROT_WRITE) != 0) {
        LT_LOGW("Unable to write Unity dlsym import slot=%p errno=%d", slot, errno);
        return false;
    }
    g_original_unity_dlsym = reinterpret_cast<dlsym_fn>(*slot);
    __atomic_store_n(slot, reinterpret_cast<void *>(replacement_unity_dlsym), __ATOMIC_RELEASE);
    mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(page_size), original_protection);
    pthread_mutex_lock(&g_lock);
    g_probe_started = true;
    snprintf(g_il2cpp_path, sizeof(g_il2cpp_path), "%s", il2cpp_path);
    pthread_mutex_unlock(&g_lock);
    LT_LOGI("Redirected Unity dlsym import slot=%p original=%p replacement=%p",
            slot, reinterpret_cast<void *>(g_original_unity_dlsym),
            reinterpret_cast<void *>(replacement_unity_dlsym));
    return true;
}

}  // namespace

void record_limbus_integrity_diagnostic(bool protection, bool accepted) {
    char event[100];
    snprintf(event, sizeof(event), "scope=%s owned_changes_only=%d", protection ? "protection" : "il2cpp", accepted);
    append_native_metadata("integrity-state.log", event);
}

void record_limbus_integrity_detail(const char *detail) {
    append_native_metadata("integrity-state.log", detail);
}

void configure_limbus_translation_runtime(const char *active_index_pointer) {
    if (active_index_pointer == nullptr || active_index_pointer[0] != '/') {
        LT_LOGW("Reject invalid active index pointer");
        return;
    }
    pthread_mutex_lock(&g_lock);
    snprintf(g_active_pointer, sizeof(g_active_pointer), "%s", active_index_pointer);
    g_configured = true;
    g_resolver_reported = false;
    g_probe_started = false;
    g_il2cpp_path[0] = 0;
    g_font_catalogue.clear();
    g_index.clear();
    g_contextual_index.clear();
    g_terms.clear();
    g_package_line_breaks.clear();
    g_term_trie.clear();
    const bool loaded = load_active_index();
    write_runtime_receipt(loaded ? "index_loaded" : "no_index");
    pthread_mutex_unlock(&g_lock);
}

void activate_limbus_translation_lifecycle_hook() {
    if (!g_configured) return;
    install_global_dlsym_lifecycle_hook();
}

void on_limbus_translation_library_loaded(const char *name, void *handle) {
    if (handle != nullptr) probe_limbus_translation_runtime(name);
}

void probe_limbus_translation_runtime(const char *library_path) {
    if (!g_configured || library_path == nullptr || library_path[0] != '/' ||
        strstr(library_path, "libil2cpp.so") == nullptr) return;
    pthread_mutex_lock(&g_lock);
    snprintf(g_il2cpp_path, sizeof(g_il2cpp_path), "%s", library_path);
    pthread_mutex_unlock(&g_lock);
}
