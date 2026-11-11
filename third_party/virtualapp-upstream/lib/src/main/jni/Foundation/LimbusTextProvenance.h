#pragma once
#include <algorithm>
#include <string>
#include <string_view>
#include <cstring>
#include <cmath>
#include <utility>
#include "LimbusRichText.h"

namespace limbus_text_provenance {
// Own only values we actually change. A later game write becomes the new
// baseline; neither recycled controls nor translated refreshes replay old state.
template<class T> struct LayoutOverride {
    T original{}, applied{};
    bool active = false;
    T observe(T current) {
        if (!active || current != applied) { original = current; active = false; }
        return original;
    }
    void record(T value) { applied = value; active = value != original; }
    bool restore(T current) const { return active && current == applied; }
};
inline bool skill_number(const std::string &id) {
    return !id.empty() && id.size() <= 18 &&
            std::all_of(id.begin(), id.end(), [](char c) { return c >= '0' && c <= '9'; });
}
inline std::string skill_root_key(const std::string &id) {
    return skill_number(id) ? "@skill:" + id : std::string();
}
inline std::string gift_root_key(const std::string &id) {
    return skill_number(id) ? "@ego-gift:" + id : std::string();
}
inline std::string gift_ability_key(const std::string &parent, const std::string &id) {
    return parent.compare(0, 10, "@ego-gift:") == 0 &&
            skill_number(parent.substr(10)) && skill_number(id)
            ? parent + "/ability:" + id : std::string();
}
inline std::string event_root_key(const std::string &id) {
    return skill_number(id) ? "@action-event:" + id : std::string();
}
inline std::string story_scene_key(std::string name) {
    if (name.compare(0, 10, "StoryData/") == 0) name.erase(0, 10);
    if (name.compare(0, 3, "JP_") == 0) name.erase(0, 3);
    if (name.size() > 5 && name.compare(name.size() - 5, 5, ".json") == 0) name.resize(name.size() - 5);
    if (name.empty() || name.size() > 96 || !std::all_of(name.begin(), name.end(), [](char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    })) return {};
    return "@story:" + name;
}
inline std::string story_dialog_key(const std::string &parent, const std::string &id) {
    return parent.compare(0, 7, "@story:") == 0 && story_scene_key(parent.substr(7)) == parent &&
            skill_number(id) ? parent + "/dialog:" + id : std::string();
}
inline std::string story_parent_key(const std::string &context) {
    const auto split = context.find("/dialog:");
    if (split == std::string::npos) return {};
    const auto parent = context.substr(0, split);
    return story_dialog_key(parent, context.substr(split + 8)) == context ? parent : std::string();
}
inline std::string guide_root_key(const std::string &id) {
    return skill_number(id) ? "@abnormality-guide:" + id : std::string();
}
inline std::string event_option_key(const std::string &parent, int32_t index) {
    return parent.compare(0, 14, "@action-event:") == 0 && skill_number(parent.substr(14)) &&
            index >= 0 && index < 512 ? parent + "/option:" + std::to_string(index) : std::string();
}
inline std::string guide_story_key(const std::string &parent, const std::string &level) {
    return parent.compare(0, 19, "@abnormality-guide:") == 0 && skill_number(parent.substr(19)) &&
            skill_number(level) ? parent + "/level:" + level : std::string();
}
inline std::string rpg_dialogue_root_key(const std::string &key) {
    if (key.empty() || key.size() > 96 || !std::all_of(key.begin(), key.end(), [](char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
                c == '-' || c == '_' || c == '.';
    })) return {};
    return "@rpg-dialogue:" + key;
}
inline std::string rpg_dialogue_text_key(const std::string &parent, const std::string &index) {
    return parent.compare(0, 14, "@rpg-dialogue:") == 0 && !rpg_dialogue_root_key(parent.substr(14)).empty() &&
            skill_number(index) ? parent + "/text:" + index : std::string();
}
inline bool text_collection_type(const char *name, const char *element, bool dictionary) {
    if (!name || !element) return false;
    std::string type(name);
    type.erase(std::remove(type.begin(), type.end(), ' '), type.end());
    if (!dictionary && type == std::string(element) + "[]") return true;
    static const char *const dictionaries[] = {"Dictionary", "Dictionary`2", "IReadOnlyDictionary", "IReadOnlyDictionary`2"};
    static const char *const lists[] = {"List", "List`1", "IReadOnlyList", "IReadOnlyList`1"};
    for (size_t i = 0; i < 4; ++i) {
        const char *container = dictionary ? dictionaries[i] : lists[i];
        std::string expected = std::string("System.Collections.Generic.") + container + "<" +
                (dictionary ? "System.Int32," : "") + element + ">";
        if (type == expected) return true;
    }
    return false;
}
inline std::string skill_child_key(const std::string &parent, const char *field,
                                   const std::string &level, int32_t index) {
    if (parent.empty() || !field || index < 0) return {};
    std::string suffix;
    if (!strcmp(field, "levelList") || !strcmp(field, "levellist")) {
        if (!skill_number(level)) return {};
        suffix = "/level:" + level;
    } else if (!strcmp(field, "coinList") || !strcmp(field, "coinlist")) {
        suffix = "/coin:" + std::to_string(index);
    } else if (!strcmp(field, "coinDescs") || !strcmp(field, "coindescs")) {
        suffix = "/text:" + std::to_string(index);
    } else return {};
    return parent.size() + suffix.size() <= 256 ? parent + suffix : std::string();
}
class ScopedTextKey {
    std::string &current;
    std::string previous;
public:
    ScopedTextKey(std::string &slot, std::string key)
        : current(slot), previous(std::move(slot)) { current = std::move(key); }
    ~ScopedTextKey() { current = std::move(previous); }
    ScopedTextKey(const ScopedTextKey &) = delete;
    ScopedTextKey &operator=(const ScopedTextKey &) = delete;
};
inline bool explicit_line_break(std::string_view text) {
    if (text.find_first_of("\r\n") != std::string_view::npos) return true;
    for (size_t i = 0; i + 3 < text.size(); ++i) {
        auto tag = limbus_rich_text::tag_at(text, i);
        if (!tag.end || tag.closing) continue;
        if (limbus_rich_text::equal_ascii(tag.name, "noparse"))
            i = limbus_rich_text::literal_end(text, tag.end) - 1;
        else if (limbus_rich_text::equal_ascii(tag.name, "br")) return true;
    }
    return false;
}
// A short sentence in a tall dialogue box is still body text. Counting characters
// alone must not turn it into an ellipsized, single-line label.
inline bool single_line_label(size_t visible, bool explicit_break, bool original_no_wrap,
                              float rect_height, float font_size, int overflow = -1,
                              bool content_fitted_height = false) {
    // ScrollRect, Page and Linked own their text flow. Masking/Truncate also
    // occur on ordinary one-line buttons and must not force CJK vertical stacks.
    if (overflow >= 4 || visible > 48 || explicit_break || content_fitted_height) return false;
    if (rect_height > 0 && font_size > 0)
        return rect_height <= font_size * 1.8f;
    return original_no_wrap;
}
inline bool positioned_ruby(std::string_view text) {
    for (size_t i = 0; (i = text.find('<', i)) != text.npos; ++i) {
        auto tag = limbus_rich_text::tag_at(text, i);
        if (tag.end && limbus_rich_text::equal_ascii(tag.name, "noparse") && !tag.closing) {
            i = limbus_rich_text::literal_end(text, tag.end) - 1; continue;
        }
        if (tag.end && limbus_rich_text::equal_ascii(tag.name, "pos")) return true;
        // Ruby is implemented by the game's subclass, not TMP's tag parser.
        if (i + 5 < text.size() && limbus_rich_text::equal_ascii(text.substr(i + 1, 4), "ruby") &&
            (text[i + 5] == '=' || text[i + 5] == '>' || text[i + 5] == ' ')) return true;
    }
    return false;
}


// These IL2CPP parameters occupy one general-purpose register on the supported
// arm64 profile. Floating-point and arbitrary value types require typed wrappers.
inline bool is_gp_formatter_parameter(const char *name) {
    if (name == nullptr) return false;
    for (const char *safe : {"System.String", "System.Object", "System.String[]", "System.Object[]",
            "System.Boolean", "System.Byte", "System.SByte", "System.Char", "System.Int16",
            "System.UInt16", "System.Int32", "System.UInt32", "System.Int64", "System.UInt64",
            "System.IntPtr", "System.UIntPtr"}) {
        if (std::strcmp(name, safe) == 0) return true;
    }
    return false;
}
// Compare complete labels without allocating normalized copies of the package.
// Only the observed whitespace and comma presentation differences are ignored.
inline unsigned int next_label_byte(std::string_view text, size_t &position) {
    while (position < text.size()) {
        unsigned char value = text[position++];
        if (value == ' ' || value == '\t' || value == '\r' || value == '\n') continue;
        if (value == 0xef && position + 1 < text.size() &&
            static_cast<unsigned char>(text[position]) == 0xbc &&
            static_cast<unsigned char>(text[position + 1]) == 0x8c) {
            position += 2;
            return ',';
        }
        return value;
    }
    return 256;
}
struct LabelHash {
    size_t operator()(std::string_view text) const {
        size_t hash = 14695981039346656037ull;
        size_t position = 0;
        for (unsigned int byte; (byte = next_label_byte(text, position)) != 256;) {
            hash ^= byte;
            hash *= 1099511628211ull;
        }
        return hash;
    }
};
struct LabelEqual {
    bool operator()(std::string_view left, std::string_view right) const {
        size_t a = 0, b = 0;
        for (;;) {
            unsigned int x = next_label_byte(left, a), y = next_label_byte(right, b);
            if (x != y) return false;
            if (x == 256) return true;
        }
    }
};
inline bool is_short_translated_label(std::string_view text) {
    return !text.empty() && text.size() <= 256 &&
           std::any_of(text.begin(), text.end(), [](unsigned char byte) { return byte >= 128; });
}
// The caller supplies a translated display field of the same formatter object.
// This only propagates font provenance; it never changes the formatted text.
inline bool contains_translated_field(const std::string &formatted, const std::string &field) {
    if (field.empty() || field.size() > 16384) return false;
    size_t codepoints = 0;
    bool non_ascii = false;
    for (unsigned char byte : field) {
        non_ascii |= (byte & 0x80) != 0;
        codepoints += (byte & 0xc0) != 0x80;
    }
    if (!non_ascii || codepoints < 2) return false;
    if (formatted.find(field) != std::string::npos) return true;
    if (field.find('\n') == std::string::npos) return false;
    std::string spaced = field;
    std::replace(spaced.begin(), spaced.end(), '\n', ' ');
    if (formatted.find(spaced) != std::string::npos) return true;
    std::string compact = field;
    compact.erase(std::remove(compact.begin(), compact.end(), '\n'), compact.end());
    return !compact.empty() && formatted.find(compact) != std::string::npos;
}
}
