#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusOwnedCode.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusIntegrityLayout.h"
#include <assert.h>
#include <stdio.h>

using limbus_owned_code::Patch;
using limbus_owned_code::section_crc;

int main() {
    LimbusIntegrityLayout layout;
    assert(limbus_parse_integrity_layout("67411520:6183260:73594780:94184624", layout));
    assert(layout.original_expectations && layout.text_offset == 67411520 && layout.managed_size == 94184624);
    const char *invalid_layouts[] = {nullptr, "", "4:4:4:4", "4:4:8:0", "5:4:12:4",
        "4:4:8:4:4", "-4:4:8:4", "4:4:8:4suffix", "4:4:536870912:4",
        "18446744073709551616:4:8:4", "4::8:4", "4:4:8:", "4:4:8: 4"};
    for (const char *input : invalid_layouts) assert(!limbus_parse_integrity_layout(input, layout));
    unsigned char original[] = "0123456789ABCDEF0123456789ABCDEF";
    unsigned char live[32];
    memcpy(live, original, sizeof(live));
    std::vector<Patch> patches;
    uint32_t crc = 0;
    size_t owned = 0;
    assert(section_crc(live, 0x1000, sizeof(live), patches, crc, owned));
    const uint32_t baseline = crc;
    assert(baseline == 0xd76b6946u && owned == 0); // Python zlib independently calculated fixture.

    Patch patch = {};
    patch.offset = 0x1008;
    patch.size = 4;
    memcpy(patch.before, live + 8, 4);
    memset(patch.after, 0xaa, 4);
    memcpy(live + 8, patch.after, 4);
    patches.push_back(patch);
    assert(section_crc(live, 0x1000, sizeof(live), patches, crc, owned));
    assert(crc == baseline && owned == 1);

    live[20] ^= 1;
    owned = 0;
    assert(section_crc(live, 0x1000, sizeof(live), patches, crc, owned) && crc != baseline);
    live[20] ^= 1;
    live[8] ^= 1;
    assert(!section_crc(live, 0x1000, sizeof(live), patches, crc, owned));
    live[8] ^= 1;
    patches[0].before[0] ^= 1;
    assert(section_crc(live, 0x1000, sizeof(live), patches, crc, owned) && crc != baseline);
    patches[0].before[0] ^= 1;

    patches.push_back(patches[0]);
    assert(!section_crc(live, 0x1000, sizeof(live), patches, crc, owned));
    patches.pop_back();
    patches[0].size = 24;
    assert(!section_crc(live, 0x1000, sizeof(live), patches, crc, owned));
    patches[0].size = 4;
    patches[0].offset = 0x101e;
    assert(!section_crc(live, 0x1000, sizeof(live), patches, crc, owned));
    patches.clear();
    assert(!section_crc(live, 0x1000, 31, patches, crc, owned));
    assert(!section_crc(live, UINTPTR_MAX - 1, 32, patches, crc, owned));

    const unsigned char normalized[] = {0x1f,0x20,0x03,0xd5,0x5f,0x3f,0x03,0xd5,0x78,0x56,0x34,0x12};
    assert(section_crc(normalized, 0, sizeof(normalized), patches, crc, owned));
    assert(crc == 0x5bfe2e21u); // CRC of 03 d5 03 d5 78 56 34 12.
    using limbus_owned_code::Range;
    using limbus_owned_code::ranges_crc;
    std::vector<Range> ranges = {{0, 11}, {14, 18}};
    memcpy(live, original, sizeof(live));
    size_t substituted = 0;
    assert(ranges_crc(live, sizeof(live), ranges, patches, crc, substituted));
    const uint32_t raw_baseline = crc;
    assert(raw_baseline == 0xfebaaa20u && substituted == 0); // Independent Python zlib fixture.
    const uint64_t shape = limbus_owned_code::range_fingerprint(ranges);
    patch.offset = 8;
    patch.size = 8;
    memcpy(patch.before, live + 8, 8);
    memset(patch.after, 0xcc, 8);
    memcpy(live + 8, patch.after, 8);
    patches.push_back(patch);
    // A patch crossing a range boundary and a gap must restore only covered bytes.
    assert(ranges_crc(live, sizeof(live), ranges, patches, crc, substituted));
    assert(crc == raw_baseline && substituted == 5);
    live[20] ^= 1;
    assert(ranges_crc(live, sizeof(live), ranges, patches, crc, substituted) && crc != raw_baseline);
    live[20] ^= 1;
    live[12] ^= 1; // Even a patch byte in the unscanned gap must still equal its recorded after-byte.
    assert(!ranges_crc(live, sizeof(live), ranges, patches, crc, substituted));
    live[12] ^= 1;
    ranges[1].size = 19;
    assert(!ranges_crc(live, sizeof(live), ranges, patches, crc, substituted));
    ranges[1] = {10, 18};
    assert(!ranges_crc(live, sizeof(live), ranges, patches, crc, substituted));
    ranges[1] = {14, 17};
    assert(limbus_owned_code::range_fingerprint(ranges) != shape);
    ranges[1] = {UINTPTR_MAX, 2};
    assert(!ranges_crc(live, sizeof(live), ranges, patches, crc, substituted));
    puts("native integrity checks passed: bounded dynamic layout, normalized sections and disjoint byte ranges");
}
