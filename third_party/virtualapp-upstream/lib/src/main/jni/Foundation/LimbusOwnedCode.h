#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <vector>

namespace limbus_owned_code {
struct Patch {
    uintptr_t offset;
    size_t size;
    unsigned char before[20];
    unsigned char after[20];
};

struct Range { uintptr_t offset; size_t size; };

inline uint64_t range_fingerprint(const std::vector<Range> &ranges) {
    uint64_t hash = 14695981039346656037ull;
    for (const Range &range : ranges) {
        for (uint64_t value : {static_cast<uint64_t>(range.offset), static_cast<uint64_t>(range.size)}) {
            for (unsigned shift = 0; shift < 64; shift += 8) {
                hash ^= (value >> shift) & 0xffu;
                hash *= 1099511628211ull;
            }
        }
    }
    return hash;
}

// The protection library's other scanner hashes disjoint, byte-sized ranges,
// without the managed scanner's instruction normalization.
inline bool ranges_crc(const unsigned char *live, size_t module_size,
                       const std::vector<Range> &ranges, const std::vector<Patch> &patches,
                       uint32_t &result, size_t &substituted) {
    if (!live || ranges.empty() || ranges.size() > 256) return false;
    uintptr_t previous_end = 0;
    for (const Range &range : ranges) {
        if (range.offset < previous_end || range.offset > module_size
                || range.size > module_size - range.offset) return false;
        previous_end = range.offset + range.size;
    }
    previous_end = 0;
    for (const Patch &patch : patches) {
        if (!patch.size || patch.size > sizeof(patch.before) || patch.offset < previous_end
                || patch.offset > module_size || patch.size > module_size - patch.offset
                || memcmp(live + patch.offset, patch.after, patch.size) != 0) return false;
        previous_end = patch.offset + patch.size;
    }
    uint32_t table[256];
    for (uint32_t i = 0; i < 256; ++i) {
        uint32_t value = i;
        for (int bit = 0; bit < 8; ++bit)
            value = (value >> 1) ^ (0xedb88320u & (0u - (value & 1u)));
        table[i] = value;
    }
    uint32_t crc = 0xffffffffu;
    size_t index = 0;
    substituted = 0;
    for (const Range &range : ranges) {
        for (uintptr_t position = range.offset; position < range.offset + range.size; ++position) {
            while (index < patches.size() && patches[index].offset + patches[index].size <= position) ++index;
            unsigned char value = live[position];
            if (index < patches.size() && patches[index].offset <= position) {
                value = patches[index].before[position - patches[index].offset];
                ++substituted;
            }
            crc = table[(crc ^ value) & 0xffu] ^ (crc >> 8);
        }
    }
    result = ~crc;
    return true;
}

// Same CRC-32 and NOP/CLREX normalization as the hash-qualified game's scanner.
// Only registered bytes are substituted. All remaining instructions contribute unchanged.
inline bool section_crc(const unsigned char *live, uintptr_t offset, size_t size,
                        const std::vector<Patch> &patches, uint32_t &result, size_t &owned) {
    if (!live || size % 4 || offset > UINTPTR_MAX - size) return false;
    uintptr_t end = offset + size;
    uintptr_t previous_end = offset;
    for (const Patch &patch : patches) {
        if (patch.offset < offset || patch.offset >= end) continue;
        if (!patch.size || patch.size > sizeof(patch.before) || patch.size % 4
                || patch.offset % 4 || patch.offset < previous_end || patch.size > end - patch.offset
                || memcmp(live + patch.offset - offset, patch.after, patch.size) != 0) return false;
        previous_end = patch.offset + patch.size;
        ++owned;
    }
    uint32_t table[256];
    for (uint32_t i = 0; i < 256; ++i) {
        uint32_t value = i;
        for (int bit = 0; bit < 8; ++bit)
            value = (value >> 1) ^ (0xedb88320u & (0u - (value & 1u)));
        table[i] = value;
    }
    uint32_t crc = 0xffffffffu;
    size_t index = 0;
    for (size_t position = 0; position < size; position += 4) {
        uintptr_t current = offset + position;
        while (index < patches.size() && patches[index].offset + patches[index].size <= current) ++index;
        const unsigned char *bytes = live + position;
        if (index < patches.size() && patches[index].offset <= current
                && current < patches[index].offset + patches[index].size)
            bytes = patches[index].before + current - patches[index].offset;
        uint32_t instruction;
        memcpy(&instruction, bytes, sizeof(instruction));
        size_t begin = (instruction == 0xd503201f || instruction == 0xd5033f5f) ? 2 : 0;
        for (size_t i = begin; i < 4; ++i) crc = table[(crc ^ bytes[i]) & 0xffu] ^ (crc >> 8);
    }
    result = ~crc;
    return true;
}
} // namespace limbus_owned_code
