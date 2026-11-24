#pragma once

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <signal.h>
#include <time.h>
#include <unistd.h>
#include <sys/syscall.h>

namespace limbus_termination {
enum Event { Ready = 0, Kill = 1, Raise = 2, Tkill = 3, Tgkill = 4,
    PthreadKill = 5, Exit = 6, Syscall = 7, Fault = 8, UnhandledFault = 9 };
// No data from strings, registers, paths, accounts or game resources.
// Existing hooks only observe; return values and signal handling stay unchanged.
class Trace {
    std::atomic<int> fd_{-1};
    std::atomic<unsigned> total_{0};
    std::atomic<unsigned> events_[10]{};
    static char *text(char *p, const char *value) {
        while (*value) *p++ = *value++;
        return p;
    }
    static char *number(char *p, int64_t value) {
        char digits[24]; unsigned size = 0;
        uint64_t magnitude = value < 0 ? uint64_t(-(value + 1)) + 1 : uint64_t(value);
        if (value < 0) *p++ = '-';
        do { digits[size++] = char('0' + magnitude % 10); magnitude /= 10; } while (magnitude);
        while (size) *p++ = digits[--size];
        return p;
    }
public:
    // Called once, before game code is loaded, with a private per-process path.
    bool open(const char *path) {
        if (fd_.load() >= 0) return true;
        int fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
        if (fd < 0) return false;
        fd_.store(fd);
        record(Ready, 0, 0, 0, 0);
        return true;
    }
    static bool interesting(int signal) {
        return signal == SIGKILL || signal == SIGTERM || signal == SIGABRT ||
               signal == SIGQUIT || signal == SIGTRAP || signal == SIGXCPU || signal == SIGPWR;
    }
    void record(Event event, int value, int target, uintptr_t caller, int disposition) {
        static_assert(ATOMIC_INT_LOCK_FREE == 2, "Trace must not lock inside a signal handler");
        const int saved_errno = errno;
        const int fd = fd_.load(std::memory_order_relaxed);
        // Reserve space across event classes: a noisy forwarded signal cannot
        // consume the slots needed to record exit/abort or a native fault.
        if (fd >= 0 && events_[event].fetch_add(1, std::memory_order_relaxed) < 8) {
            const unsigned sequence = total_.fetch_add(1, std::memory_order_relaxed);
            if (sequence < 80) {
                struct timespec wall{}, mono{};
                clock_gettime(CLOCK_REALTIME, &wall);
                clock_gettime(CLOCK_MONOTONIC, &mono);
                char buffer[384]; char *p = buffer;
                p = text(p, "seq="); p = number(p, sequence);
                p = text(p, " unixMs="); p = number(p, int64_t(wall.tv_sec) * 1000 + wall.tv_nsec / 1000000);
                p = text(p, " monoMs="); p = number(p, int64_t(mono.tv_sec) * 1000 + mono.tv_nsec / 1000000);
                p = text(p, " pid="); p = number(p, getpid());
                p = text(p, " tid="); p = number(p, ::syscall(SYS_gettid));
                p = text(p, " event="); p = number(p, event);
                p = text(p, " value="); p = number(p, value);
                p = text(p, " target="); p = number(p, target);
                p = text(p, " caller="); p = number(p, static_cast<int64_t>(caller));
                p = text(p, " disposition="); p = number(p, disposition);
                *p++ = '\n';
                // A fixed slot avoids cross-thread interleaving and does not
                // rely on a Java callback, logcat retention or process shutdown.
                memset(p, ' ', sizeof(buffer) - size_t(p - buffer));
                buffer[sizeof(buffer) - 1] = '\n';
                (void)pwrite(fd, buffer, sizeof(buffer), off_t(sequence) * sizeof(buffer));
            }
        }
        errno = saved_errno;
    }
};
extern Trace trace;
} // namespace limbus_termination
