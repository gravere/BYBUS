#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusProcessScope.h"
#include <assert.h>
#include <stdio.h>

static const limbus_process::Scope scope;

int main() {
    assert(scope.isOwner());
    assert(!scope.isDirectChild(getpid()));
    assert(!scope.isDirectChild(getppid()));
    assert(!scope.isDirectChild(0));
    assert(!scope.isDirectChild(-1));
    int ready[2];
    assert(pipe(ready) == 0);
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        close(ready[0]);
        // No game-only signal or exit interception is allowed in an inherited helper.
        assert(!scope.isOwner());
        assert(!scope.isDirectChild(getppid()));
        char byte = '1';
        assert(write(ready[1], &byte, 1) == 1);
        close(ready[1]);
        for (;;) pause();
    }
    close(ready[1]);
    char byte;
    assert(read(ready[0], &byte, 1) == 1);
    close(ready[0]);
    errno = EBUSY;
    assert(scope.isDirectChild(child));
    assert(errno == EBUSY);
    assert(kill(child, SIGKILL) == 0);
    siginfo_t observed = {};
    assert(waitid(P_PID, child, &observed, WEXITED | WNOWAIT) == 0);
    assert(scope.isDirectChild(child));
    int status;
    assert(waitpid(child, &status, 0) == child); // Ownership probes did not reap it.
    assert(WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
    assert(!scope.isDirectChild(child));
    assert(scope.isOwner());
    puts("process scope: fork isolation, child cleanup, no reaping, errno preservation passed");
}
