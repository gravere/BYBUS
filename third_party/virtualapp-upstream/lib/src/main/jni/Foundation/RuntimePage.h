#pragma once
#include <stddef.h>
#include <stdint.h>

namespace runtime_page {
constexpr size_t kMaxSupported = 16384;
inline bool supported(size_t page) { return page == 4096 || page == kMaxSupported; }
struct Span { uintptr_t start; size_t length; };

// Smallest complete page range containing the bytes, without wraparound.
inline bool span(uintptr_t address, size_t length, size_t page, Span &out) {
    out = {0, 0};
    if (!address || !length || !supported(page) || length - 1 > UINTPTR_MAX - address)
        return false;
    const uintptr_t mask = static_cast<uintptr_t>(page - 1);
    const uintptr_t first = address & ~mask;
    const uintptr_t last = (address + length - 1) & ~mask;
    if (last > UINTPTR_MAX - page || last - first > SIZE_MAX - page) return false;
    out = {first, static_cast<size_t>(last - first) + page};
    return true;
}

// Initialize before installing signal handlers; handlers only read the cache.
size_t size();
bool register_executable_page(uintptr_t address);
void unregister_executable_page(uintptr_t address);
bool owns_executable_page(uintptr_t start, uintptr_t end);
}
