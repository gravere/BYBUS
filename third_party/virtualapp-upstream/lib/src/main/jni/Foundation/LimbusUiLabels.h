#pragma once
#include <string>

namespace limbus_ui_labels {
// The translation archive contains game prose, but these prefab status constants
// are not Localize records. Only complete, enumerated UI labels are eligible.
inline std::string translate_status(const std::string &source) {
    const std::string progress_base = "DOWNLOADING UPDATE";
    if (source == progress_base) return "下载更新";
    if (source.size() <= 48 && source.compare(0, progress_base.size() + 1, progress_base + " ") == 0) {
        size_t position = progress_base.size() + 1;
        auto digits = [&](size_t maximum) {
            size_t start = position;
            while (position < source.size() && source[position] >= '0' && source[position] <= '9') ++position;
            return position > start && position - start <= maximum;
        };
        auto take = [&](char expected) {
            if (position >= source.size() || source[position] != expected) return false;
            ++position; return true;
        };
        bool valid = digits(3);
        if (valid && position < source.size() && source[position] == '.') {
            ++position; valid = digits(2);
        }
        if (valid && take('%') && take(' ') && digits(6) && take('/') && digits(6) && position == source.size())
            return "下载更新" + source.substr(progress_base.size());
    }
    const char *bases[] = {"Loading", "LOADING", "loading", "Now Loading", "NOW LOADING", "now loading",
                           "Connecting", "CONNECTING", "connecting"};
    const char *suffixes[] = {"", ".", "..", "...", "…"};
    for (size_t i = 0; i < sizeof(bases) / sizeof(bases[0]); ++i) {
        for (const char *suffix : suffixes) {
            if (source == std::string(bases[i]) + suffix) {
                return std::string(i < 6 ? "加载中" : "连接中") + (suffix[0] == 0 ? "" : "…");
            }
        }
    }
    return {};
}
}
