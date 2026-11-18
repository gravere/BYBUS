#pragma once
#include <stdint.h>

namespace limbus_method {
using MethodFlags = uint32_t (*)(const void *, uint32_t *);
inline bool is_instance_method(const void *method, MethodFlags get_flags) {
    if (!method || !get_flags) return false;
    uint32_t implementation_flags = 0;
    const uint32_t flags = get_flags(method, &implementation_flags);
    // The out parameter contains implementation flags, not METHOD_ATTRIBUTE_*.
    return (flags & (0x0010u | 0x0400u | 0x2000u)) == 0; // static / abstract / pinvoke
}
} // namespace limbus_method
