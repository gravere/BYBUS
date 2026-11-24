#pragma once

#include <errno.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

namespace limbus_process {

// An exec loads a new library; a fork inherits this owner. The forked helper must
// not inherit game-only exit/signal handling while it prepares its exec.
class Scope {
    const pid_t owner_ = getpid();
public:
    // getpid/waitid are not intercepted. Calling the generic syscall entry here
    // would recurse: that entry asks is_limbus_container_process() before dispatch.
    bool isOwner() const { return owner_ == getpid(); }

    bool isDirectChild(pid_t target) const {
        if (!isOwner() || target <= 0 || target == owner_) return false;
        const int saved_errno = errno;
        siginfo_t status = {};
        int result;
        do {
            // WNOWAIT never reaps a child or competes with Java Process.waitFor.
            // For a live child WNOHANG returns zero; unrelated processes give ECHILD.
            result = waitid(P_PID, target, &status, WEXITED | WNOHANG | WNOWAIT);
        } while (result < 0 && errno == EINTR);
        errno = saved_errno;
        return result == 0;
    }
};

}
