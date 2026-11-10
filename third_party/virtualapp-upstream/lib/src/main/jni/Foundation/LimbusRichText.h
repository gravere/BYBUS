#pragma once
#include <string>
#include <string_view>

namespace limbus_rich_text {
inline char lower_ascii(char c) { return c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c; }
inline bool equal_ascii(std::string_view value, std::string_view expected) {
    if (value.size() != expected.size()) return false;
    for (size_t i = 0; i < value.size(); ++i)
        if (lower_ascii(value[i]) != expected[i]) return false;
    return true;
}
struct Tag {
    size_t end = 0;
    std::string_view name;
    bool closing = false;
    Tag() = default;
    Tag(size_t limit, std::string_view label, bool is_closing)
        : end(limit), name(label), closing(is_closing) {}
};
inline Tag tag_at(std::string_view text, size_t offset) {
    if (offset >= text.size() || text[offset] != '<') return {};
    const size_t end = text.find('>', offset + 1);
    if (end == text.npos || end - offset > 128) return {};
    auto body = text.substr(offset + 1, end - offset - 1);
    bool closing = !body.empty() && body.front() == '/';
    if (closing) body.remove_prefix(1);
    auto name = body.substr(0, body.find_first_of("= \t"));
    if (name.empty()) return {};
    // TMP's color shorthand, not arbitrary angle-bracket prose.
    if (!closing && body.front() == '#' && (body.size() == 7 || body.size() == 9)) {
        bool hex = true;
        for (char c : body.substr(1))
            hex &= (c >= '0' && c <= '9') || (lower_ascii(c) >= 'a' && lower_ascii(c) <= 'f');
        if (hex) return {end + 1, "color", false};
    }
    for (auto supported : {"align", "allcaps", "alpha", "b", "br", "color", "cspace",
             "font", "font-weight", "gradient", "i", "indent", "line-height", "line-indent",
             "link", "lowercase", "margin", "margin-left", "margin-right", "mark", "mspace",
             "nobr", "noparse", "page", "pos", "rotate", "s", "size", "smallcaps", "space",
             "sprite", "style", "sub", "sup", "u", "uppercase", "voffset", "width"}) {
        if (equal_ascii(name, supported)) return {end + 1, name, closing};
    }
    return {};
}
inline size_t literal_end(std::string_view text, size_t offset) {
    for (size_t next = offset; (next = text.find('<', next)) != text.npos; ++next) {
        auto tag = tag_at(text, next);
        if (tag.closing && equal_ascii(tag.name, "noparse")) return tag.end;
    }
    return text.size();
}
inline size_t protected_end(std::string_view text, size_t offset) {
    auto tag = tag_at(text, offset);
    if (tag.end && !tag.closing && equal_ascii(tag.name, "noparse"))
        return literal_end(text, tag.end);
    return tag.end;
}
inline size_t visible_codepoints(std::string_view text) {
    size_t count = 0;
    bool literal = false;
    for (size_t offset = 0; offset < text.size();) {
        auto tag = tag_at(text, offset);
        if (tag.end && (!literal || (tag.closing && equal_ascii(tag.name, "noparse")))) {
            if (equal_ascii(tag.name, "noparse")) literal = !tag.closing;
            if (!tag.closing && equal_ascii(tag.name, "sprite")) ++count;
            offset = tag.end;
            continue;
        }
        unsigned char c = text[offset++];
        if ((c & 0xc0) != 0x80) ++count;
    }
    return count;
}
}
