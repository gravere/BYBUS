#pragma once
#include <array>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include "LimbusRichText.h"

// Font provenance only: this never translates, rewrites tags, or changes numbers.
// Recognize a complete released translation, including bounded numeric formatting.
namespace limbus_font_proof {
inline bool decorative(std::string_view tag) {
    if (!tag.empty() && tag.front() == '/') tag.remove_prefix(1);
    auto end = tag.find_first_of("= \t");
    auto name = tag.substr(0, end);
    for (auto safe : {"color", "alpha", "size", "b", "i", "u", "s", "mark",
                      "align", "line-height", "cspace", "mspace", "indent", "voffset", "font-weight", "link"}) {
        if (limbus_rich_text::equal_ascii(name, safe)) return true;
    }
    return false;
}
inline bool has_literal_region(std::string_view input) {
    for (size_t i = 0; (i = input.find('<', i)) != input.npos; ++i) {
        const auto tag = limbus_rich_text::tag_at(input, i);
        if (tag.end && limbus_rich_text::equal_ascii(tag.name, "noparse")) return true;
    }
    return false;
}
inline std::string canonical(std::string_view input) {
    if (input.empty() || input.size() > 16384) return {};
    std::string out;
    out.reserve(input.size());
    for (size_t i = 0; i < input.size();) {
        if (input[i] == '<') {
            const auto tag = limbus_rich_text::tag_at(input, i);
            if (tag.end && !tag.closing && limbus_rich_text::equal_ascii(tag.name, "noparse")) {
                const auto end = limbus_rich_text::literal_end(input, tag.end);
                // Literal markup, whitespace and punctuation inside noparse
                // are visible content, not formatting to normalize away.
                out.append(input.substr(i, end - i)); i = end; continue;
            }
            // TMP's line-break tag and an actual newline have the same visible
            // text. Normalize only the exact no-argument tag, not arbitrary tags.
            if (tag.end == i + 4 && !tag.closing && limbus_rich_text::equal_ascii(tag.name, "br")) {
                i = tag.end;
                continue;
            }
            if (tag.end && decorative(tag.name)) {
                i = tag.end;
                continue;
            }
        }
        unsigned char c = input[i++];
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
        if (c == 0xef && i + 1 < input.size() &&
            static_cast<unsigned char>(input[i]) == 0xbc &&
            static_cast<unsigned char>(input[i + 1]) == 0x8c) {
            out += ','; i += 2;
        } else out += static_cast<char>(c);
    }
    return out;
}
// Only concatenate independently proven COMPLETE outputs at explicit paragraph
// boundaries. Rich-text macros and sprites are retained for exact lookup.
template<class Known>
bool complete_paragraphs(std::string_view text, Known known) {
    if (text.size() > 8192 || has_literal_region(text)) return false;
    std::string lines(text);
    for (size_t i = 0; i < lines.size();) {
        auto tag = limbus_rich_text::tag_at(lines, i);
        if (tag.end == i + 4 && !tag.closing && limbus_rich_text::equal_ascii(tag.name, "br")) {
            lines.replace(i, 4, "\n"); ++i;
        } else i = tag.end ? tag.end : i + 1;
    }
    std::vector<std::string_view> parts;
    std::string_view view(lines);
    for (size_t start = 0; start <= view.size();) {
        const auto end = view.find('\n', start);
        auto part = view.substr(start, end == view.npos ? view.npos : end - start);
        if (!part.empty() && part.back() == '\r') part.remove_suffix(1);
        parts.push_back(part);
        if (parts.size() > 32) return false;
        if (end == view.npos) break;
        start = end + 1;
    }
    if (parts.size() < 2) return false;
    std::vector<bool> reachable(parts.size() + 1, false);
    reachable[0] = true;
    unsigned budget = 256;
    bool nonempty_proof = false;
    for (size_t end = 1; end <= parts.size(); ++end) {
        if (reachable[end - 1] && parts[end - 1].find_first_not_of(" \t\r") == parts[end - 1].npos) {
            reachable[end] = true; continue;
        }
        std::string candidate;
        for (size_t start = end; start-- > 0;) {
            candidate = std::string(parts[start]) + (start + 1 < end ? "\n" + candidate : "");
            if (!reachable[start]) continue;
            if (!budget--) return false;
            if (known(candidate)) { reachable[end] = true; nonempty_proof = true; break; }
        }
    }
    return nonempty_proof && reachable.back();
}
inline bool number(std::string_view value) {
    if (value.empty() || value.size() > 64) return false;
    bool digit = false;
    for (unsigned char c : value) {
        if (c >= '0' && c <= '9') digit = true;
        else if (std::string_view("+-.,:/%()").find(static_cast<char>(c)) == std::string_view::npos) return false;
    }
    return digit;
}
inline bool numeric_display(std::string_view value) {
    return value.size() <= 512 && number(canonical(value));
}
inline bool storage_quantity(std::string_view value) {
    if (value.empty() || value.size() > 64) return false;
    if (value.size() > 2 && value.substr(value.size() - 2) == "/s") value.remove_suffix(2);
    bool unit_found = false;
    for (std::string_view unit : {"KiB", "MiB", "GiB", "TiB", "KB", "MB", "GB", "TB", "B"}) {
        if (value.size() > unit.size() && value.substr(value.size() - unit.size()) == unit) {
            value.remove_suffix(unit.size()); unit_found = true; break;
        }
    }
    if (!unit_found || value.size() > 32) return false;
    const auto dot = value.find('.');
    auto integer = value.substr(0, dot);
    auto digits = [](std::string_view part) {
        if (part.empty()) return false;
        for (char c : part) if (c < '0' || c > '9') return false;
        return true;
    };
    if (dot != value.npos && !digits(value.substr(dot + 1))) return false;
    const auto comma = integer.find(',');
    if (comma == integer.npos) return digits(integer);
    if (comma == 0 || comma > 3 || !digits(integer.substr(0, comma))) return false;
    for (size_t pos = comma; pos < integer.size(); pos += 4) {
        if (integer[pos] != ',' || integer.size() - pos < 4 || !digits(integer.substr(pos + 1, 3))) return false;
    }
    return (integer.size() - comma) % 4 == 0;
}
struct Pattern {
    std::vector<std::string> literals;
    std::vector<unsigned> slots;
};
inline Pattern pattern(std::string_view text) {
    Pattern result;
    std::string literal;
    size_t non_ascii = 0;
    for (size_t i = 0; i < text.size();) {
        char c = text[i++];
        if ((c == '{' || c == '}') && i < text.size() && text[i] == c) { literal += c; ++i; continue; }
        if (c == '}') return {};
        if (c != '{') { literal += c; non_ascii += static_cast<unsigned char>(c) >= 128; continue; }
        if (result.slots.size() >= 8 || i >= text.size() || text[i] < '0' || text[i] > '9') return {};
        unsigned slot = static_cast<unsigned>(text[i++] - '0');
        if (i >= text.size()) return {};
        if (text[i] != '}') {
            if (text[i] != ',' && text[i] != ':') return {};
            size_t format_start = i;
            while (i < text.size() && text[i] != '}') {
                unsigned char f = text[i++];
                if (i - format_start > 32 || !((f >= '0' && f <= '9') ||
                    (f >= 'A' && f <= 'Z') || (f >= 'a' && f <= 'z') ||
                    std::string_view(",:-+.#%/").find(static_cast<char>(f)) != std::string_view::npos)) return {};
            }
        }
        if (i >= text.size() || text[i++] != '}') return {};
        // Adjacent substitutions cannot be identified safely from displayed text.
        if (!result.slots.empty() && literal.empty()) return {};
        result.literals.push_back(literal); literal.clear(); result.slots.push_back(slot);
    }
    if (result.slots.empty() || non_ascii < 3) return {};
    result.literals.push_back(literal);
    return result;
}
class Catalogue {
    std::unordered_set<std::string> verbatim_;
    std::unordered_set<std::string> labels_;
    std::vector<Pattern> patterns_;
    std::unordered_set<std::string> fragments_;
    std::unordered_map<std::string, bool> recent_;
    std::unordered_map<std::string, bool> paragraphs_;

    bool argument(std::string_view value) const {
        // IntroductionPreset previews replace {0} with this exact mask. The
        // entire released template still has to match; arbitrary names or a
        // bare mask are not provenance, and no displayed text is rewritten.
        return value == "■■■■■" || number(value) || storage_quantity(value) ||
                (value.size() <= 256 && labels_.count(std::string(value)) != 0);
    }
    bool match(const Pattern &p, std::string_view text, size_t slot, size_t pos,
               std::array<std::string_view, 10> &values, unsigned &budget) const {
        if (budget == 0) return false;
        --budget;
        if (slot == p.slots.size()) return pos == text.size();
        const auto &next = p.literals[slot + 1];
        size_t end = next.empty() ? text.size() : text.find(next, pos);
        while (end != text.npos && end - pos <= 256) {
            auto value = text.substr(pos, end - pos);
            auto &previous = values[p.slots[slot]];
            auto saved = previous;
            if (!value.empty() && (previous.empty() || previous == value) && argument(value)) {
                previous = value;
                if (match(p, text, slot + 1, end + next.size(), values, budget)) return true;
                previous = saved;
            }
            if (next.empty() || budget == 0) break;
            end = text.find(next, end + 1);
            --budget;
        }
        return false;
    }
public:
    void clear() { verbatim_.clear(); labels_.clear(); patterns_.clear(); fragments_.clear(); recent_.clear(); paragraphs_.clear(); }
    void add(std::string_view released) {
        if (released.size() <= 16384 && has_literal_region(released)) {
            // Released names use literal rich-text brackets. They are known
            // only as complete fields: never strip noparse or infer fragments.
            for (unsigned char c : released) if (c >= 128) {
                verbatim_.insert(canonical(released)); break;
            }
            return;
        }
        auto label = canonical(released);
        bool non_ascii = false;
        for (unsigned char c : label) non_ascii |= c >= 128;
        if (!non_ascii || !labels_.insert(label).second) return;
        auto p = pattern(label);
        if (!p.slots.empty()) {
            for (const auto &literal : p.literals) {
                bool cjk = false;
                for (unsigned char c : literal) cjk |= c >= 128;
                if (cjk && literal.size() <= 256) fragments_.insert(literal);
            }
            patterns_.push_back(std::move(p));
        }
    }
    // A candidate is not proof by itself. It only bounds hierarchy lookups.
    bool fragment_candidate(std::string_view text) const {
        return text.size() <= 512 && fragments_.count(canonical(text)) != 0;
    }
    bool contains_fragments(const std::vector<std::string> &parts, size_t target) const {
        if (parts.size() < 3 || parts.size() > 17 || target >= parts.size() || target % 2 != 0) return false;
        std::vector<std::string> normalized;
        size_t total = 0;
        for (const auto &part : parts) {
            if (part.size() > 512 || (total += part.size()) > 2048) return false;
            normalized.push_back(canonical(part));
            if (normalized.back().empty()) return false;
        }
        if (!fragments_.count(normalized[target])) return false;
        for (const auto &p : patterns_) {
            if (p.slots.size() * 2 + 1 != normalized.size()) continue;
            std::array<std::string_view, 10> values{};
            bool valid = true;
            for (size_t n = 0; n < p.literals.size() && valid; ++n) {
                valid = p.literals[n] == normalized[n * 2];
                if (n == p.slots.size() || !valid) continue;
                const auto &value = normalized[n * 2 + 1];
                auto &previous = values[p.slots[n]];
                valid = argument(value) && (previous.empty() || previous == value);
                previous = value;
            }
            if (valid) return true;
        }
        return false;
    }
    bool contains_atomic(std::string_view rendered) {
        auto label = canonical(rendered);
        if (label.empty()) return false;
        if (has_literal_region(rendered)) return verbatim_.count(label) != 0;
        if (labels_.count(label)) return true;
        auto cached = recent_.find(label);
        if (cached != recent_.end()) return cached->second;
        bool found = false;
        // The template catalogue is immutable after index loading; a bounded
        // result cache avoids re-matching repeated timers and layout passes.
        for (const auto &p : patterns_) {
            const auto &prefix = p.literals.front();
            if (label.compare(0, prefix.size(), prefix) != 0 ||
                label.size() < prefix.size() + p.literals.back().size()) continue;
            if (!p.literals.back().empty() &&
                label.compare(label.size() - p.literals.back().size(), p.literals.back().size(), p.literals.back()) != 0) continue;
            std::array<std::string_view, 10> values{};
            unsigned budget = 64;
            if (match(p, label, 0, prefix.size(), values, budget)) { found = true; break; }
        }
        if (recent_.size() >= 512) recent_.clear();
        recent_.emplace(std::move(label), found);
        return found;
    }
    bool contains_display_line(std::string_view rendered) {
        if (contains_atomic(rendered)) return true;
        if (rendered.size() > 2048 || has_literal_region(rendered) ||
            rendered.find_first_of("\r\n") != rendered.npos) return false;
        // The game appends a numeric stage to a complete translated dungeon
        // name. Keep this proof separate: it cannot authorize nested suffixes.
        const auto split = rendered.rfind(" - ");
        if (split == rendered.npos) return false;
        const auto number = canonical(rendered.substr(split + 3));
        if (number.empty() || number.size() > 6) return false;
        for (char c : number) if (c < '0' || c > '9') return false;
        return contains_atomic(rendered.substr(0, split));
    }
    bool contains(std::string_view rendered) {
        if (contains_atomic(rendered)) return true;
        if (rendered.size() > 8192 || has_literal_region(rendered)) return false;
        // Start-buff cards concatenate several released descriptions. Validate
        // every complete bullet, including its parameters, never a prose substring.
        std::string list_text(rendered);
        for (size_t i = 0; i + 3 < list_text.size(); ++i)
            if (list_text[i] == '<' && (list_text[i + 1] == 'b' || list_text[i + 1] == 'B') &&
                (list_text[i + 2] == 'r' || list_text[i + 2] == 'R') && list_text[i + 3] == '>')
                list_text.replace(i, 4, "\n");
        rendered = list_text;
        if (rendered.find("•") == rendered.npos && rendered.find("◆") == rendered.npos) {
            if (rendered.find('\n') == rendered.npos) return contains_display_line(rendered);
            // Keep line boundaries in this cache: joining known complete
            // paragraphs is not permission to match arbitrary substrings.
            auto known = paragraphs_.find(list_text);
            if (known != paragraphs_.end()) return known->second;
            size_t count = 0;
            bool valid = true;
            for (size_t start = 0; start <= rendered.size();) {
                const auto end = rendered.find('\n', start);
                auto line = rendered.substr(start, end == rendered.npos ? rendered.npos : end - start);
                if (!canonical(line).empty() &&
                    (++count > 16 || line.size() > 2048 || !contains_display_line(line))) { valid = false; break; }
                if (end == rendered.npos) break;
                start = end + 1;
            }
            valid = valid && count >= 2;
            if (paragraphs_.size() >= 256) paragraphs_.clear();
            paragraphs_.emplace(std::move(list_text), valid);
            return valid;
        }
        std::vector<std::string> items;
        for (size_t start = 0; start <= rendered.size();) {
            size_t end = rendered.find('\n', start);
            std::string line = canonical(rendered.substr(start, end == rendered.npos ? rendered.npos : end - start));
            if (!line.empty()) {
                // Both markers are added by the game around complete released
                // labels/templates. They never authorize an unknown remainder.
                const std::string_view bullet = line.compare(0, std::string_view("◆").size(), "◆") == 0
                        ? std::string_view("◆") : std::string_view("•");
                if (line.compare(0, bullet.size(), bullet) == 0) {
                    if (items.size() >= 16) return false;
                    items.push_back(line.substr(bullet.size()));
                } else {
                    if (items.empty()) return false;
                    items.back() += line;
                }
            }
            if (end == rendered.npos) break;
            start = end + 1;
        }
        if (items.empty()) return false;
        for (const auto &item : items) {
            if (item.empty() || item.size() > 2048 || !contains_atomic(item)) return false;
        }
        // Keep whole-list proof out of the atomic-template cache: otherwise a
        // previously seen list can authorize another nested bullet wrapper.
        // Individual complete items are already cached by contains_atomic.
        return true;
    }
    size_t label_count() const { return labels_.size() + verbatim_.size(); }
    size_t pattern_count() const { return patterns_.size(); }
};
}
