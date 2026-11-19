#pragma once

#include <stdint.h>
#include <stddef.h>
#include <string.h>

struct LimbusIntegrityLayout {
    uintptr_t text_offset, managed_offset;
    size_t text_size, managed_size;
    uint32_t text_crc, managed_crc, protection_crc;
    bool original_expectations;
    constexpr LimbusIntegrityLayout(uintptr_t text = 0, uintptr_t managed = 0,
            size_t text_length = 0, size_t managed_length = 0, uint32_t text_expected = 0,
            uint32_t managed_expected = 0, uint32_t protection_expected = 0, bool automatic = false)
        : text_offset(text), managed_offset(managed), text_size(text_length), managed_size(managed_length),
          text_crc(text_expected), managed_crc(managed_expected), protection_crc(protection_expected),
          original_expectations(automatic) {}
};

// Input is produced by the host's signed-APK/ELF qualifier, never downloaded code.
// Keep independent bounds here because environment values are not memory-safety guarantees.
inline bool limbus_parse_integrity_layout(const char *input, LimbusIntegrityLayout &result) {
    if (!input || strnlen(input, 96) >= 96) return false;
    uint64_t values[4] = {};
    size_t at = 0;
    constexpr uint64_t limit = 512u * 1024u * 1024u;
    for (unsigned i = 0; i < 4; ++i) {
        size_t start = at;
        while (input[at] >= '0' && input[at] <= '9') {
            values[i] = values[i] * 10 + static_cast<unsigned>(input[at++] - '0');
            if (values[i] > limit) return false;
        }
        if (at == start || values[i] < 4 || (values[i] & 3u)) return false;
        if (i < 3) { if (input[at++] != ':') return false; }
        else if (input[at] != '\0') return false;
    }
    if (values[0] + values[1] > values[2] || values[2] + values[3] > limit) return false;
    result = {static_cast<uintptr_t>(values[0]), static_cast<uintptr_t>(values[2]),
              static_cast<size_t>(values[1]), static_cast<size_t>(values[3]), 0, 0, 0, true};
    return true;
}
