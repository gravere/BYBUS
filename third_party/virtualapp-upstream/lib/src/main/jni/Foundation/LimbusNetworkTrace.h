#pragma once
#include "IoErrno.h"
#include <atomic>
#include <cstdio>
#include <time.h>
#include <unistd.h>
#include <sys/syscall.h>

namespace limbus_network {
enum Api { Resolve = 0, Connect = 1, Send = 2 };
// Open once before guest execution. No addresses, payloads, URLs or credentials.
class Trace {
    std::atomic<int> fd_{-1};
    std::atomic<unsigned> normal_[3]{};
    std::atomic<unsigned> failures_[3]{};
public:
    bool open(const char *path) {
        limbus_io::PreserveErrno saved;
        if (fd_.load() >= 0) return true;
        int fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
        if (fd < 0) return false;
        fd_.store(fd);
        return true;
    }
    void record(Api api, int socket, long result, int error) {
        limbus_io::PreserveErrno saved;
        int fd = fd_.load();
        if (fd < 0) return;
        bool failed = result != 0 && api == Resolve;
        failed |= result < 0 && api != Resolve && error != EAGAIN && error != EINPROGRESS && error != EINTR;
        auto &quota = failed ? failures_[api] : normal_[api];
        // At most 84 records, with an independent error quota for each API.
        if (quota.fetch_add(1, std::memory_order_relaxed) >= (failed ? 24u : 4u)) return;
        timespec now{};
        clock_gettime(CLOCK_REALTIME, &now);
        char line[256];
        int size = snprintf(line, sizeof(line), "time=%lld.%03ld pid=%d tid=%ld api=%d fd=%d result=%ld errno=%d\n",
                static_cast<long long>(now.tv_sec), now.tv_nsec / 1000000, getpid(),
                syscall(__NR_gettid), static_cast<int>(api), socket, result, error);
        if (size > 0 && size < static_cast<int>(sizeof(line))) syscall(__NR_write, fd, line, size);
    }
};
extern Trace trace;
}
