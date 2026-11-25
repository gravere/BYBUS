// Run with the complete production ARM64 container library in a disposable
// Android / Bionic environment. No game, credentials or user files are needed.
#include <cassert>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#include <string>

int main(int argc, char **argv) {
    assert(argc == 2);
    void *lib = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!lib) { fprintf(stderr, "%s\n", dlerror()); return 2; }
    auto start = reinterpret_cast<void (*)(int)>(dlsym(lib, "_Z11startIOHooki"));
    auto table = reinterpret_cast<void *(*)()>(dlsym(lib, "_ZN22virtualFileDescribeSet9getVFDSetEv"));
    auto set = reinterpret_cast<void (*)(void *, int, int)>(dlsym(lib, "_ZN22virtualFileDescribeSet7setFlagEii"));
    auto clear = reinterpret_cast<void (*)(void *, int)>(dlsym(lib, "_ZN22virtualFileDescribeSet9clearFlagEi"));
    auto closeFd = reinterpret_cast<int (*)(int)>(dlsym(lib, "new_close"));
    auto writeFd = reinterpret_cast<ssize_t (*)(int, const void *, size_t)>(dlsym(lib, "new_write"));
    auto flags = reinterpret_cast<int (*)(int, int, ...)>(dlsym(lib, "new_fcntl"));
    assert(start && table && set && clear && closeFd && writeFd && flags);
    start(35);
    int pipeFd[2];
    assert(pipe(pipeFd) == 0);
    set(table(), pipeFd[1], 1); // Parent-side bookkeeping inherited at fork.
    int result = 0;
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        bool ok = flags(pipeFd[1], F_SETFD, FD_CLOEXEC) == 0
            && flags(pipeFd[1], F_GETFD) == FD_CLOEXEC
            && writeFd(pipeFd[1], "ok", 2) == 2
            && closeFd(pipeFd[1]) == 0;
        _exit(ok ? 0 : 1);
    }
    int status = -1;
    assert(waitpid(child, &status, 0) == child);
    clear(table(), pipeFd[1]);
    close(pipeFd[1]);
    char data[3]{};
    ssize_t received = read(pipeFd[0], data, 2);
    close(pipeFd[0]);
    result = status == 0 && received == 2 && strcmp(data, "ok") == 0 ? 0 : 1;
    fprintf(stderr, "child FD inherited-parent-state isolation=%s status=%d received=%zd\n",
            result ? "FAIL" : "PASS", status, received);
    auto proc = reinterpret_cast<bool (*)(pid_t, pid_t, int, std::string &)>(dlsym(lib, "readStartupProcFromKernel"));
    if (proc) {
        prctl(PR_SET_NAME, "UnityMain", 0, 0, 0);
        std::string value;
        bool own = proc(getpid(), gettid(), 0, value) && value == "UnityMain";
        bool other = !proc(getppid(), 0, 0, value);
        char path[128], facade[64]{};
        snprintf(path, sizeof(path), "/proc/%d/task/%d/comm", getpid(), gettid());
        FILE *file = fopen(path, "r");
        if (file) { fgets(facade, sizeof(facade), file); fclose(file); }
        bool separate = strcmp(facade, "UnityMain\n") != 0;
        fprintf(stderr, "production proc original-name=%d unrelated-process-denied=%d guest-view-separated=%d\n", own, other, separate);
        result |= !separate;
        result |= !own || !other;
    }
    // Do not invoke the container's game exit policy in this native harness.
    register long code asm("x0") = result;
    register long nr asm("x8") = __NR_exit_group;
    asm volatile("svc 0" : "+r"(code) : "r"(nr) : "memory");
    __builtin_unreachable();
}
