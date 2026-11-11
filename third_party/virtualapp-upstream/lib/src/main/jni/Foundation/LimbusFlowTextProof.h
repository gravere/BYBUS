#pragma once
#include "LimbusFontProof.h"

namespace limbus_font_proof {
// A proof belongs to one registered event text component, never the global
// catalogue. Only a complete verified input may authorize its progressive view.
class FlowProof {
    std::string complete_;
    std::vector<size_t> starts_;
    static std::string normalize(std::string_view text) {
        std::string normalized;
        for (size_t i = 0; i < text.size();) {
            auto tag = limbus_rich_text::tag_at(text, i);
            if (tag.end) {
                if (limbus_rich_text::equal_ascii(tag.name, "font") ||
                    limbus_rich_text::equal_ascii(tag.name, "noparse")) return {};
                // A typewriter may close an in-progress style early. Its exact
                // opening macro and sprite tokens remain part of the proof.
                if (tag.closing && limbus_rich_text::equal_ascii(tag.name, "style")) {
                    i = tag.end; continue;
                }
                if (!tag.closing && tag.end == i + 4 && limbus_rich_text::equal_ascii(tag.name, "br")) {
                    normalized += '\n'; i = tag.end; continue;
                }
                normalized.append(text.substr(i, tag.end - i)); i = tag.end; continue;
            }
            // Incomplete control tags are not visible-prefix proof. Literal
            // angle-bracket dialogue remains supported by the normal matcher.
            if (text[i] == '<' && i + 1 < text.size()) {
                char next = limbus_rich_text::lower_ascii(text[i + 1]);
                if ((next >= 'a' && next <= 'z') || next == '/' || next == '#') return {};
            }
            normalized += text[i] == '\r' ? '\n' : text[i]; ++i;
        }
        return normalized;
    }
public:
    void clear() { complete_.clear(); starts_.clear(); }
    bool assign(std::string_view text, bool verified) {
        clear();
        if (!verified || text.empty() || text.size() > 16384) return false;
        const auto normalized = normalize(text);
        if (normalized.empty()) return false;
        text = normalized;
        for (size_t start = 0; start <= text.size();) {
            size_t end = text.find('\n', start);
            auto line = canonical(text.substr(start, end == text.npos ? text.npos : end - start));
            if (!line.empty()) {
                if (starts_.size() >= 128) { clear(); return false; }
                starts_.push_back(complete_.size());
                complete_ += line;
            }
            if (end == text.npos) break;
            start = end + 1;
        }
        return !complete_.empty();
    }
    bool matches(std::string_view rendered) const {
        if (complete_.empty() || rendered.size() > 16384) return false;
        auto visible = canonical(normalize(rendered));
        bool non_ascii = false;
        for (unsigned char c : visible) non_ascii |= c >= 128;
        if (!non_ascii || visible.empty()) return false;
        for (size_t start : starts_)
            if (visible.size() <= complete_.size() - start &&
                complete_.compare(start, visible.size(), visible) == 0) return true;
        return false;
    }
};
}
