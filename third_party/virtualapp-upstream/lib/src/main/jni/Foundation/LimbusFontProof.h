#pragma once
#include <array>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

// Font provenance only: this never translates, rewrites tags, or changes numbers.
// Recognize a complete released translation, including bounded numeric formatting.
namespace limbus_font_proof {
inline bool decorative(std::string_view tag) {
    if (!tag.empty() && tag.front() == '/') tag.remove_prefix(1);
    auto end = tag.find_first_of("= ");
    auto name = tag.substr(0, end);
    for (auto safe : {"color", "alpha", "size", "b", "i", "u", "s", "mark",
                      "align", "line-height", "cspace", "mspace", "indent"}) {
        if (name == safe) return true;
    }
    return false;
}
inline std::string canonical(std::string_view input) {
    if (input.empty() || input.size() > 16384 || input.find("<noparse") != input.npos) return {};
    std::string out;
    out.reserve(input.size());
    for (size_t i = 0; i < input.size();) {
        if (input[i] == '<') {
            size_t end = input.find('>', i + 1);
            if (end != input.npos && end - i <= 128 && decorative(input.substr(i + 1, end - i - 1))) {
                i = end + 1;
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
    void clear() { labels_.clear(); patterns_.clear(); fragments_.clear(); recent_.clear(); paragraphs_.clear(); }
    void add(std::string_view released) {
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
    bool contains(std::string_view rendered) {
        if (contains_atomic(rendered)) return true;
        if (rendered.size() > 8192 || rendered.find("<noparse") != rendered.npos) return false;
        // Start-buff cards concatenate several released descriptions. Validate
        // every complete bullet, including its parameters, never a prose substring.
        std::string list_text(rendered);
        for (size_t i = 0; i + 3 < list_text.size(); ++i)
            if (list_text[i] == '<' && (list_text[i + 1] == 'b' || list_text[i + 1] == 'B') &&
                (list_text[i + 2] == 'r' || list_text[i + 2] == 'R') && list_text[i + 3] == '>')
                list_text.replace(i, 4, "\n");
        rendered = list_text;
        if (rendered.find("•") == rendered.npos) {
            if (rendered.find('\n') == rendered.npos) return false;
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
                    (++count > 16 || line.size() > 2048 || !contains_atomic(line))) { valid = false; break; }
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
                const std::string bullet = "•";
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
        if (recent_.size() >= 512) recent_.clear();
        recent_[canonical(rendered)] = true;
        return true;
    }
    size_t label_count() const { return labels_.size(); }
    size_t pattern_count() const { return patterns_.size(); }
};
}
