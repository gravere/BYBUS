#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

// Bounded constant-return strategy contributed by UMP40 in BYBUS PR #2.
// Planning is independent of Android so adjacent-function boundaries can be tested.
namespace limbus_language {
enum class Strategy { kReject, kDirect };
struct Plan {
    Strategy strategy = Strategy::kReject;
    size_t width = 0;
    uint32_t words[3] = {};
};

using MethodFlags = uint32_t (*)(const void *, uint32_t *);
inline bool is_instance_method(const void *method, MethodFlags get_flags) {
    if (!method || !get_flags) return false;
    uint32_t implementation_flags = 0;
    const uint32_t flags = get_flags(method, &implementation_flags);
    // The out parameter contains implementation flags, not METHOD_ATTRIBUTE_*.
    return (flags & (0x0010u | 0x0400u | 0x2000u)) == 0; // static / abstract / pinvoke
}

inline bool is_bti_landing_pad(uint32_t word) {
    return word == 0xd503241fu || word == 0xd503245fu ||
           word == 0xd503249fu || word == 0xd50324dfu;
}

inline Plan plan_language_getter_patch(uintptr_t target, uintptr_t next_method,
                                       uint32_t first_word) {
    Plan plan;
    if (!target || (target & 3u) || (next_method & 3u) || next_method <= target)
        return plan;
    const bool bti = is_bti_landing_pad(first_word);
    const size_t width = bti ? 12u : 8u;
    if (next_method - target < width) return plan;
    plan.strategy = Strategy::kDirect;
    plan.width = width;
    size_t index = 0;
    if (bti) plan.words[index++] = first_word;
    plan.words[index++] = 0x52800040u; // movz w0, #2 (LOCALIZE_LANGUAGE.JP)
    plan.words[index] = 0xd65f03c0u;   // ret
    return plan;
}

// The caller must own a validated writable mapping and flush its instruction cache.
inline bool apply_direct_plan(void *target, const Plan &plan) {
    const size_t offset = plan.width == 12u ? 1u : 0u;
    if (!target || (reinterpret_cast<uintptr_t>(target) & 3u) ||
        plan.strategy != Strategy::kDirect || (plan.width != 8u && plan.width != 12u) ||
        (offset && !is_bti_landing_pad(plan.words[0])) ||
        plan.words[offset] != 0x52800040u || plan.words[offset + 1] != 0xd65f03c0u)
        return false;
    memcpy(target, plan.words, plan.width);
    return true;
}
} // namespace limbus_language
