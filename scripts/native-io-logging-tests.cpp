// Link the production MapsRedirector, SandboxFs, canonicalize_md and RuntimePage.
// The two socket wrappers in the generated include are extracted unchanged from
// IORelocator64.cpp. Only the system logger/network calls are simulated.
#include <android/log.h>
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <climits>
#include <fcntl.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include "MapsRedirector.h"
#include "SandboxFs.h"
#include "IoErrno.h"
#include "LimbusNetworkTrace.h"
limbus_network::Trace limbus_network::trace;
#include <netdb.h>
#include <fstream>

static pthread_mutex_t logger_lock = PTHREAD_MUTEX_INITIALIZER;
static void (*logger_io)() = nullptr;
static int log_calls = 0;
extern "C" int __android_log_print(int, const char *, const char *, ...) {
    // ART's non-recursive lock surrounds the logger, which may open /proc files.
    // alarm() kills a deadlocked baseline instead of hanging the test runner.
    pthread_mutex_lock(&logger_lock);
    ++log_calls;
    if (logger_io) logger_io();
    pthread_mutex_unlock(&logger_lock);
    errno = EIO; // Deliberately make diagnostic work change TLS errno.
    return 0;
}

static bool is_limbus_container_process() { return true; }
static bool getNetWorkState() { return true; }
static bool isIpV4Enable(const char *) { return true; }
static bool isIpV6Enable(const char *) { return true; }
static void format_limbus_socket_target(const sockaddr *, char *text, size_t size) {
    snprintf(text, size, "test");
}
static int g_limbus_network_io_diag_count = 0, g_limbus_network_diag_count = 0;
static bool isWhiteList() { return false; }
static bool isIPAddress(const char *) { return false; }
static bool isContainsStr(const char *, const char *) { return false; }
static bool isDomainEnable(const char *) { return true; }
static void addWhiteIpStrategy(const char *) { }
namespace originalInterface {
static int original_getaddrinfo(const char *, const char *, const addrinfo *, addrinfo **) {
    errno = ENETUNREACH;
    return EAI_SYSTEM;
}
}
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "test", __VA_ARGS__)
static long test_syscall(long, ...) { errno = ECONNREFUSED; return -1; }
#define HOOK_DEF(ret, name, ...) ret new_##name(__VA_ARGS__)
#define syscall test_syscall
#include "runtime-socket-io-under-test.inc"
#undef syscall

static const char *proc_path;
static bool expect_failure;
static void read_proc() {
    int fd = redirect_proc_file(proc_path, O_RDONLY | O_CLOEXEC, 0);
    if (expect_failure) { assert(fd == -1 && errno == EACCES); return; }
    assert(fd > 0);
    char bytes[64];
    assert(read(fd, bytes, sizeof(bytes)) >= 0);
    close(fd);
}
static void forward_overflow() {
    char buffer[8];
    assert(relocate_path("/probe-source/file", buffer, sizeof(buffer)) == nullptr);
}
static void reverse_overflow() {
    char buffer[8];
    assert(reverse_relocate_path("/probe-target/file", buffer, sizeof(buffer)) == nullptr);
}
static void connect_unix() {
    sockaddr address{}; address.sa_family = AF_UNIX;
    assert(new_connect(3, &address, sizeof(address)) == -1 && errno == ECONNREFUSED);
}
static void sendto_unix() {
    sockaddr address{}; address.sa_family = AF_UNIX;
    assert(new_sendto(3, "", 0, 0, &address, sizeof(address)) == -1 && errno == ECONNREFUSED);
}

static unsigned long fcntl_arg(int command, ...) {
    va_list args; va_start(args, command);
    auto value = limbus_io::fcntlArgument(command, args);
    va_end(args); return value;
}
int main(int argc, char **argv) {
    assert(argc == 3); // Case and writable scratch directory; no app or game data.
    setenv("V_NATIVE_PATH", argv[2], 1);
    setenv("V_LIMBUS_PROCESS_NAME", "bybus.logging.probe", 1);
    unsetenv("V_LIMBUS_REAL_LIB_DIR");
    const char *kind = argv[1];
    if (!strcmp(kind, "forward_overflow") || !strcmp(kind, "reverse_overflow")) {
        add_replace_item("/probe-source/", "/probe-target/");
        log_calls = 0;
        logger_io = !strcmp(kind, "forward_overflow") ? forward_overflow : reverse_overflow;
    } else if (!strcmp(kind, "connect_unix")) logger_io = connect_unix;
    else if (!strcmp(kind, "sendto_unix")) logger_io = sendto_unix;
    else {
        proc_path = kind;
        if (!strcmp(kind, "cache_failure")) {
            proc_path = "/proc/self/cmdline"; expect_failure = true;
            setenv("V_NATIVE_PATH", "/proc/bybus-missing-directory", 1);
        }
        logger_io = read_proc;
    }
    if (!strcmp(kind, "descriptor_and_dns")) {
        logger_io = nullptr;
        assert(fcntl_arg(F_GETFD) == 0 && fcntl_arg(F_GETFL) == 0);
        assert(fcntl_arg(F_SETFD, FD_CLOEXEC) == FD_CLOEXEC);
        assert(fcntl_arg(F_SETOWN, -123) == static_cast<unsigned long>(-123));
        struct flock lock{}; assert(fcntl_arg(F_GETLK, &lock) == reinterpret_cast<unsigned long>(&lock));
        int fd = open("/dev/null", O_RDONLY); assert(fd >= 0);
        assert(fcntl(fd, F_SETFD, fcntl_arg(F_SETFD, FD_CLOEXEC)) == 0);
        assert(fcntl(fd, F_GETFD, fcntl_arg(F_GETFD)) == FD_CLOEXEC);
        close(fd);
        assert(new_getaddrinfo("private.invalid", "443", nullptr, nullptr) == EAI_SYSTEM);
        assert(errno == ENETUNREACH);
        puts("Descriptor argument ABI and DNS errno passed"); return 0;
    }
    if (!strcmp(kind, "trace_bounds")) {
        std::string path = std::string(argv[2]) + "/network-test.log";
        errno = EBUSY; assert(limbus_network::trace.open(path.c_str())); assert(errno == EBUSY);
        for (int i = 0; i < 300; ++i) {
            limbus_network::trace.record(limbus_network::Connect, 19, -1, EINPROGRESS);
            limbus_network::trace.record(limbus_network::Connect, 19, -1, ECONNRESET);
        }
        assert(errno == EBUSY);
        std::ifstream input(path); std::string line; unsigned count = 0;
        while (std::getline(input, line)) { ++count; assert(line.find("api=1") != std::string::npos); }
        assert(count == 28); puts("Bounded numeric-only network trace passed"); return 0;
    }
    alarm(3);
    if (!strcmp(kind, "connect_inet")) {
        proc_path = "/proc/self/cmdline";
        sockaddr_in v4{}; v4.sin_family = AF_INET;
        sockaddr_in6 v6{}; v6.sin6_family = AF_INET6;
        assert(new_connect(3, reinterpret_cast<sockaddr *>(&v4), sizeof(v4)) == -1);
        assert(errno == ECONNREFUSED);
        assert(new_connect(3, reinterpret_cast<sockaddr *>(&v6), sizeof(v6)) == -1);
        assert(errno == ECONNREFUSED && log_calls == 2);
    } else {
        __android_log_print(ANDROID_LOG_INFO, "test", "outer log");
        assert(log_calls == 1);
    }
    alarm(0);
    puts("Native IO logger test passed");
}
