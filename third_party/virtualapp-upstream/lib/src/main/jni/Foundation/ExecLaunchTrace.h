#pragma once

#include <atomic>
#include <cstdint>
#include <new>
#include <sys/mman.h>
#include <unistd.h>

namespace container_exec {

enum Phase { Entered = 1, PathReady = 2, ElfReady = 3, EnvironmentReady = 4, Failed = 5,
    Descriptors = 6, DescriptorFlags = 7, WorkingDirectory = 8 };

// Initialized in the parent before hooks. MAP_SHARED also covers Bionic's
// non-CLONE_VM vfork fallback. Child writes contain no paths, commands or locks.
class LaunchTrace {
    struct Entry {
        std::atomic<uint32_t> sequence{0};
        std::atomic<int32_t> pid{0}, phase{0}, error{0};
    };
    Entry *entries_ = nullptr;
    pid_t owner_ = 0;
public:
    static constexpr int kEntries = 16;
    static constexpr int kFields = 3;
    void initialize() {
        if (entries_) return;
        static_assert(ATOMIC_INT_LOCK_FREE == 2, "exec trace must never use a lock");
        void *memory = mmap(nullptr, sizeof(Entry) * kEntries, PROT_READ | PROT_WRITE,
                            MAP_SHARED | MAP_ANONYMOUS, -1, 0);
        if (memory == MAP_FAILED) return;
        auto *entries = static_cast<Entry *>(memory);
        for (int i = 0; i < kEntries; ++i) new (entries + i) Entry();
        owner_ = getpid();
        entries_ = entries;
    }
    void record(Phase phase, int error = 0) {
        if (!entries_) return;
        const pid_t pid = getpid();
        if (pid <= 0 || pid == owner_) return;
        Entry &entry = entries_[static_cast<unsigned>(pid) % kEntries];
        // Each PID has a single surviving thread between vfork and exec. Two
        // concurrent children can collide: discard a busy slot, never wait.
        uint32_t sequence = entry.sequence.load(std::memory_order_relaxed);
        if ((sequence & 1) || !entry.sequence.compare_exchange_strong(sequence, sequence + 1)) return;
        const int previous = entry.phase.load(std::memory_order_relaxed);
        if (phase >= Descriptors && entry.pid.load(std::memory_order_relaxed) == pid
                && previous >= Entered && previous <= Failed) {
            entry.sequence.store(sequence + 2, std::memory_order_release);
            return;
        }
        entry.pid.store(pid, std::memory_order_relaxed);
        entry.phase.store(phase, std::memory_order_relaxed);
        entry.error.store(error, std::memory_order_relaxed);
        entry.sequence.store(sequence + 2, std::memory_order_release);
    }
    int snapshot(int64_t *values, int capacity) const {
        if (!entries_ || capacity < kEntries * kFields) return 0;
        int size = 0;
        for (int i = 0; i < kEntries; ++i) {
            const Entry &entry = entries_[i];
            const uint32_t before = entry.sequence.load(std::memory_order_acquire);
            if (before & 1) continue;
            const int32_t pid = entry.pid.load(std::memory_order_relaxed);
            const int32_t phase = entry.phase.load(std::memory_order_relaxed);
            const int32_t error = entry.error.load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            if (pid <= 0 || before != entry.sequence.load(std::memory_order_acquire)) continue;
            values[size++] = pid; values[size++] = phase; values[size++] = error;
        }
        return size;
    }
};

inline LaunchTrace &launchTrace() { static LaunchTrace trace; return trace; }

}
