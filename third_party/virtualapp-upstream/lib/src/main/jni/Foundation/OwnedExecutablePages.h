#pragma once
#include "RuntimePage.h"
#include <atomic>

// Only explicitly allocated BYBUS pages qualify. A matching size/protection in
// /proc/maps alone is not evidence that an anonymous mapping belongs to us.
template<size_t Capacity> class OwnedExecutablePages {
    std::atomic<uintptr_t> entries_[Capacity];
public:
    OwnedExecutablePages() {
        for (auto &entry : entries_) entry.store(0, std::memory_order_relaxed);
    }
    bool add(uintptr_t address, size_t page) {
        runtime_page::Span span;
        if (!runtime_page::span(address, page, page, span) || span.start != address) return false;
        for (auto &entry : entries_) {
            uintptr_t empty = 0;
            if (entry.load(std::memory_order_acquire) == address ||
                    entry.compare_exchange_strong(empty, address, std::memory_order_acq_rel)) return true;
        }
        return false;
    }
    void remove(uintptr_t address) {
        if (!address) return;
        for (auto &entry : entries_) {
            uintptr_t expected = address;
            entry.compare_exchange_strong(expected, 0, std::memory_order_acq_rel);
        }
    }
    bool contains(uintptr_t start, uintptr_t end, size_t page) const {
        if (!runtime_page::supported(page) || end <= start || end - start != page || !start) return false;
        for (const auto &entry : entries_)
            if (entry.load(std::memory_order_acquire) == start) return true;
        return false;
    }
};
