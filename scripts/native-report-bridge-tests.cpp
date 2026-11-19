#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusReportBridge.h"
#include <assert.h>
#include <stdio.h>

static bool skip = false;
static uintptr_t observed_code = 0;
static int original_calls = 0;

extern "C" uintptr_t original_report(uintptr_t a, uintptr_t b, uintptr_t c, uintptr_t d,
                                   uintptr_t e, uintptr_t f, uintptr_t g, uintptr_t h, double x, double y) {
    ++original_calls;
    assert(a == 11 && b == 22 && c == 30010 && d == 44 && e == 55 && f == 66 && g == 77 && h == 88);
    assert(x == 1.25 && y == 2.5);
    return 0x12345678;
}

extern "C" LimbusReportDecision limbus_report_dispatch_impl(uintptr_t code, uintptr_t caller) {
    observed_code = code;
    // Deliberately overwrite floating arguments: the actual bridge must preserve them.
    __asm__ volatile("movi v0.16b, #0\nmovi v1.16b, #0" ::: "v0", "v1");
    return skip ? LimbusReportDecision{0, caller + 8}
                : LimbusReportDecision{reinterpret_cast<uintptr_t>(original_report), 0};
}

extern "C" uintptr_t call_bridge(uintptr_t, uintptr_t, uintptr_t, uintptr_t,
                                 uintptr_t, uintptr_t, uintptr_t, uintptr_t, double, double)
        asm("limbus_report_dispatch_entry");

extern "C" __attribute__((naked)) uintptr_t call_cleanup() {
    __asm__ volatile("stp x29, x30, [sp, #-16]!\nmov x29, sp\n"
                     "mov x2, #50048\nbl limbus_report_dispatch_entry\n"
                     "mov x0, #1\nb 2f\nmov x0, #42\n2: ldp x29, x30, [sp], #16\nret\n");
}

int main() {
    uint32_t call[] = {0xaa0003e1, 0x910fe3e0, 0x52987002, 0x52800023, 0x52800004, 0xd63f0260};
    assert(limbus_report_matches(50048, 0x4e568, call, 6));
    assert(!limbus_report_matches(30009, 0x4e568, call, 6));
    assert(!limbus_report_matches(50048, 0x4e564, call, 6));
    assert(!limbus_report_matches(50048, 0x4e568, call, 5));
    assert(!limbus_report_matches(50048, 0x4e568, nullptr, 6));
    for (auto &word : call) {
        word ^= 1;
        assert(!limbus_report_matches(50048, 0x4e568, call, 6));
        word ^= 1;
    }
    for (int i = 0; i < 1000; ++i) {
        skip = false;
        assert(call_bridge(11, 22, 30010, 44, 55, 66, 77, 88, 1.25, 2.5) == 0x12345678);
        assert(observed_code == 30010);
        skip = true;
        assert(call_cleanup() == 42);
        assert(observed_code == 50048);
    }
    assert(original_calls == 1000);
    puts("Report bridge: integer/SIMD arguments, original return value, scoped cleanup and stack restoration passed (1000 cycles)");
}
