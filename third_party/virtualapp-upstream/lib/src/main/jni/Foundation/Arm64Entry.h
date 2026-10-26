#pragma once
#include <stddef.h>
#include <stdint.h>

// Reject short tail wrappers before an absolute inline branch could overwrite
// the following function. BL is a call and SVC returns, so neither ends a wrapper.
inline bool arm64_entry_has_room(const uint32_t *code, size_t available, size_t required) {
    if (!code || !required || required > available || required > 5) return false;
    for (size_t i = 0; i + 1 < required; ++i) {
        const uint32_t op = code[i];
        if ((op & 0xfc000000u) == 0x14000000u || // B immediate
            (op & 0xfffffc1fu) == 0xd61f0000u || // BR register
            (op & 0xfffffc1fu) == 0xd65f0000u || // RET
            op == 0xd65f0bffu || op == 0xd65f0fffu) return false; // RETAA / RETAB
    }
    return true;
}
