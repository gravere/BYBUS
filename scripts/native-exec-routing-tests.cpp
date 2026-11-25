// Run with the complete production ARM64 library in a disposable Bionic runtime.
// Arguments: library path, a real executable that exits 0 (for example /bin/true).
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <sys/syscall.h>
#include <unistd.h>
extern char **environ;

int main(int argc, char **argv) {
    assert(argc == 3);
    auto lib = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!lib) { fprintf(stderr, "%s\n", dlerror()); return 2; }
    auto start = reinterpret_cast<void (*)(int)>(dlsym(lib, "_Z11startIOHooki"));
    auto redirect = reinterpret_cast<int (*)(const char *, const char *)>(dlsym(lib, "_Z16add_replace_itemPKcS0_"));
    auto forbid = reinterpret_cast<int (*)(const char *)>(dlsym(lib, "_Z18add_forbidden_itemPKc"));
    assert(start && redirect && forbid);
    redirect("/bybus-exec-fixture", argv[2]);
    redirect("/bybus-path/fixture", argv[2]);
    forbid("/bybus-exec-denied");
    setenv("PATH", "/bybus-path", 1);
    start(35);
    int failures = 0;
    const char *targets[] = {"/bybus-exec-fixture", "fixture", "/bybus-exec-denied", "/bybus-exec-missing"};
    for (int mode = 0; mode != 3; ++mode) {
        for (int which = 0; which != 4; ++which) {
            if (mode == 0 && which == 1) continue; // execve does not search PATH.
            char *args[] = {const_cast<char *>(targets[which]), nullptr};
            const pid_t child = vfork();
            if (child == 0) {
                if (mode == 0) execve(args[0], args, environ);
                if (mode == 1) execvp(args[0], args);
                if (mode == 2) execvpe(args[0], args, environ);
                _exit(which == 2 && errno == EACCES ? 42 : which == 3 && errno == ENOENT ? 43 : 99);
            }
            int status = -1;
            assert(child > 0 && waitpid(child, &status, 0) == child);
            const int expected = which == 2 ? 42 : which == 3 ? 43 : 0;
            const bool ok = WIFEXITED(status) && WEXITSTATUS(status) == expected;
            fprintf(stderr, "exec-route mode=%d case=%d pass=%d status=%d\n", mode, which, ok, status);
            failures += !ok;
        }
    }
    // Avoid the game owner's existing exit policy in this standalone harness.
    register long code asm("x0") = failures;
    register long nr asm("x8") = __NR_exit_group;
    asm volatile("svc 0" : "+r"(code) : "r"(nr) : "memory");
    __builtin_unreachable();
}
