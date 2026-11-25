#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/ExecLaunchTrace.h"
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <sys/wait.h>

int main() {
    auto &trace = container_exec::launchTrace();
    trace.initialize();
    int64_t values[48]{};
    trace.record(container_exec::Entered);
    assert(trace.snapshot(values, 48) == 0); // No owner commands captured.
    for (int shared = 0; shared < 2; ++shared) {
        const pid_t pid = shared ? vfork() : fork();
        assert(pid >= 0);
        if (pid == 0) {
            trace.record(container_exec::Descriptors);
            trace.record(container_exec::DescriptorFlags);
            trace.record(container_exec::Entered);
            trace.record(container_exec::EnvironmentReady);
            errno = ENOENT;
            trace.record(container_exec::Failed, errno);
            trace.record(container_exec::Descriptors); // Error-pipe cleanup must not erase exec errno.
            _exit(errno == ENOENT ? 0 : 1);
        }
        int status = -1;
        assert(waitpid(pid, &status, 0) == pid && status == 0);
        const int size = trace.snapshot(values, 48);
        bool found = false;
        for (int i = 0; i < size; i += 3) {
            if (values[i] == pid) {
                assert(values[i + 1] == container_exec::Failed && values[i + 2] == ENOENT);
                found = true;
            }
        }
        assert(found);
    }
    assert(trace.snapshot(values, 1) == 0);
    puts("exec trace: fork, vfork, preserved errno, bounded numeric snapshot passed");
}
