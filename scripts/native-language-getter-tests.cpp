#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusLanguageGetterPatch.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusOwnedCode.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/RuntimePage.h"
#include <cassert>
#include <cstdio>
#include <initializer_list>

struct Method { uint32_t attributes, implementation; };
uint32_t flags(const void *method, uint32_t *implementation) {
    const auto &value = *static_cast<const Method *>(method);
    *implementation = value.implementation;
    return value.attributes;
}

int main() {
    using namespace limbus_language;
    // A real instance accessor can have implementation bit 0x10 set. Conversely,
    // a static method must be rejected even when its implementation flags are zero.
    for (uint32_t implementation : {0u, 0x10u}) {
        Method instance{0x886, implementation};
        assert(is_instance_method(&instance, flags));
        for (uint32_t attribute : {0x10u, 0x400u, 0x2000u}) {
            Method rejected{0x886 | attribute, implementation};
            assert(!is_instance_method(&rejected, flags));
        }
    }
    Method method{0x86, 0};
    assert(!is_instance_method(nullptr, flags));
    assert(!is_instance_method(&method, nullptr));

    // GetLanguage occupies two instructions; SetLanguage starts immediately after it.
    // Exercise all four BTI forms, 4-byte and 8-byte entry alignment, and a page edge.
    for (uint32_t bti : {0u, 0xd503241fu, 0xd503245fu, 0xd503249fu, 0xd50324dfu}) {
        for (uintptr_t address : {uintptr_t(0x1000), uintptr_t(0x1004), uintptr_t(0x3ffc)}) {
            alignas(8) uint32_t code[] = {0xd503201f, 0xb9400800, 0xd65f03c0,
                                         0xb9000801, 0xd65f03c0, 0xd503201f};
            uint32_t *entry = code + 1;
            const size_t width = bti ? 12 : 8;
            if (bti) { entry[0] = bti; entry[1] = 0xb9400800; entry[2] = 0xd65f03c0; }
            unsigned char before[sizeof(code)];
            memcpy(before, code, sizeof(code));
            const auto plan = plan_language_getter_patch(address, address + width, entry[0]);
            assert(plan.strategy == Strategy::kDirect && plan.width == width);
            assert(apply_direct_plan(entry, plan));
            assert(memcmp(before, code, sizeof(uint32_t)) == 0);
            assert(memcmp(before + sizeof(uint32_t) + width,
                          reinterpret_cast<unsigned char *>(entry) + width,
                          sizeof(code) - sizeof(uint32_t) - width) == 0);
            const size_t offset = bti ? 1 : 0;
            if (bti) assert(entry[0] == bti);
            assert(entry[offset] == 0x52800040 && entry[offset + 1] == 0xd65f03c0);
            assert(plan_language_getter_patch(address, address + width - 4, bti).strategy == Strategy::kReject);

            // The protected whole region remains equal only when these exact owned
            // bytes are normalized. Damage to the next method is never exempted.
            limbus_owned_code::Patch patch = {};
            patch.offset = 4; patch.size = width;
            memcpy(patch.before, before + 4, width);
            memcpy(patch.after, entry, width);
            const std::vector<limbus_owned_code::Range> ranges = {{0, sizeof(code)}};
            uint32_t original_crc = 0, live_crc = 0;
            size_t owned = 0;
            assert(limbus_owned_code::ranges_crc(before, sizeof(code), ranges, {}, original_crc, owned));
            assert(limbus_owned_code::ranges_crc(reinterpret_cast<unsigned char *>(code), sizeof(code),
                                                 ranges, {patch}, live_crc, owned));
            assert(original_crc == live_crc && owned == width);
            code[5] ^= 1;
            assert(limbus_owned_code::ranges_crc(reinterpret_cast<unsigned char *>(code), sizeof(code),
                                                 ranges, {patch}, live_crc, owned));
            assert(live_crc != original_crc);
            entry[offset] ^= 1;
            assert(!limbus_owned_code::ranges_crc(reinterpret_cast<unsigned char *>(code), sizeof(code),
                                                  ranges, {patch}, live_crc, owned));
        }
    }
    for (uintptr_t next : {uintptr_t(0), uintptr_t(0x1000), uintptr_t(0x1004), uintptr_t(0x1007)})
        assert(plan_language_getter_patch(0x1000, next, 0).strategy == Strategy::kReject);
    assert(plan_language_getter_patch(0, 16, 0).strategy == Strategy::kReject);
    assert(plan_language_getter_patch(0x1001, 0x1010, 0).strategy == Strategy::kReject);
    assert(plan_language_getter_patch(UINTPTR_MAX - 3, 4, 0).strategy == Strategy::kReject);
    alignas(8) uint32_t untouched[4] = {1, 2, 3, 4};
    auto malformed = plan_language_getter_patch(0x1000, 0x1010, 0);
    malformed.width = 20;
    assert(!apply_direct_plan(untouched, malformed));
    assert(untouched[0] == 1 && untouched[3] == 4);
    assert(!apply_direct_plan(nullptr, Plan{}));
    for (size_t page : {size_t(4096), size_t(16384)}) {
        runtime_page::Span span;
        assert(runtime_page::span(page * 4 - 4, 8, page, span));
        assert(span.start == page * 3 && span.length == page * 2);
    }
    puts("PASS: bounded 8/12-byte getters, adjacent code, BTI, flags, page edges and exact owned CRC");
}
