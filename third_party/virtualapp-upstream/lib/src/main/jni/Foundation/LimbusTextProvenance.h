#pragma once
#include <algorithm>
#include <string>
#include <string_view>
#include <cstring>
#include <cmath>
#include <utility>
#include "LimbusRichText.h"

namespace limbus_text_provenance {
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
                              float rect_height, float font_size, int overflow = -1) {
    // Preserve Masking, Truncate, ScrollRect, Page and Linked modes (TMP 3).
    if (overflow >= 2 || visible > 48 || explicit_break) return false;
    if (rect_height > 0 && font_size > 0)
        return rect_height <= font_size * 1.8f;
    return original_no_wrap;
}
inline float nominal_font_size(float current, bool auto_sizing, float maximum) {
    return auto_sizing && std::isfinite(maximum) && maximum > 0 ? maximum : current;
}
inline float readable_minimum(float maximum, float original_minimum) {
    float floor = std::max(9.0f, maximum * 0.8f);
    if (std::isfinite(original_minimum) && original_minimum > floor) floor = original_minimum;
    return std::min(maximum, floor);
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
