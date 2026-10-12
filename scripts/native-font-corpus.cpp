#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFontProof.h"
#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstdint>

static uint32_t read32(FILE *f) {
    unsigned char b[4]; assert(fread(b, 1, 4, f) == 4);
    return (uint32_t(b[0]) << 24) | (uint32_t(b[1]) << 16) | (uint32_t(b[2]) << 8) | b[3];
}
int main(int argc, char **argv) {
    assert(argc == 2);
    FILE *f = fopen(argv[1], "rb"); assert(f);
    uint32_t count = read32(f); assert(count < 1000000);
    std::vector<std::string> released;
    for (uint32_t i = 0; i < count; ++i) {
        uint32_t length = read32(f); assert(length < 1024 * 1024);
        std::string value(length, '\0'); assert(fread(value.data(), 1, length, f) == length);
        released.push_back(std::move(value));
    }
    assert(fgetc(f) == EOF); fclose(f);
    auto start = std::chrono::steady_clock::now();
    limbus_font_proof::Catalogue catalogue;
    for (const auto &text : released) catalogue.add(text);
    auto loaded = std::chrono::steady_clock::now();
    size_t full = 0, formatted = 0, rich = 0, excluded = 0, fragments = 0;
    for (const auto &text : released) {
        auto label = limbus_font_proof::canonical(text);
        bool non_ascii = false;
        for (unsigned char c : label) non_ascii |= c >= 128;
        if (!non_ascii) { ++excluded; continue; }
        assert(catalogue.contains(text)); ++full;
        if (text.size() < 16000) {
            assert(catalogue.contains("<color=#ffeecc>" + text + "</color>")); ++rich;
        }
        auto p = limbus_font_proof::pattern(label);
        if (p.slots.empty()) continue;
        std::string rendered = p.literals.front();
        for (size_t n = 0; n < p.slots.size(); ++n) {
            rendered += "<b>" + std::to_string(7 + p.slots[n]) + "</b>" + p.literals[n + 1];
        }
        assert(catalogue.contains(rendered)); ++formatted;
        std::vector<std::string> parts;
        bool applicable = p.literals.front().size() > 0;
        for (size_t n = 0; n < p.literals.size(); ++n) {
            parts.push_back(p.literals[n]);
            applicable &= !p.literals[n].empty() && p.literals[n].size() <= 512;
            if (n < p.slots.size()) parts.push_back(std::to_string(7 + p.slots[n]));
        }
        size_t total = 0;
        for (const auto &part : parts) total += part.size();
        if (applicable && total <= 2048) {
            for (size_t n = 0; n < parts.size(); n += 2) {
                if (!catalogue.fragment_candidate(parts[n])) continue;
                assert(catalogue.contains_fragments(parts, n)); ++fragments;
            }
        }
    }
    auto done = std::chrono::steady_clock::now();
    printf("Font corpus: input=%u labels=%zu patterns=%zu full=%zu rich=%zu formatted=%zu fragments=%zu excluded=%zu build_ms=%lld verify_ms=%lld\n",
           count, catalogue.label_count(), catalogue.pattern_count(), full, rich, formatted, fragments, excluded,
           (long long)std::chrono::duration_cast<std::chrono::milliseconds>(loaded-start).count(),
           (long long)std::chrono::duration_cast<std::chrono::milliseconds>(done-loaded).count());
}
