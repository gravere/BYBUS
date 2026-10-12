#pragma once
#include "LimbusFontProof.h"

namespace limbus_font_proof {
// A proof belongs to one registered event text component, never the global
// catalogue. Only a complete verified input may authorize its progressive view.
class FlowProof {
    std::string complete_;
    std::vector<size_t> starts_;
public:
    void clear() { complete_.clear(); starts_.clear(); }
    bool assign(std::string_view text, bool verified) {
        clear();
        if (!verified || text.empty() || text.size() > 16384) return false;
        std::string lower(text);
        for (auto &c : lower) if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
        for (auto tag : {"<font", "<sprite", "<noparse"})
            if (lower.find(tag) != lower.npos) return false;
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
        auto visible = canonical(rendered);
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
