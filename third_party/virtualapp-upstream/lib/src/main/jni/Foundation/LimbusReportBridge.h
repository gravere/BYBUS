#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>

inline bool limbus_report_matches(uintptr_t code, uintptr_t return_offset,
                                  const uint32_t *instructions, size_t count) {
    static constexpr uint32_t expected[] = {
            0xaa0003e1, 0x910fe3e0, 0x52987002, 0x52800023, 0x52800004, 0xd63f0260};
    return code == 50048 && return_offset == 0x4e568 && instructions && count == 6
            && memcmp(instructions, expected, sizeof(expected)) == 0;
}

struct LimbusReportDecision { uintptr_t target; uintptr_t continuation; };
extern "C" LimbusReportDecision limbus_report_dispatch_impl(uintptr_t code, uintptr_t caller);

#if defined(__aarch64__)
extern "C" __attribute__((naked)) void limbus_report_dispatch_entry() {
    // Keep the original ABI for every report, including integer, SIMD and indirect-result arguments.
    __asm__ volatile(
            "stp x29, x30, [sp, #-224]!\nmov x29, sp\n"
            "stp x0, x1, [sp, #16]\nstp x2, x3, [sp, #32]\n"
            "stp x4, x5, [sp, #48]\nstp x6, x7, [sp, #64]\nstp x8, x9, [sp, #80]\n"
            "stp q0, q1, [sp, #96]\nstp q2, q3, [sp, #128]\n"
            "stp q4, q5, [sp, #160]\nstp q6, q7, [sp, #192]\n"
            "mov x0, x2\nmov x1, x30\nbl limbus_report_dispatch_impl\n"
            "mov x16, x0\nmov x17, x1\n"
            "ldp x0, x1, [sp, #16]\nldp x2, x3, [sp, #32]\n"
            "ldp x4, x5, [sp, #48]\nldp x6, x7, [sp, #64]\nldp x8, x9, [sp, #80]\n"
            "ldp q0, q1, [sp, #96]\nldp q2, q3, [sp, #128]\n"
            "ldp q4, q5, [sp, #160]\nldp q6, q7, [sp, #192]\n"
            "ldp x29, x30, [sp], #224\ncbnz x17, 1f\nbr x16\n1: mov x30, x17\nret\n");
}
#endif
