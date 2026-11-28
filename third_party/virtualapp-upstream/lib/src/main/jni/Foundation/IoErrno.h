#pragma once
#include <cerrno>
#include <cstdarg>
#include <fcntl.h>

namespace limbus_io {
// Observers run after a libc call and must leave that call's errno untouched.
struct PreserveErrno {
    const int value = errno;
    ~PreserveErrno() { errno = value; }
};

inline unsigned long fcntlArgument(int command, va_list &args) {
    switch (command) {
        case F_GETFD: case F_GETFL: case F_GETOWN:
#ifdef F_GETSIG
        case F_GETSIG:
#endif
#ifdef F_GETLEASE
        case F_GETLEASE:
#endif
#ifdef F_GETPIPE_SZ
        case F_GETPIPE_SZ:
#endif
#ifdef F_GET_SEALS
        case F_GET_SEALS:
#endif
            return 0;
        case F_SETFD: case F_SETFL: case F_SETOWN:
        case F_DUPFD: case F_DUPFD_CLOEXEC:
#ifdef F_SETSIG
        case F_SETSIG:
#endif
#ifdef F_SETLEASE
        case F_SETLEASE:
#endif
#ifdef F_SETPIPE_SZ
        case F_SETPIPE_SZ:
#endif
#ifdef F_ADD_SEALS
        case F_ADD_SEALS:
#endif
            return static_cast<unsigned long>(va_arg(args, int));
        default: return reinterpret_cast<unsigned long>(va_arg(args, void *));
    }
}
}
