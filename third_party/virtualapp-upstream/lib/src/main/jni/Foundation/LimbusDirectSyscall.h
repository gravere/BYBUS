#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>

namespace limbus_direct_syscall {
constexpr size_t kTrampolineSize = 48;

inline bool branch(uintptr_t from, uintptr_t to, uint32_t &instruction) {
    // Both addresses belong to the same mapped address space.
    int64_t delta = static_cast<int64_t>(to) - static_cast<int64_t>(from);
    if ((delta & 3) || delta < -0x08000000LL || delta >= 0x08000000LL) return false;
    instruction = 0x14000000u | (static_cast<uint32_t>(delta / 4) & 0x03ffffffu);
    return true;
}

inline bool build(void *memory, uintptr_t source, uintptr_t handler, uintptr_t entry) {
    if (!memory || !source || !handler || !entry) return false;
    const uintptr_t base = reinterpret_cast<uintptr_t>(memory);
    uint32_t back;
    if (!branch(base + 28, source + 4, back)) return false;
    // Enter with B (not BL). Keep LR and both scratch registers intact across SVC.
    uint32_t code[] = {
        0xa9be47f0, // stp x16, x17, [sp, #-32]!
        0x100000b1, // adr x17, landing (+20)
        0xf9000bf1, // str x17, [sp, #16]
        0x580000b0, // ldr x16, handler (+20)
        0x580000d1, // ldr x17, entry (+24)
        0xd61f0220, // br x17
        0xa8c247f0, // landing: ldp x16, x17, [sp], #32
        back
    };
    memcpy(memory, code, sizeof(code));
    memcpy(static_cast<char *>(memory) + 32, &handler, sizeof(handler));
    memcpy(static_cast<char *>(memory) + 40, &entry, sizeof(entry));
    return true;
}

inline long kernelResult(long result, int error) { return result == -1 ? -error : result; }

#if defined(__aarch64__)
// A Linux SVC preserves the caller's state except x0. A C call does not: even
// q8-q15 only preserve their low 64 bits. Save the complete state, including LR,
// NZCV and FP controls; the per-site landing restores the original x16/x17.
__attribute__((naked, noinline)) static void entry() {
    __asm__ volatile(
        "sub sp, sp, #784\n"
        "stp x0, x1, [sp, #0]\nstp x2, x3, [sp, #16]\n"
        "stp x4, x5, [sp, #32]\nstp x6, x7, [sp, #48]\n"
        "stp x8, x9, [sp, #64]\nstp x10, x11, [sp, #80]\n"
        "stp x12, x13, [sp, #96]\nstp x14, x15, [sp, #112]\n"
        "stp x18, x19, [sp, #144]\nstp x20, x21, [sp, #160]\n"
        "stp x22, x23, [sp, #176]\nstp x24, x25, [sp, #192]\n"
        "stp x26, x27, [sp, #208]\nstp x28, x29, [sp, #224]\n"
        "str x30, [sp, #240]\nmrs x9, nzcv\nstr x9, [sp, #248]\n"
        "stp q0, q1, [sp, #256]\nstp q2, q3, [sp, #288]\n"
        "stp q4, q5, [sp, #320]\nstp q6, q7, [sp, #352]\n"
        "stp q8, q9, [sp, #384]\nstp q10, q11, [sp, #416]\n"
        "stp q12, q13, [sp, #448]\nstp q14, q15, [sp, #480]\n"
        "stp q16, q17, [sp, #512]\nstp q18, q19, [sp, #544]\n"
        "stp q20, q21, [sp, #576]\nstp q22, q23, [sp, #608]\n"
        "stp q24, q25, [sp, #640]\nstp q26, q27, [sp, #672]\n"
        "stp q28, q29, [sp, #704]\nstp q30, q31, [sp, #736]\n"
        "mrs x9, fpcr\nstr x9, [sp, #768]\nmrs x9, fpsr\nstr x9, [sp, #776]\n"
        "blr x16\nstr x0, [sp]\n"
        "ldr x9, [sp, #768]\nmsr fpcr, x9\nldr x9, [sp, #776]\nmsr fpsr, x9\n"
        "ldr x9, [sp, #248]\nmsr nzcv, x9\n"
        "ldp q0, q1, [sp, #256]\nldp q2, q3, [sp, #288]\n"
        "ldp q4, q5, [sp, #320]\nldp q6, q7, [sp, #352]\n"
        "ldp q8, q9, [sp, #384]\nldp q10, q11, [sp, #416]\n"
        "ldp q12, q13, [sp, #448]\nldp q14, q15, [sp, #480]\n"
        "ldp q16, q17, [sp, #512]\nldp q18, q19, [sp, #544]\n"
        "ldp q20, q21, [sp, #576]\nldp q22, q23, [sp, #608]\n"
        "ldp q24, q25, [sp, #640]\nldp q26, q27, [sp, #672]\n"
        "ldp q28, q29, [sp, #704]\nldp q30, q31, [sp, #736]\n"
        "ldp x0, x1, [sp, #0]\nldp x2, x3, [sp, #16]\n"
        "ldp x4, x5, [sp, #32]\nldp x6, x7, [sp, #48]\n"
        "ldp x8, x9, [sp, #64]\nldp x10, x11, [sp, #80]\n"
        "ldp x12, x13, [sp, #96]\nldp x14, x15, [sp, #112]\n"
        "ldp x18, x19, [sp, #144]\nldp x20, x21, [sp, #160]\n"
        "ldp x22, x23, [sp, #176]\nldp x24, x25, [sp, #192]\n"
        "ldp x26, x27, [sp, #208]\nldp x28, x29, [sp, #224]\n"
        "ldr x30, [sp, #240]\nadd sp, sp, #784\nldr x16, [sp, #16]\nbr x16\n");
}
#endif
} // namespace limbus_direct_syscall
