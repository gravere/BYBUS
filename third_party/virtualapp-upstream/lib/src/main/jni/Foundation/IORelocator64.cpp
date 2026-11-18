//
// VirtualApp Native Project
//
#include <unistd.h>
#include <stdlib.h>
#include <sys/ptrace.h>
#include <Substrate/CydiaSubstrate.h>
#include <Jni/VAJni.h>
#include <sys/stat.h>
#include <syscall.h>
#include <Foundation/syscall/BinarySyscallFinder.h>
#include <limits.h>
#include <sys/socket.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <sys/user.h>
#include <sys/time.h>
#include <sys/system_properties.h>
#include <ucontext.h>
#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <link.h>
#include <unwind.h>
#include <atomic>
#include <map>
#include <set>
#include <stdint.h>
#include <string>
#include <utility>

#include <asm/mman.h>
#include <sys/mman.h>
#include <arpa/inet.h>
#include <utils/zMd5.h>
#include <utils/controllerManagerNative.h>
#include <linux/in6.h>
#include <netdb.h>
#include <asm/unistd.h>

#include "IORelocator.h"
#include "RuntimePage.h"
#include "Arm64Entry.h"
#include "IoHookRegistry.h"
#include "LimbusTranslationRuntime.h"
#include "SandboxFs.h"
#include "canonicalize_md.h"
#include "Symbol.h"
#include "Log.h"

#include "transparentED/originalInterface.h"
#include "transparentED/ff_Recognizer.h"

void startIOHook(JNIEnv *env);

bool need_load_env = true;

int g_api_level;

bool execve_process = false;
bool g_limbus_container_process_known = false;
bool g_limbus_container_process = false;
static int g_limbus_network_diag_count = 0;
static int g_limbus_network_io_diag_count = 0;
static pthread_mutex_t g_limbus_localize_io_lock = PTHREAD_MUTEX_INITIALIZER;
static std::atomic<int> g_limbus_localize_fd_count{0};
static std::set<std::string> g_limbus_localize_logged_paths;
static int g_limbus_localize_detail_log_count = 0;
static int g_limbus_localize_total_event_count = 0;
static int g_limbus_localize_last_summary_total = 0;
static long long g_limbus_localize_last_summary_ms = -1;
static const int kLimbusLocalizeDetailLogLimit = 120;
static const int kLimbusLocalizeSummaryEventInterval = 5000;
static const long long kLimbusLocalizeSummaryTimeIntervalMs = 5000;
static int g_limbus_localize_mmap_log_count = 0;
static const int kLimbusLocalizeMmapLogLimit = 1200;

struct LimbusLocalizeFdInfo {
    char original[PATH_MAX];
    char path[PATH_MAX];
    int flags;
    long long total_read;
    int read_ops;
};

static std::map<int, LimbusLocalizeFdInfo> g_limbus_localize_fds;
static timeval g_limbus_localize_io_start_time;
static bool g_limbus_localize_io_start_set = false;

#include "utils/zString.h"
#include "utils/utils.h"
#include "utils/Autolock.h"
#include "transparentED/virtualFileSystem.h"
#include "utils/mylog.h"

std::map<int64_t, MmapFileInfo *> MmapInfoMap;
using namespace xdja;

void startIOHook(int api_level);

char *get_process_name() {
    char *cmdline = (char *) calloc(0x400u, 1u);
    if (cmdline) {
        FILE *file = fopen("/proc/self/cmdline", "r");
        if (file) {
            int count = fread(cmdline, 1u, 0x400u, file);
            if (count) {
                if (cmdline[count - 1] == '\n') {
                    cmdline[count - 1] = '\0';
                }
            }
            fclose(file);
        } else {
            ALOGE("fail open cmdline.");
        }
    }
    return cmdline;
}

bool is_limbus_container_process() {
    if (g_limbus_container_process_known) {
        return g_limbus_container_process;
    }
    char *process_name = get_process_name();
    bool is_limbus_container = process_name
            && (strstr(process_name, "limbuszhcn") != nullptr
            || strstr(process_name, "com.ProjectMoon.LimbusCompany") != nullptr);
    free(process_name);
    return is_limbus_container;
}

static void log_limbus_network_bypass(const char *api, const char *target) {
    if (!is_limbus_container_process()) {
        return;
    }
    int count = __sync_fetch_and_add(&g_limbus_network_diag_count, 1);
    if (count >= 16) {
        return;
    }
    ALOGE("network guard >>> bypass Limbus VirtualApp network strategy api=%s target=%s state=%d white=%d",
          api,
          target != nullptr ? target : "(null)",
          getNetWorkState() ? 1 : 0,
          isWhiteList() ? 1 : 0);
}

static void log_limbus_network_io(const char *api, const char *target, long result, int error) {
    if (!is_limbus_container_process()) {
        return;
    }
    int count = __sync_fetch_and_add(&g_limbus_network_io_diag_count, 1);
    if (count >= 48) {
        return;
    }
    ALOGE("network io >>> Limbus api=%s target=%s result=%ld errno=%d strategy=%d",
          api,
          target != nullptr ? target : "(null)",
          result,
          error,
          getNetWorkState() ? 1 : 0);
}

static void format_limbus_socket_target(const struct sockaddr *address,
                                        char *target,
                                        size_t target_size) {
    if (target == nullptr || target_size == 0) {
        return;
    }
    target[0] = '\0';
    if (address == nullptr) {
        snprintf(target, target_size, "(null)");
        return;
    }
    char ip[INET6_ADDRSTRLEN] = {};
    if (address->sa_family == AF_INET) {
        const auto *ipv4 = reinterpret_cast<const struct sockaddr_in *>(address);
        if (inet_ntop(AF_INET, &ipv4->sin_addr, ip, sizeof(ip)) != nullptr) {
            snprintf(target, target_size, "%s:%u", ip, ntohs(ipv4->sin_port));
            return;
        }
    } else if (address->sa_family == AF_INET6) {
        const auto *ipv6 = reinterpret_cast<const struct sockaddr_in6 *>(address);
        if (inet_ntop(AF_INET6, &ipv6->sin6_addr, ip, sizeof(ip)) != nullptr) {
            snprintf(target, target_size, "[%s]:%u", ip, ntohs(ipv6->sin6_port));
            return;
        }
    }
    snprintf(target, target_size, "family=%d", address->sa_family);
}

static bool limbus_should_log_io_path(const char *pathname) {
    if (!g_limbus_container_process_known || !g_limbus_container_process || pathname == nullptr) {
        return false;
    }
    // 常规版本不记录全部 I/O 路径，避免 Unity 高频文件探测拖慢游戏启动。
    return false;
}

static bool limbus_is_interesting_game_path(const char *pathname) {
    if (!g_limbus_container_process_known || !g_limbus_container_process || pathname == nullptr) {
        return false;
    }
    return strstr(pathname, "Resources_moved") != nullptr
            || strstr(pathname, "Localize") != nullptr
            || strstr(pathname, "AssetBundles") != nullptr
            || strstr(pathname, "Addressable") != nullptr
            || strstr(pathname, "/aa/") != nullptr;
}

static bool limbus_localize_io_diag_enabled() {
    char prop[PROP_VALUE_MAX] = {};
    return __system_property_get("debug.limbus.localize_io", prop) > 0
            && strcmp(prop, "1") == 0;
}

static bool limbus_is_localize_io_path(const char *pathname) {
    if (!g_limbus_container_process_known
            || !g_limbus_container_process
            || pathname == nullptr) {
        return false;
    }
    bool looks_like_localize = strstr(pathname, "Resources_moved/Localize") != nullptr
            || strstr(pathname, "/Localize/") != nullptr
            || strstr(pathname, "/Lang/") != nullptr
            || strstr(pathname, "/translation-cache/") != nullptr;
    return looks_like_localize && limbus_localize_io_diag_enabled();
}

static long long limbus_localize_elapsed_ms_locked() {
    timeval now{};
    gettimeofday(&now, nullptr);
    if (!g_limbus_localize_io_start_set) {
        g_limbus_localize_io_start_time = now;
        g_limbus_localize_io_start_set = true;
    }
    long long sec = static_cast<long long>(now.tv_sec) -
            static_cast<long long>(g_limbus_localize_io_start_time.tv_sec);
    long long usec = static_cast<long long>(now.tv_usec) -
            static_cast<long long>(g_limbus_localize_io_start_time.tv_usec);
    return sec * 1000LL + usec / 1000LL;
}

static long limbus_gettid() {
    return static_cast<long>(syscall(__NR_gettid));
}

static const char *limbus_basename(const char *path) {
    if (path == nullptr) {
        return "(null)";
    }
    const char *slash = strrchr(path, '/');
    return slash != nullptr ? slash + 1 : path;
}

static void limbus_format_caller(char *dest, size_t size, void *caller) {
    if (dest == nullptr || size == 0) {
        return;
    }
    if (caller == nullptr) {
        snprintf(dest, size, "(null)");
        return;
    }
    Dl_info info{};
    if (dladdr(caller, &info) != 0 && info.dli_fname != nullptr && info.dli_fbase != nullptr) {
        uintptr_t offset = reinterpret_cast<uintptr_t>(caller)
                - reinterpret_cast<uintptr_t>(info.dli_fbase);
        snprintf(dest, size, "%s+0x%llx",
                 limbus_basename(info.dli_fname),
                 static_cast<unsigned long long>(offset));
        return;
    }
    snprintf(dest, size, "%p", caller);
}

static void limbus_copy_path(char *dest, size_t size, const char *source) {
    if (dest == nullptr || size == 0) {
        return;
    }
    if (source == nullptr) {
        snprintf(dest, size, "(null)");
        return;
    }
    snprintf(dest, size, "%s", source);
}

static bool limbus_should_log_localize_detail_locked(const char *op,
                                                     const char *original,
                                                     const char *relocated,
                                                     const char *caller) {
    g_limbus_localize_total_event_count++;
    if (g_limbus_localize_detail_log_count >= kLimbusLocalizeDetailLogLimit) {
        return false;
    }
    const char *path = relocated != nullptr ? relocated : original;
    if (path == nullptr) {
        path = "(null)";
    }
    std::string key = op != nullptr ? op : "(null)";
    key.push_back('\n');
    key.append(path);
    key.push_back('\n');
    key.append(caller != nullptr ? caller : "(null)");
    if (!g_limbus_localize_logged_paths.insert(key).second) {
        return false;
    }
    g_limbus_localize_detail_log_count++;
    return true;
}

static bool limbus_should_log_localize_summary_locked(long long elapsed) {
    if (g_limbus_localize_detail_log_count < kLimbusLocalizeDetailLogLimit) {
        return false;
    }
    if (g_limbus_localize_last_summary_ms < 0) {
        g_limbus_localize_last_summary_ms = elapsed;
        g_limbus_localize_last_summary_total = g_limbus_localize_total_event_count;
        return true;
    }
    if (g_limbus_localize_total_event_count - g_limbus_localize_last_summary_total
            >= kLimbusLocalizeSummaryEventInterval
            || elapsed - g_limbus_localize_last_summary_ms
            >= kLimbusLocalizeSummaryTimeIntervalMs) {
        g_limbus_localize_last_summary_ms = elapsed;
        g_limbus_localize_last_summary_total = g_limbus_localize_total_event_count;
        return true;
    }
    return false;
}

static void limbus_record_localize_open(const char *op,
                                        const char *original,
                                        const char *relocated,
                                        int fd,
                                        int flags,
                                        void *caller) {
    if (fd < 0 || (!limbus_is_localize_io_path(original) && !limbus_is_localize_io_path(relocated))) {
        return;
    }
    char caller_text[160];
    limbus_format_caller(caller_text, sizeof(caller_text), caller);
    pthread_mutex_lock(&g_limbus_localize_io_lock);
    auto existing = g_limbus_localize_fds.find(fd);
    if (existing == g_limbus_localize_fds.end()) {
        g_limbus_localize_fd_count.fetch_add(1, std::memory_order_relaxed);
    }
    LimbusLocalizeFdInfo &info = g_limbus_localize_fds[fd];
    limbus_copy_path(info.original, sizeof(info.original), original);
    limbus_copy_path(info.path, sizeof(info.path), relocated);
    info.flags = flags;
    info.total_read = 0;
    info.read_ops = 0;
    long long elapsed = limbus_localize_elapsed_ms_locked();
    bool should_log = limbus_should_log_localize_detail_locked(op, original, relocated, caller_text);
    bool should_summary = !should_log && limbus_should_log_localize_summary_locked(elapsed);
    int detail_count = g_limbus_localize_detail_log_count;
    int total_count = g_limbus_localize_total_event_count;
    pthread_mutex_unlock(&g_limbus_localize_io_lock);
    if (!should_log) {
        if (should_summary) {
            ALOGI("Limbus Localize IO summary t=%lld total=%d detail=%d last_op=%s caller=%s path=%s",
                  elapsed,
                  total_count,
                  detail_count,
                  op != nullptr ? op : "(null)",
                  caller_text,
                  relocated != nullptr ? relocated : (original != nullptr ? original : "(null)"));
        }
        return;
    }
    ALOGI("Limbus Localize IO open t=%lld tid=%ld op=%s fd=%d flags=0x%x caller=%s original=%s path=%s",
          elapsed,
          limbus_gettid(),
          op != nullptr ? op : "(null)",
          fd,
          flags,
          caller_text,
          original != nullptr ? original : "(null)",
          relocated != nullptr ? relocated : "(null)");
    if (detail_count == kLimbusLocalizeDetailLogLimit) {
        ALOGI("Limbus Localize IO detail limit reached total=%d detail=%d", total_count, detail_count);
    }
}

static void limbus_record_localize_meta(const char *op,
                                        const char *original,
                                        const char *relocated) {
    if (!limbus_is_localize_io_path(original) && !limbus_is_localize_io_path(relocated)) {
        return;
    }
    pthread_mutex_lock(&g_limbus_localize_io_lock);
    long long elapsed = limbus_localize_elapsed_ms_locked();
    bool should_log = limbus_should_log_localize_detail_locked(op, original, relocated, "meta");
    bool should_summary = !should_log && limbus_should_log_localize_summary_locked(elapsed);
    int detail_count = g_limbus_localize_detail_log_count;
    int total_count = g_limbus_localize_total_event_count;
    pthread_mutex_unlock(&g_limbus_localize_io_lock);
    if (!should_log) {
        if (should_summary) {
            ALOGI("Limbus Localize IO summary t=%lld total=%d detail=%d last_op=%s caller=meta path=%s",
                  elapsed,
                  total_count,
                  detail_count,
                  op != nullptr ? op : "(null)",
                  relocated != nullptr ? relocated : (original != nullptr ? original : "(null)"));
        }
        return;
    }
    ALOGI("Limbus Localize IO meta t=%lld tid=%ld op=%s original=%s path=%s",
          elapsed,
          limbus_gettid(),
          op != nullptr ? op : "(null)",
          original != nullptr ? original : "(null)",
          relocated != nullptr ? relocated : "(null)");
    if (detail_count == kLimbusLocalizeDetailLogLimit) {
        ALOGI("Limbus Localize IO detail limit reached total=%d detail=%d", total_count, detail_count);
    }
}

static void limbus_record_localize_read(const char *op,
                                        int fd,
                                        size_t requested,
                                        ssize_t ret,
                                        off64_t offset,
                                        bool has_offset) {
    if (g_limbus_localize_fd_count.load(std::memory_order_relaxed) <= 0) {
        return;
    }
    char original[PATH_MAX];
    char path[PATH_MAX];
    int flags = 0;
    long long total = 0;
    int ops = 0;
    long long elapsed = 0;
    bool found = false;
    pthread_mutex_lock(&g_limbus_localize_io_lock);
    auto it = g_limbus_localize_fds.find(fd);
    if (it != g_limbus_localize_fds.end()) {
        found = true;
        if (ret > 0) {
            it->second.total_read += ret;
        }
        it->second.read_ops += 1;
        limbus_copy_path(original, sizeof(original), it->second.original);
        limbus_copy_path(path, sizeof(path), it->second.path);
        flags = it->second.flags;
        total = it->second.total_read;
        ops = it->second.read_ops;
        elapsed = limbus_localize_elapsed_ms_locked();
    }
    pthread_mutex_unlock(&g_limbus_localize_io_lock);
    if (!found) {
        return;
    }
    if (has_offset) {
        ALOGI("Limbus Localize IO read t=%lld tid=%ld op=%s fd=%d requested=%zu ret=%zd offset=%lld total=%lld ops=%d flags=0x%x original=%s path=%s",
              elapsed,
              limbus_gettid(),
              op != nullptr ? op : "(null)",
              fd,
              requested,
              ret,
              static_cast<long long>(offset),
              total,
              ops,
              flags,
              original,
              path);
    } else {
        ALOGI("Limbus Localize IO read t=%lld tid=%ld op=%s fd=%d requested=%zu ret=%zd total=%lld ops=%d flags=0x%x original=%s path=%s",
              elapsed,
              limbus_gettid(),
              op != nullptr ? op : "(null)",
              fd,
              requested,
              ret,
              total,
              ops,
              flags,
              original,
              path);
    }
}

static void limbus_record_localize_stream_read(const char *op,
                                               FILE *stream,
                                               size_t requested,
                                               ssize_t ret) {
    if (stream == nullptr) {
        return;
    }
    int fd = fileno(stream);
    if (fd < 0) {
        return;
    }
    limbus_record_localize_read(op, fd, requested, ret, 0, false);
}

static void limbus_record_localize_close(int fd) {
    if (g_limbus_localize_fd_count.load(std::memory_order_relaxed) <= 0) {
        return;
    }
    char original[PATH_MAX];
    char path[PATH_MAX];
    int flags = 0;
    long long total = 0;
    int ops = 0;
    long long elapsed = 0;
    bool found = false;
    pthread_mutex_lock(&g_limbus_localize_io_lock);
    auto it = g_limbus_localize_fds.find(fd);
    if (it != g_limbus_localize_fds.end()) {
        found = true;
        limbus_copy_path(original, sizeof(original), it->second.original);
        limbus_copy_path(path, sizeof(path), it->second.path);
        flags = it->second.flags;
        total = it->second.total_read;
        ops = it->second.read_ops;
        elapsed = limbus_localize_elapsed_ms_locked();
        g_limbus_localize_fds.erase(it);
        g_limbus_localize_fd_count.fetch_sub(1, std::memory_order_relaxed);
    }
    pthread_mutex_unlock(&g_limbus_localize_io_lock);
    if (!found) {
        return;
    }
    (void) elapsed;
    (void) total;
    (void) ops;
    (void) flags;
    (void) original;
    (void) path;
}

static bool limbus_find_localize_fd_info(int fd,
                                         LimbusLocalizeFdInfo *out,
                                         long long *elapsed_out) {
    if (g_limbus_localize_fd_count.load(std::memory_order_relaxed) <= 0) {
        return false;
    }
    bool found = false;
    pthread_mutex_lock(&g_limbus_localize_io_lock);
    auto it = g_limbus_localize_fds.find(fd);
    if (it != g_limbus_localize_fds.end()) {
        found = true;
        if (out != nullptr) {
            *out = it->second;
        }
        if (elapsed_out != nullptr) {
            *elapsed_out = limbus_localize_elapsed_ms_locked();
        }
    }
    pthread_mutex_unlock(&g_limbus_localize_io_lock);
    return found;
}

static bool limbus_should_log_localize_mmap_detail() {
    if (!limbus_localize_io_diag_enabled()) {
        return false;
    }
    int count = __sync_fetch_and_add(&g_limbus_localize_mmap_log_count, 1);
    if (count == kLimbusLocalizeMmapLogLimit) {
        ALOGI("Limbus Localize FD mmap diagnostic detail limit reached limit=%d",
              kLimbusLocalizeMmapLogLimit);
    }
    return count < kLimbusLocalizeMmapLogLimit;
}

static void limbus_record_localize_fd_meta(const char *op,
                                           int fd,
                                           long long arg1,
                                           long long arg2,
                                           long long ret) {
    LimbusLocalizeFdInfo info{};
    long long elapsed = 0;
    if (!limbus_find_localize_fd_info(fd, &info, &elapsed)
            || !limbus_should_log_localize_mmap_detail()) {
        return;
    }
    ALOGI("Limbus Localize FD %s t=%lld tid=%ld fd=%d arg1=%lld arg2=%lld ret=%lld total=%lld ops=%d flags=0x%x original=%s path=%s",
          op != nullptr ? op : "(null)",
          elapsed,
          limbus_gettid(),
          fd,
          arg1,
          arg2,
          ret,
          info.total_read,
          info.read_ops,
          info.flags,
          info.original,
          info.path);
}

static void limbus_record_localize_mmap(const char *op,
                                        int fd,
                                        void *ret,
                                        size_t length,
                                        int prot,
                                        int flags,
                                        size_t pgoffset,
                                        bool virtual_file) {
    LimbusLocalizeFdInfo info{};
    long long elapsed = 0;
    if (!limbus_find_localize_fd_info(fd, &info, &elapsed)
            || !limbus_should_log_localize_mmap_detail()) {
        return;
    }
    ALOGI("Limbus Localize FD %s t=%lld tid=%ld fd=%d ret=%p length=%zu prot=0x%x flags=0x%x pgoffset=%zu virtual=%d total=%lld ops=%d original=%s path=%s",
          op != nullptr ? op : "(null)",
          elapsed,
          limbus_gettid(),
          fd,
          ret,
          length,
          prot,
          flags,
          pgoffset,
          virtual_file ? 1 : 0,
          info.total_read,
          info.read_ops,
          info.original,
          info.path);
}

static void limbus_log_io_path(const char *op, const char *pathname, const char *relocated_path) {
    limbus_record_localize_meta(op, pathname, relocated_path);
    if (!limbus_should_log_io_path(pathname)
            && !limbus_should_log_io_path(relocated_path)
            && !limbus_is_interesting_game_path(pathname)
            && !limbus_is_interesting_game_path(relocated_path)) {
        return;
    }
    // 只保留少量与汉化资源有关的诊断，避免高频文件访问刷满日志。
    static int resource_path_logs = 0;
    if (__atomic_fetch_add(&resource_path_logs, 1, __ATOMIC_RELAXED) > 160) {
        return;
    }
    ALOGI("Limbus IO %s path=%s relocated=%s",
          op,
          pathname != nullptr ? pathname : "(null)",
          relocated_path != nullptr ? relocated_path : "(null)");
}

static void limbus_log_io_failure(const char *op, const char *pathname, const char *relocated_path, int ret, int saved_errno) {
    static int failure_logs = 0;
    if (failure_logs++ > 80) {
        return;
    }
    if (!limbus_is_interesting_game_path(pathname)
            && !limbus_is_interesting_game_path(relocated_path)) {
        return;
    }
    ALOGE("Limbus IO failed %s ret=%d errno=%d path=%s relocated=%s",
          op,
          ret,
          saved_errno,
          pathname != nullptr ? pathname : "(null)",
          relocated_path != nullptr ? relocated_path : "(null)");
}

static const char *relocate_path_with_dirfd(int dirfd, const char *pathname, char *const buffer, const size_t size) {
    if (pathname == nullptr || pathname[0] == '/' || dirfd == AT_FDCWD) {
        return relocate_path(pathname, buffer, size);
    }
    char fd_link[64];
    snprintf(fd_link, sizeof(fd_link), "/proc/self/fd/%d", dirfd);
    char dir_path[PATH_MAX];
    long dir_len = syscall(__NR_readlinkat, AT_FDCWD, fd_link, dir_path, sizeof(dir_path) - 1);
    if (dir_len <= 0) {
        return relocate_path(pathname, buffer, size);
    }
    dir_path[dir_len] = '\0';
    char full_path[PATH_MAX];
    int written = snprintf(full_path, sizeof(full_path), "%s/%s", dir_path, pathname);
    if (written <= 0 || static_cast<size_t>(written) >= sizeof(full_path)) {
        return relocate_path(pathname, buffer, size);
    }
    const char *relocated_full = relocate_path(full_path, buffer, size);
    if (relocated_full != nullptr && strcmp(relocated_full, full_path) != 0) {
        if (limbus_is_interesting_game_path(full_path) || limbus_is_interesting_game_path(relocated_full)) {
            ALOGI("Limbus IO dirfd path fd=%d path=%s full=%s relocated=%s",
                  dirfd,
                  pathname,
                  full_path,
                  relocated_full);
        }
        return relocated_full;
    }
    return relocate_path(pathname, buffer, size);
}

static bool limbus_is_translation_cache_path(const char *path) {
    return path != nullptr
           && strstr(path, "io.github.gravere.bybus") != nullptr
           && strstr(path, "/translation-cache/") != nullptr;
}

static const char *limbus_virtual_storage_path(const char *path, char *const buffer, const size_t size) {
    if (path == nullptr || buffer == nullptr || size == 0) {
        return path;
    }
    struct PrefixRule {
        const char *visible;
        const char *actual;
    };
    static const PrefixRule rules[] = {
            {"/storage/emulated/0/", "/data/user/0/io.github.gravere.bybus/virtual/storage/emulated/0/"},
            {"/sdcard/", "/data/user/0/io.github.gravere.bybus/virtual/storage/emulated/0/"},
            {"/mnt/sdcard/", "/data/user/0/io.github.gravere.bybus/virtual/storage/emulated/0/"},
    };
    for (const PrefixRule &rule : rules) {
        size_t prefix_len = strlen(rule.visible);
        if (strncmp(path, rule.visible, prefix_len) != 0) {
            continue;
        }
        int written = snprintf(buffer, size, "%s%s", rule.actual, path + prefix_len);
        if (written <= 0 || static_cast<size_t>(written) >= size) {
            return path;
        }
        return buffer;
    }
    return path;
}

static const char *relocate_metadata_path_with_dirfd(int dirfd, const char *pathname, char *const buffer, const size_t size) {
    const char *relocated_path = relocate_path_with_dirfd(dirfd, pathname, buffer, size);
    if (!limbus_is_translation_cache_path(relocated_path)) {
        return relocated_path;
    }
    char translated_path[PATH_MAX];
    translated_path[0] = '\0';
    if (relocated_path != nullptr) {
        snprintf(translated_path, sizeof(translated_path), "%s", relocated_path);
    }
    char full_path[PATH_MAX];
    const char *metadata_path = pathname;
    if (pathname != nullptr && pathname[0] != '/' && dirfd != AT_FDCWD) {
        char fd_link[64];
        snprintf(fd_link, sizeof(fd_link), "/proc/self/fd/%d", dirfd);
        char dir_path[PATH_MAX];
        long dir_len = syscall(__NR_readlinkat, AT_FDCWD, fd_link, dir_path, sizeof(dir_path) - 1);
        if (dir_len > 0) {
            dir_path[dir_len] = '\0';
            int written = snprintf(full_path, sizeof(full_path), "%s/%s", dir_path, pathname);
            if (written > 0 && static_cast<size_t>(written) < sizeof(full_path)) {
                metadata_path = full_path;
            }
        }
    }
    const char *actual_metadata_path = limbus_virtual_storage_path(metadata_path, buffer, size);
    if (limbus_is_interesting_game_path(metadata_path) || limbus_is_interesting_game_path(actual_metadata_path)) {
        ALOGI("Limbus IO metadata bypass translation path=%s translated=%s metadata=%s",
              metadata_path != nullptr ? metadata_path : "(null)",
              translated_path[0] != '\0' ? translated_path : "(null)",
              actual_metadata_path != nullptr ? actual_metadata_path : "(null)");
    }
    return actual_metadata_path;
}

void init_limbus_container_process_flag() {
    char *process_name = get_process_name();
    g_limbus_container_process = process_name
            && (strstr(process_name, "limbuszhcn") != nullptr
            || strstr(process_name, "com.ProjectMoon.LimbusCompany") != nullptr);
    g_limbus_container_process_known = true;
    ALOGI("Limbus container process flag : %d name : %s",
          g_limbus_container_process ? 1 : 0,
          process_name != nullptr ? process_name : "(null)");
    free(process_name);
}

void IOUniformer::init_env_before_all() {
    if (!need_load_env) {
        return;
    }
    need_load_env = false;
    char *ld_preload = getenv("LD_PRELOAD");
    if (!ld_preload || !strstr(ld_preload, CORE_SO_NAME)) {
        return;
    }
    execve_process = true;
    char *process_name = get_process_name();
    ALOGI("Start init env : %s", process_name);
    free(process_name);
    char src_key[KEY_MAX];
    char dst_key[KEY_MAX];
    int i = 0;
    while (true) {
        memset(src_key, 0, sizeof(src_key));
        memset(dst_key, 0, sizeof(dst_key));
        sprintf(src_key, "V_REPLACE_ITEM_SRC_%d", i);
        sprintf(dst_key, "V_REPLACE_ITEM_DST_%d", i);
        char *src_value = getenv(src_key);
        if (!src_value) {
            break;
        }
        char *dst_value = getenv(dst_key);
        add_replace_item(src_value, dst_value);
        i++;
    }
    i = 0;
    while (true) {
        memset(src_key, 0, sizeof(src_key));
        sprintf(src_key, "V_KEEP_ITEM_%d", i);
        char *keep_value = getenv(src_key);
        if (!keep_value) {
            break;
        }
        add_keep_item(keep_value);
        i++;
    }
    i = 0;
    while (true) {
        memset(src_key, 0, sizeof(src_key));
        sprintf(src_key, "V_FORBID_ITEM_%d", i);
        char *forbid_value = getenv(src_key);
        if (!forbid_value) {
            break;
        }
        add_forbidden_item(forbid_value);
        i++;
    }
    char *api_level_char = getenv("V_API_LEVEL");
    if (api_level_char != NULL) {
        int api_level = atoi(api_level_char);
        startIOHook(api_level);
    }
}

static inline void
hook_function(void *handle, const char *symbol, void *new_func, void **old_func) {
    void *addr = dlsym(handle, symbol);
    if (addr == NULL) {
        ALOGE("Not found symbol : %s", symbol);
        return;
    }
    static LimbusIoHookRegistry registry;
    static pthread_mutex_t install_lock = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&install_lock);
    const bool installed = registry.install(addr, symbol, new_func, old_func,
            [](void *address, void *replacement, void **original) {
                MSHookFunction(address, replacement, original);
            });
    pthread_mutex_unlock(&install_lock);
    if (!installed) ALOGE("IO hook rejected conflicting or unavailable symbol: %s", symbol);
}

void onSoLoaded(const char *name, void *handle);

void IOUniformer::relocate(const char *orig_path, const char *new_path) {
    add_replace_item(orig_path, new_path);
}

const char *IOUniformer::query(const char *orig_path, char *const buffer, const size_t size) {
    return relocate_path(orig_path, buffer, size);
}

void IOUniformer::whitelist(const char *_path) {
    add_keep_item(_path);
}

void IOUniformer::forbid(const char *_path) {
    add_forbidden_item(_path);
}

void IOUniformer::readOnly(const char *_path) {
    add_readonly_item(_path);
}

const char *IOUniformer::reverse(const char *_path, char *const buffer, const size_t size) {
    return reverse_relocate_path(_path, buffer, size);
}


__BEGIN_DECLS

// int faccessat(int dirfd, const char *pathname, int mode, int flags);
HOOK_DEF(int, faccessat, int dirfd, const char *pathname, int mode, int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_metadata_path_with_dirfd(dirfd, pathname, temp, sizeof(temp));
    limbus_log_io_path("faccessat", pathname, relocated_path);
    if (relocated_path && !(mode & W_OK && isReadOnly(relocated_path))) {
        int ret = static_cast<int>(syscall(__NR_faccessat, dirfd, relocated_path, mode, flags));
        if (ret < 0) {
            limbus_log_io_failure("faccessat", pathname, relocated_path, ret, errno);
        }
        return ret;
    }
    errno = EACCES;
    limbus_log_io_failure("faccessat", pathname, relocated_path, -1, errno);
    return -1;
}

// int fchmodat(int dirfd, const char *pathname, mode_t mode, int flags);
HOOK_DEF(int, fchmodat, int dirfd, const char *pathname, mode_t mode, int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_fchmodat, dirfd, relocated_path, mode, flags));
    }
    errno = EACCES;
    return -1;
}

// int fstatat64(int dirfd, const char *pathname, struct stat *buf, int flags);
HOOK_DEF(int, fstatat64, int dirfd, const char *pathname, struct stat *buf, int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_metadata_path_with_dirfd(dirfd, pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        int ret = static_cast<int>(syscall(__NR_newfstatat, dirfd, relocated_path, buf, flags));
        if (is_TED_Enable()) {
            int fd = originalInterface::original_openat(AT_FDCWD, relocated_path, O_RDONLY, 0);

            if (fd > 0) {
                if (EncryptFile::isEncryptFile(fd)) {
                    EncryptFile ef(relocated_path);
                    if (ef.create(fd, ENCRYPT_READ)) {
                        ef.fstat(fd, buf);
                    }
                }
                originalInterface::original_close(fd);
            }
        }
        return ret;
    }
    errno = EACCES;
    return -1;
}

// int kill(pid_t pid, int sig);
HOOK_DEF(DIR *, opendir, const char *pathname) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (relocated_path != nullptr) {
        return orig_opendir(relocated_path);
    }
    errno = EACCES;
    return nullptr;
}

HOOK_DEF(int, open, const char *pathname, int flags, int mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (relocated_path != NULL) {
        int ret = orig_open(relocated_path, flags, mode);
        limbus_record_localize_open("open", pathname, relocated_path, ret, flags, __builtin_return_address(0));
        if (ret < 0) {
            limbus_log_io_failure("open", pathname, relocated_path, ret, errno);
        }
        return ret;
    }
    errno = EACCES;
    limbus_log_io_failure("open", pathname, relocated_path, -1, errno);
    return -1;
}

HOOK_DEF(int, open64, const char *pathname, int flags, int mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (relocated_path != NULL) {
        int ret = orig_open64(relocated_path, flags, mode);
        limbus_record_localize_open("open64", pathname, relocated_path, ret, flags, __builtin_return_address(0));
        if (ret < 0) {
            limbus_log_io_failure("open64", pathname, relocated_path, ret, errno);
        }
        return ret;
    }
    errno = EACCES;
    limbus_log_io_failure("open64", pathname, relocated_path, -1, errno);
    return -1;
}

HOOK_DEF(int, __open_2, const char *pathname, int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (relocated_path != NULL) {
        int ret = orig___open_2(relocated_path, flags);
        limbus_record_localize_open("__open_2", pathname, relocated_path, ret, flags, __builtin_return_address(0));
        return ret;
    }
    errno = EACCES;
    return -1;
}

HOOK_DEF(int, openat64, int fd, const char *pathname, int flags, int mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path_with_dirfd(fd, pathname, temp, sizeof(temp));
    limbus_log_io_path("openat64", pathname, relocated_path);
    if (relocated_path == NULL) {
        errno = EACCES;
        limbus_log_io_failure("openat64", pathname, relocated_path, -1, errno);
        return -1;
    }
    int ret = static_cast<int>(syscall(__NR_openat, fd, relocated_path, flags, mode));
    limbus_record_localize_open("openat64", pathname, relocated_path, ret, flags, __builtin_return_address(0));
    if (ret < 0) {
        limbus_log_io_failure("openat64", pathname, relocated_path, ret, errno);
    }
    return ret;
}

HOOK_DEF(FILE *, fopen, const char *pathname, const char *mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    limbus_log_io_path("fopen", pathname, relocated_path);
    if (relocated_path != NULL) {
        FILE *ret = orig_fopen(relocated_path, mode);
        if (ret != NULL) {
            limbus_record_localize_open("fopen", pathname, relocated_path, fileno(ret), O_RDONLY, __builtin_return_address(0));
        }
        if (ret == NULL) {
            limbus_log_io_failure("fopen", pathname, relocated_path, -1, errno);
        }
        return ret;
    }
    errno = EACCES;
    limbus_log_io_failure("fopen", pathname, relocated_path, -1, errno);
    return NULL;
}

HOOK_DEF(FILE *, fopen64, const char *pathname, const char *mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    limbus_log_io_path("fopen64", pathname, relocated_path);
    if (relocated_path != NULL) {
        FILE *ret = orig_fopen64(relocated_path, mode);
        if (ret != NULL) {
            limbus_record_localize_open("fopen64", pathname, relocated_path, fileno(ret), O_RDONLY, __builtin_return_address(0));
        }
        if (ret == NULL) {
            limbus_log_io_failure("fopen64", pathname, relocated_path, -1, errno);
        }
        return ret;
    }
    errno = EACCES;
    limbus_log_io_failure("fopen64", pathname, relocated_path, -1, errno);
    return NULL;
}

// int __statfs64(const char *path, size_t size, struct statfs *stat);
HOOK_DEF(int, __statfs64, const char *pathname, size_t size, struct statfs *stat) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    limbus_log_io_path("__statfs64", pathname, relocated_path);
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_statfs, relocated_path, size, stat));
    }
    errno = EACCES;
    return -1;
}

// int lstat(const char *path, struct stat *buf);
HOOK_DEF(int, lstat, const char *pathname, struct stat *buf) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_metadata_path_with_dirfd(AT_FDCWD, pathname, temp, sizeof(temp));
    limbus_log_io_path("lstat", pathname, relocated_path);
    if (__predict_true(relocated_path)) {
        int ret = orig_lstat(relocated_path, buf);

        if (is_TED_Enable()) {
            int fd = originalInterface::original_openat(AT_FDCWD, relocated_path, O_RDONLY, 0);

            if (fd > 0) {
                if (EncryptFile::isEncryptFile(fd)) {
                    EncryptFile ef(relocated_path);
                    if (ef.create(fd, ENCRYPT_READ)) {
                        ef.fstat(fd, buf);
                    }
                }
                originalInterface::original_close(fd);
            }
        }

        return ret;
    }
    errno = EACCES;
    return -1;
}

// int stat(const char *path, struct stat *buf);
HOOK_DEF(int, stat, const char *pathname, struct stat *buf) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_metadata_path_with_dirfd(AT_FDCWD, pathname, temp, sizeof(temp));
    limbus_log_io_path("stat", pathname, relocated_path);
    if (__predict_true(relocated_path)) {
        int ret = orig_stat(relocated_path, buf);
        if (ret < 0) {
            limbus_log_io_failure("stat", pathname, relocated_path, ret, errno);
        }
        if (isReadOnly(relocated_path)) {
            buf->st_mode &= ~S_IWGRP;
        }

        if (is_TED_Enable()) {
            int fd = originalInterface::original_openat(AT_FDCWD, relocated_path, O_RDONLY, 0);

            if (fd > 0) {
                if (EncryptFile::isEncryptFile(fd)) {
                    EncryptFile ef(relocated_path);
                    if (ef.create(fd, ENCRYPT_READ)) {
                        ef.fstat(fd, buf);
                    }
                }
                originalInterface::original_close(fd);
            }
        }
        return ret;
    }
    errno = EACCES;
    limbus_log_io_failure("stat", pathname, relocated_path, -1, errno);
    return -1;
}

// int fchmod(const char *pathname, mode_t mode);
HOOK_DEF(int, fchmod, const char *pathname, mode_t mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_fchmod, relocated_path, mode));
    }
    errno = EACCES;
    return -1;
}


// int fstatat(int dirfd, const char *pathname, struct stat *buf, int flags);
HOOK_DEF(int, fstatat, int dirfd, const char *pathname, struct stat *buf, int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_metadata_path_with_dirfd(dirfd, pathname, temp, sizeof(temp));
    limbus_log_io_path("fstatat", pathname, relocated_path);
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_newfstatat, dirfd, relocated_path, buf, flags));
    }
    errno = EACCES;
    return -1;
}

HOOK_DEF(int, fstat, int fd, struct stat *buf)
{
    int ret = -1;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if(virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("fstat fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        /*path.format("%s", vfd->_vf->getPath());*/
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vfstat(vfd.get(), buf);
            flag = true;
        }
    }
    if(!flag) {
        ret = orig_fstat(fd, buf);
    }
    limbus_record_localize_fd_meta("fstat", fd,
                                   buf != nullptr ? static_cast<long long>(buf->st_size) : -1,
                                   buf != nullptr ? static_cast<long long>(buf->st_mode) : -1,
                                   ret);
    return ret;
}

// int mknodat(int dirfd, const char *pathname, mode_t mode, dev_t dev);
HOOK_DEF(int, mknodat, int dirfd, const char *pathname, mode_t mode, dev_t dev) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_mknodat, dirfd, relocated_path, mode, dev));
    }
    errno = EACCES;
    return -1;
}

// int utimensat(int dirfd, const char *pathname, const struct timespec times[2], int flags);
HOOK_DEF(int, utimensat, int dirfd, const char *pathname, const struct timespec times[2],
         int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_utimensat, dirfd, relocated_path, times, flags));
    }
    errno = EACCES;
    return -1;
}

// int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags);
HOOK_DEF(int, fchownat, int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_fchownat, dirfd, relocated_path, owner, group, flags));
    }
    errno = EACCES;
    return -1;
}

// int chroot(const char *pathname);
HOOK_DEF(int, chroot, const char *pathname) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_chroot, relocated_path));
    }
    errno = EACCES;
    return -1;
}

// int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath);
HOOK_DEF(int, renameat, int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    char temp_old[PATH_MAX], temp_new[PATH_MAX];
    const char *relocated_path_old = relocate_path(oldpath, temp_old, sizeof(temp_old));
    const char *relocated_path_new = relocate_path(newpath, temp_new, sizeof(temp_new));
    if (relocated_path_old && relocated_path_new) {
        xdja::zs::sp <virtualFile> *vf2 = virtualFileManager::getVFM().queryVF(
                (char *) relocated_path_old);
        if (vf2 != nullptr) {
            slog(" *** need to force translate virtual File [%s] *** ", vf2->get()->getPath());

            xdja::zs::sp <virtualFile> pvf2(vf2->get());
            pvf2->lockWhole();
            pvf2->forceTranslate();
            pvf2->unlockWhole();
            pvf2->delRef();
        }

        {
            /**？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？**/
            virtualFileManager::getVFM().deleted((char *) relocated_path_old);
        }

        int ret = static_cast<int>(syscall(__NR_renameat, olddirfd, relocated_path_old, newdirfd,
                                           relocated_path_new));

        xdja::zs::sp <virtualFile> *vf3 = virtualFileManager::getVFM().queryVF(
                (char *) relocated_path_new);
        if (vf3 != nullptr) {
            xdja::zs::sp <virtualFile> pvf3(vf3->get());
            slog(" *** update virtual file [%s] *** ", pvf3->getPath());
            pvf3->lockWhole();
            virtualFileManager::getVFM().updateVF(*pvf3.get());
            pvf3->unlockWhole();
            pvf3->delRef();
        }

        /*zString op("renameat to %s ret %d err %s", redirect_path_new, ret, getErr);
        doFileTrace(redirect_path_old, op.toString());*/

        return ret;
    }
    errno = EACCES;
    return -1;
}

// int renameat2(int olddirfd, const char *oldpath, int newdirfd,
//               const char *newpath, unsigned int flags);
HOOK_DEF(int, renameat2, int olddirfd, const char *oldpath, int newdirfd,
         const char *newpath, unsigned int flags) {
    char temp_old[PATH_MAX], temp_new[PATH_MAX];
    // renameat2 允许相对路径，因此源、目标必须分别结合自己的 dirfd 解析后再重定向。
    const char *relocated_path_old = relocate_path_with_dirfd(
            olddirfd, oldpath, temp_old, sizeof(temp_old));
    const char *relocated_path_new = relocate_path_with_dirfd(
            newdirfd, newpath, temp_new, sizeof(temp_new));
    if (relocated_path_old == nullptr || relocated_path_new == nullptr) {
        errno = EACCES;
        return -1;
    }

    // 加密虚拟文件在重命名前必须先完成落盘，沿用旧 renameat hook 的缓存一致性处理。
    xdja::zs::sp<virtualFile> *source_virtual_file = virtualFileManager::getVFM().queryVF(
            const_cast<char *>(relocated_path_old));
    if (source_virtual_file != nullptr) {
        xdja::zs::sp<virtualFile> source(source_virtual_file->get());
        source->lockWhole();
        source->forceTranslate();
        source->unlockWhole();
        source->delRef();
    }
    virtualFileManager::getVFM().deleted(const_cast<char *>(relocated_path_old));

    int ret = static_cast<int>(syscall(__NR_renameat2,
                                       olddirfd,
                                       relocated_path_old,
                                       newdirfd,
                                       relocated_path_new,
                                       flags));
    int saved_errno = ret < 0 ? errno : 0;

    xdja::zs::sp<virtualFile> *target_virtual_file = virtualFileManager::getVFM().queryVF(
            const_cast<char *>(relocated_path_new));
    if (target_virtual_file != nullptr) {
        xdja::zs::sp<virtualFile> target(target_virtual_file->get());
        target->lockWhole();
        virtualFileManager::getVFM().updateVF(*target.get());
        target->unlockWhole();
        target->delRef();
    }

    // UnityCache 是本问题的关键提交点，保留低频结果日志便于真机确认临时文件已成功归档。
    if ((oldpath != nullptr && strstr(oldpath, "/UnityCache/") != nullptr)
            || (newpath != nullptr && strstr(newpath, "/UnityCache/") != nullptr)) {
        ALOGI("Limbus UnityCache renameat2 >>> ret=%d errno=%d flags=0x%x old=%s relocated_old=%s new=%s relocated_new=%s",
              ret,
              saved_errno,
              flags,
              oldpath != nullptr ? oldpath : "(null)",
              relocated_path_old,
              newpath != nullptr ? newpath : "(null)",
              relocated_path_new);
    }
    if (ret < 0) {
        errno = saved_errno;
    }
    return ret;
}

// int statfs64(const char *__path, struct statfs64 *__buf) __INTRODUCED_IN(21);
HOOK_DEF(int, statfs64, const char *filename, struct statfs64 *buf) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(filename, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_statfs, relocated_path, buf));
    }
    errno = EACCES;
    return -1;
}

// int unlinkat(int dirfd, const char *pathname, int flags);
HOOK_DEF(int, unlinkat, int dirfd, const char *pathname, int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (relocated_path && !isReadOnly(relocated_path)) {
        int ret = static_cast<int>(syscall(__NR_unlinkat, dirfd, relocated_path, flags));

        if (ret == 0) {
            /***？？？？？？？？？？？？？？？？？？？？？？？？？？？？？***/
            virtualFileManager::getVFM().deleted((char *) relocated_path);
        }

        return ret;
    }
    errno = EACCES;
    return -1;
}

// int symlinkat(const char *oldpath, int newdirfd, const char *newpath);
HOOK_DEF(int, symlinkat, const char *oldpath, int newdirfd, const char *newpath) {
    char temp[PATH_MAX];
    const char *relocated_path_old = relocate_path(oldpath, temp, sizeof(temp));
    if (relocated_path_old) {
        return static_cast<int>(syscall(__NR_symlinkat, relocated_path_old, newdirfd, newpath));
    }
    errno = EACCES;
    return -1;
}

// int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags);
HOOK_DEF(int, linkat, int olddirfd, const char *oldpath, int newdirfd, const char *newpath,
         int flags) {
    char temp[PATH_MAX];
    const char *relocated_path_old = relocate_path(oldpath, temp, sizeof(temp));
    if (relocated_path_old) {
        return static_cast<int>(syscall(__NR_linkat, olddirfd, relocated_path_old, newdirfd,
                                        newpath,
                                        flags));
    }
    errno = EACCES;
    return -1;
}

// int mkdirat(int dirfd, const char *pathname, mode_t mode);
HOOK_DEF(int, mkdirat, int dirfd, const char *pathname, mode_t mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_mkdirat, dirfd, relocated_path, mode));
    }
    errno = EACCES;
    return -1;
}

// ssize_t readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz);
HOOK_DEF(ssize_t, readlinkat, int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    limbus_log_io_path("readlinkat", pathname, relocated_path);
    if (__predict_true(relocated_path)) {
        /*
         * readlinkat 的公开 ABI 返回 ssize_t。arm64 若错误返回 int，-1 会被零扩展为
         * 0x00000000ffffffff；Android 16 的 realpath 会把它当作长度并越界写入。
         */
        ssize_t ret = static_cast<ssize_t>(
                syscall(__NR_readlinkat, dirfd, relocated_path, buf, bufsiz));
        return ret;
    }
    errno = EACCES;
    return -1;
}


// int truncate(const char *path, off_t length);
HOOK_DEF(int, truncate, const char *pathname, off_t length) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_truncate, relocated_path, length));
    }
    errno = EACCES;
    return -1;
}

// int chdir(const char *path);
HOOK_DEF(int, chdir, const char *pathname) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_chdir, relocated_path));
    }
    errno = EACCES;
    return -1;
}

// int truncate64(const char *pathname, off_t length);
HOOK_DEF(int, truncate64, const char *pathname, off_t length) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        int ret = static_cast<int>(syscall(__NR_truncate, relocated_path, length));

        /*zString op("truncate64 length %ld ret %d err %s", length, ret, getErr);
        doFileTrace(relocated_path, op.toString());*/
        return ret;
    }

    errno = EACCES;
    return -1;
}


HOOK_DEF(char *, getcwd, char *buf, size_t size) {
    long ret = syscall(__NR_getcwd, buf, size);
    if (ret < 0) {
        return nullptr;
    }
    if (reverse_relocate_path_inplace(buf, size) < 0) {
        errno = EACCES;
        return nullptr;
    }
    return buf;
}

// int __openat(int fd, const char *pathname, int flags, int mode);
HOOK_DEF(int, openat, int fd, const char *pathname, int flags, int mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path_with_dirfd(fd, pathname, temp, sizeof(temp));
    limbus_log_io_path("openat", pathname, relocated_path);
    if (__predict_true(relocated_path)) {
        if ((flags & O_ACCMODE) == O_WRONLY) {
            flags &= ~O_ACCMODE;
            flags |= O_RDWR;
        }

        int ret = static_cast<int>(syscall(__NR_openat, fd, relocated_path, flags, mode));
        limbus_record_localize_open("openat", pathname, relocated_path, ret, flags, __builtin_return_address(0));
        if (ret < 0) {
            limbus_log_io_failure("openat", pathname, relocated_path, ret, errno);
        }
        /*zString op("openat fd = %d err = %s", ret, strerror(errno));
        doFileTrace(relocated_path, op.toString());*/

        if (getApiLevel() >= 29) {
            xdja::zs::sp<virtualFileDescribe> oldVfd(
                    virtualFileDescribeSet::getVFDSet().get(ret));
            if (oldVfd.get() != nullptr) {
                virtualFileDescribeSet::getVFDSet().reset(ret);
                xdja::zs::sp<virtualFile> vf(oldVfd->_vf->get());
                if (vf.get() != nullptr) {
                    virtualFileManager::getVFM().releaseVF(vf->getPath(), oldVfd.get());
                }
                oldVfd.get()->decStrong(0);
            }
        }

        if (ret > 0 && (is_TED_Enable() || changeDecryptState(false, 1)) &&
            isEncryptPath(relocated_path)) {

            /*******************only here**********************/
            virtualFileDescribe *pvfd = new virtualFileDescribe(ret);
            pvfd->incStrong(0);
            /***************************************************/
            xdja::zs::sp<virtualFileDescribe> vfd(pvfd);

            int _Errno;
            xdja::zs::sp <virtualFile> vf(
                    virtualFileManager::getVFM().getVF(vfd.get(), (char *) relocated_path, &_Errno));

            virtualFileDescribeSet::getVFDSet().set(ret, pvfd);

            if (vf.get() != nullptr) {
                LOGE("judge : open vf [PATH %s] [VFS %d] [FD %d] [VFD %p]", vf->getPath(),
                     vf->getVFS(), ret, vfd.get());
                if ((flags & O_APPEND) == O_APPEND) {
                    vf->vlseek(vfd.get(), 0, SEEK_END);
                } else {
                    vf->vlseek(vfd.get(), 0, SEEK_SET);
                }
            } else {
                virtualFileDescribeSet::getVFDSet().reset(ret);
                /******through this way to release vfd *********/
                virtualFileDescribeSet::getVFDSet().release(pvfd);
                /***********************************************/

                if (_Errno < 0) {
                    //这种情况需要让openat 返回失败
                    originalInterface::original_close(ret);
                    ret = -1;
                    errno = EACCES;

                    if (flags & O_CREAT) {
                        originalInterface::original_unlinkat(AT_FDCWD, relocated_path, 0);
                    }

                    LOGE("judge : **** force openat fail !!! ****");
                }
            }
        }
        return ret;
    }
    errno = EACCES;
    return -1;
}

HOOK_DEF(int, close, int __fd) {
    int ret;
    LOGE("close fd[%d]", __fd);
    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(__fd));
    if (vfd.get() == nullptr) {
        if (virtualFileDescribeSet::getVFDSet().getFlag(__fd)) {
            log("close fd[%d] flag is closing", __fd);
            return -1;
        }
    } else {
        virtualFileDescribeSet::getVFDSet().setFlag(__fd, FD_CLOSING);

        virtualFileDescribeSet::getVFDSet().reset(__fd);
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            log("trace_close fd[%d]path[%s]vfd[%p]", __fd, vf->getPath(), vfd.get());
            virtualFileManager::getVFM().releaseVF(vf->getPath(), vfd.get());
        }

        /******through this way to release vfd *********/
        virtualFileDescribeSet::getVFDSet().release(vfd.get());
        /***********************************************/
    }

    limbus_record_localize_close(__fd);
    ret = static_cast<int>(syscall(__NR_close, __fd));
    virtualFileDescribeSet::getVFDSet().clearFlag(__fd);
    return ret;
}


// int __statfs (__const char *__file, struct statfs *__buf);
HOOK_DEF(int, __statfs, __const char *__file, struct statfs *__buf) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(__file, temp, sizeof(temp));
    limbus_log_io_path("__statfs", __file, relocated_path);
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_statfs, relocated_path, __buf));
    }
    errno = EACCES;
    return -1;
}

HOOK_DEF(int, statfs, __const char *__file, struct statfs *__buf) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(__file, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_statfs, relocated_path, __buf));
    }
    errno = EACCES;
    return -1;
}

static char **relocate_envp(const char *pathname, char *const envp[]) {
    if (strstr(pathname, "libweexjsb.so")) {
        return const_cast<char **>(envp);
    }
    char *soPath = getenv("V_SO_PATH");
    char *soPath64 = getenv("V_SO_PATH_64");

    char *env_so_path = NULL;
    FILE *fd = fopen(pathname, "r");
    if (!fd) {
        return const_cast<char **>(envp);
    }
    for (int i = 0; i < 4; ++i) {
        fgetc(fd);
    }
    int type = fgetc(fd);
    if (type == ELFCLASS32) {
        env_so_path = soPath;
    } else if (type == ELFCLASS64) {
        env_so_path = soPath64;
    }
    fclose(fd);
    if (env_so_path == NULL) {
        return const_cast<char **>(envp);
    }
    int len = 0;
    int ld_preload_index = -1;
    int self_so_index = -1;
    while (envp[len]) {
        /* find LD_PRELOAD element */
        if (ld_preload_index == -1 && !strncmp(envp[len], "LD_PRELOAD=", 11)) {
            ld_preload_index = len;
        }
        if (self_so_index == -1 && !strncmp(envp[len], "V_SO_PATH=", 10)) {
            self_so_index = len;
        }
        ++len;
    }
    /* append LD_PRELOAD element */
    if (ld_preload_index == -1) {
        ++len;
    }
    /* append V_env element */
    if (self_so_index == -1) {
        // V_SO_PATH
        // V_API_LEVEL
        // V_PREVIEW_API_LEVEL
        // V_NATIVE_PATH
        len += 4;
        if (soPath64) {
            // V_SO_PATH_64
            len++;
        }
        len += get_keep_item_count();
        len += get_forbidden_item_count();
        len += get_replace_item_count() * 2;
    }

    /* append NULL element */
    ++len;

    char **relocated_envp = (char **) malloc(len * sizeof(char *));
    memset(relocated_envp, 0, len * sizeof(char *));
    for (int i = 0; envp[i]; ++i) {
        if (i != ld_preload_index) {
            relocated_envp[i] = strdup(envp[i]);
        }
    }
    char LD_PRELOAD_VARIABLE[PATH_MAX];
    if (ld_preload_index == -1) {
        ld_preload_index = len - 2;
        sprintf(LD_PRELOAD_VARIABLE, "LD_PRELOAD=%s", env_so_path);
    } else {
        const char *orig_ld_preload = envp[ld_preload_index] + 11;
        sprintf(LD_PRELOAD_VARIABLE, "LD_PRELOAD=%s:%s", env_so_path, orig_ld_preload);
    }
    relocated_envp[ld_preload_index] = strdup(LD_PRELOAD_VARIABLE);
    int index = 0;
    while (relocated_envp[index]) index++;
    if (self_so_index == -1) {
        char element[PATH_MAX] = {0};
        sprintf(element, "V_SO_PATH=%s", soPath);
        relocated_envp[index++] = strdup(element);
        if (soPath64) {
            sprintf(element, "V_SO_PATH_64=%s", soPath64);
            relocated_envp[index++] = strdup(element);
        }
        sprintf(element, "V_API_LEVEL=%s", getenv("V_API_LEVEL"));
        relocated_envp[index++] = strdup(element);
        sprintf(element, "V_PREVIEW_API_LEVEL=%s", getenv("V_PREVIEW_API_LEVEL"));
        relocated_envp[index++] = strdup(element);
        sprintf(element, "V_NATIVE_PATH=%s", getenv("V_NATIVE_PATH"));
        relocated_envp[index++] = strdup(element);

        for (int i = 0; i < get_keep_item_count(); ++i) {
            PathItem &item = get_keep_items()[i];
            char env[PATH_MAX] = {0};
            sprintf(env, "V_KEEP_ITEM_%d=%s", i, item.path);
            relocated_envp[index++] = strdup(env);
        }

        for (int i = 0; i < get_forbidden_item_count(); ++i) {
            PathItem &item = get_forbidden_items()[i];
            char env[PATH_MAX] = {0};
            sprintf(env, "V_FORBID_ITEM_%d=%s", i, item.path);
            relocated_envp[index++] = strdup(env);
        }

        for (int i = 0; i < get_replace_item_count(); ++i) {
            ReplaceItem &item = get_replace_items()[i];
            char src[PATH_MAX] = {0};
            char dst[PATH_MAX] = {0};
            sprintf(src, "V_REPLACE_ITEM_SRC_%d=%s", i, item.orig_path);
            sprintf(dst, "V_REPLACE_ITEM_DST_%d=%s", i, item.new_path);
            relocated_envp[index++] = strdup(src);
            relocated_envp[index++] = strdup(dst);
        }
    }
    return relocated_envp;
}


// int (*origin_execve)(const char *pathname, char *const argv[], char *const envp[]);
HOOK_DEF(int, execve, const char *pathname, char *argv[], char *const envp[]) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (!relocated_path) {
        errno = EACCES;
        return -1;
    }

    char **relocated_envp = relocate_envp(relocated_path, envp);
    int ret = static_cast<int>(syscall(__NR_execve, relocated_path, argv, relocated_envp));
    if (relocated_envp != envp) {
        int i = 0;
        while (relocated_envp[i] != nullptr) {
            free(relocated_envp[i]);
            ++i;
        }
        free(relocated_envp);
    }
    return ret;
}

HOOK_DEF(void *, dlopen_CI, const char *filename, int flag) {
    char temp[PATH_MAX];
    const char *redirect_path = relocate_path(filename, temp, sizeof(temp));
    void *ret = orig_dlopen_CI(redirect_path, flag);
    onSoLoaded(filename, ret);
    return ret;
}

HOOK_DEF(void*, do_dlopen_CIV, const char *filename, int flag, const void *extinfo) {
    char temp[PATH_MAX];
    const char *redirect_path = relocate_path(filename, temp, sizeof(temp));
    void *ret = orig_do_dlopen_CIV(redirect_path, flag, extinfo);
    onSoLoaded(filename, ret);
    return ret;
}

HOOK_DEF(void*, do_dlopen_CIVV, const char *name, int flags, const void *extinfo,
         void *caller_addr) {
    char temp[PATH_MAX];
    const char *redirect_path = relocate_path(name, temp, sizeof(temp));
    void *ret = orig_do_dlopen_CIVV(redirect_path, flags, extinfo, caller_addr);
    onSoLoaded(name, ret);
    return ret;
}

//void *dlsym(void *handle, const char *symbol)
HOOK_DEF(void*, dlsym, void *handle, char *symbol) {
    return orig_dlsym(handle, symbol);
}

HOOK_DEF(pid_t, vfork) {
    return fork();
}

HOOK_DEF(ssize_t, pread64, int fd, void* buf, size_t count, off64_t offset) {
    ssize_t ret = 0;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if (virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("pread64 fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        /*path.format("%s", vfd->_vf->getPath());*/
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vpread64(vfd.get(), (char *) buf, count, offset);
            flag = true;
        }
    }

    if(!flag)
        ret = orig_pread64(fd, buf, count, offset);

    limbus_record_localize_read("pread64", fd, count, ret, offset, true);
    return ret;
}

HOOK_DEF(ssize_t, pwrite64, int fd, const void *buf, size_t count, off64_t offset) {
    ssize_t ret = 0;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if (virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("pwrite64 fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vpwrite64(vfd.get(), (char *) buf, count, offset);
            flag = true;
        }
    }

    if(!flag)
        ret = orig_pwrite64(fd, buf, count, offset);

    return ret;
}

HOOK_DEF(ssize_t, read, int fd, void *buf, size_t count) {
    ssize_t ret = 0;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if (virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("read fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vread(vfd.get(), (char *) buf, count);
            flag = true;
        }
    }

    if(!flag)
        ret = syscall(__NR_read, fd, buf, count);

    limbus_record_localize_read("read", fd, count, ret, 0, false);
    return ret;
}

HOOK_DEF(size_t, fread, void *ptr, size_t size, size_t nmemb, FILE *stream) {
    size_t ret = orig_fread(ptr, size, nmemb, stream);
    size_t requested = 0;
    if (size != 0 && nmemb <= SIZE_MAX / size) {
        requested = size * nmemb;
    } else {
        requested = SIZE_MAX;
    }
    size_t bytes = 0;
    if (size != 0 && ret <= SIZE_MAX / size) {
        bytes = ret * size;
    } else {
        bytes = SIZE_MAX;
    }
    limbus_record_localize_stream_read("fread", stream, requested,
                                       bytes > static_cast<size_t>(SSIZE_MAX)
                                               ? SSIZE_MAX
                                               : static_cast<ssize_t>(bytes));
    return ret;
}

HOOK_DEF(size_t, fread_unlocked, void *ptr, size_t size, size_t nmemb, FILE *stream) {
    size_t ret = orig_fread_unlocked(ptr, size, nmemb, stream);
    size_t requested = 0;
    if (size != 0 && nmemb <= SIZE_MAX / size) {
        requested = size * nmemb;
    } else {
        requested = SIZE_MAX;
    }
    size_t bytes = 0;
    if (size != 0 && ret <= SIZE_MAX / size) {
        bytes = ret * size;
    } else {
        bytes = SIZE_MAX;
    }
    limbus_record_localize_stream_read("fread_unlocked", stream, requested,
                                       bytes > static_cast<size_t>(SSIZE_MAX)
                                               ? SSIZE_MAX
                                               : static_cast<ssize_t>(bytes));
    return ret;
}

HOOK_DEF(char *, fgets, char *s, int size, FILE *stream) {
    char *ret = orig_fgets(s, size, stream);
    ssize_t bytes = ret != nullptr ? static_cast<ssize_t>(strlen(s)) : 0;
    limbus_record_localize_stream_read("fgets", stream,
                                       size > 0 ? static_cast<size_t>(size) : 0,
                                       bytes);
    return ret;
}

HOOK_DEF(ssize_t, write, int fd, const void* buf, size_t count) {
    ssize_t ret = 0;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if (virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("write fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        /*path.format("%s", vfd->_vf->getPath());*/
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vwrite(vfd.get(), (char *) buf, count);
            flag = true;
        }
    }

    if(!flag)
        ret = syscall(__NR_write, fd, buf, count);

    return ret;
}

HOOK_DEF(int, munmap, void *addr, size_t length) {
    int ret = -1;

    MmapFileInfo *fileInfo = 0;
    std::map<int64_t , MmapFileInfo *>::iterator iter = MmapInfoMap.find(std::int64_t(addr));
    if (iter != MmapInfoMap.end()) {
        MmapInfoMap.erase(iter);
        fileInfo = iter->second;
        if ((fileInfo->_flag & MAP_SHARED)) {
            int fd = syscall(__NR_openat, AT_FDCWD, fileInfo->_path, O_RDWR, 0);

            if (fd > 0 && isEncryptPath(fileInfo->_path)) {
                virtualFileDescribe *pvfd = new virtualFileDescribe(fd);
                xdja::zs::sp<virtualFileDescribe> vfd(pvfd);

                int _Errno;
                xdja::zs::sp<virtualFile> vf(virtualFileManager::getVFM().getVF(vfd.get(), fileInfo->_path,
                                                                     &_Errno));
                virtualFileDescribeSet::getVFDSet().set(fd, pvfd);
                if (vf.get() != nullptr) {
                    vf->vpwrite64(vfd.get(), (char *) addr, length, fileInfo->_offsize * 4096);
                }

                virtualFileDescribeSet::getVFDSet().reset(fd);
                vf->delRef();
            }
            syscall(__NR_close, fd);
        }
    }

    ret = static_cast<int>(syscall(__NR_munmap, addr, length));

    return ret;
}

HOOK_DEF(int, msync, void *addr, size_t size, int flags) {
    int ret = -1;

    MmapFileInfo *fileInfo = 0;
    std::map<int64_t , MmapFileInfo *>::iterator iter = MmapInfoMap.find(std::int64_t(addr));
    if (iter != MmapInfoMap.end()) {
        MmapInfoMap.erase(iter);
        fileInfo = iter->second;
        if ((fileInfo->_flag & MAP_SHARED)) {
            int fd = syscall(__NR_openat, AT_FDCWD, fileInfo->_path, O_RDWR, 0);

            if (fd > 0 && isEncryptPath(fileInfo->_path)) {
                virtualFileDescribe *pvfd = new virtualFileDescribe(fd);
                xdja::zs::sp<virtualFileDescribe> vfd(pvfd);

                int _Errno;
                xdja::zs::sp<virtualFile> vf(virtualFileManager::getVFM().getVF(vfd.get(), fileInfo->_path,
                                                                     &_Errno));
                virtualFileDescribeSet::getVFDSet().set(fd, pvfd);
                if (vf.get() != nullptr) {
                    vf->vpwrite64(vfd.get(), (char *) addr, size, fileInfo->_offsize * 4096);
                }

                virtualFileDescribeSet::getVFDSet().reset(fd);
                vf->delRef();
            }
            syscall(__NR_close, fd);
        }
    }

    ret = static_cast<int>(syscall(__NR_msync, addr, size, flags));

    return ret;
}

HOOK_DEF(void *, mmap, void *addr, size_t length, int prot,int flags, int fd, size_t pgoffset) {
    void * ret = 0;
    bool flag = false;

    do {
        if (fd == -1) break;

        xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));

        if (vfd.get() == nullptr) {
            if(virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
                log("__mmap2 fd[%d] flag is closing", fd);
                return MAP_FAILED;
            }
        } else {
            xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
            if (vf.get() != nullptr) {
                if (vf->getVFS() == VFS_ENCRYPT) {
                    flags |= MAP_ANONYMOUS;     //申请匿名内存
                    ret = (void *) syscall(__NR_mmap, addr, length, prot, flags, 0, 0);

                    bool nowrite = (prot & PROT_WRITE) == 0;
                    if (nowrite && -1 == mprotect(ret, length, prot | PROT_WRITE)) {
                        LOGE("__mmap2 mprotect failed.");
                    } else {
                        vf->vpread64(vfd.get(), (char *) ret, length, pgoffset * 4096);

                        if (nowrite) {
                            if (0 != mprotect(ret, length, prot)) {
                                LOGE("__mmap2 mprotect restore prot fails.");
                            }
                        }
                        MmapFileInfo *fileInfo = new MmapFileInfo(vf->getPath(), pgoffset,
                                                                  flags);
                        MmapInfoMap.insert(
                                std::pair<int64_t , MmapFileInfo *>(int64_t(ret), fileInfo));
                        flag = true;
                    }
                }
            }
        }
    }while(false);

    if(fd > 0)
    {
        /*zString path;
        getPathFromFd(fd, path);

        zString op("%c__mmap2 length %d flags %p pgoffset %p", flag?'v':' ', length, flags, pgoffset);
        doFileTrace(path.toString(), op.toString());*/
    }

    if(!flag)
        ret = (void *) syscall(__NR_mmap, addr, length, prot, flags, fd, pgoffset);

    limbus_record_localize_mmap("mmap", fd, ret, length, prot, flags, pgoffset, flag);
    return ret;
}

HOOK_DEF(off_t, lseek, int fd, off_t offset, int whence)
{
    off_t ret;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if(virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("lseek fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        /*path.format("%s", vfd->_vf->getPath());*/
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vlseek(vfd.get(), offset, whence);
            flag = true;
        }
    }

    if(!flag)
        ret = orig_lseek(fd, offset, whence);

    limbus_record_localize_fd_meta("lseek", fd, offset, whence, ret);
    return ret;
}

HOOK_DEF(int, __llseek, unsigned int fd, unsigned long offset_high,
         unsigned long offset_low, off64_t *result,
         unsigned int whence)
{
    bool flag = false;

    int ret;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if(virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("__llseek fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        /*path.format("%s", vfd->_vf->getPath());*/
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vllseek(vfd.get(), offset_high, offset_low, result, whence);
            flag = true;
        }
    }

    if(!flag)
        ret = orig___llseek(fd, offset_high, offset_low, result, whence);

    limbus_record_localize_fd_meta("__llseek",
                                   static_cast<int>(fd),
                                   (static_cast<long long>(offset_high) << 32)
                                           | static_cast<long long>(offset_low),
                                   whence,
                                   ret);
    return ret;
}

//int ftruncate64(int, off_t)
HOOK_DEF(int, ftruncate64, int fd, off64_t length)
{
    int ret;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if(virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("ftruncate64 fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        /*path.format("%s", vfd->_vf->getPath());*/
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vftruncate64(vfd.get(), length);
            flag = true;
        }
    }

    if(!flag)
        ret = orig_ftruncate64(fd, length);

    return ret;
}

//ssize_t sendfile(int out_fd, int in_fd, off_t* offset, size_t count)
HOOK_DEF(ssize_t, sendfile, int out_fd, int in_fd, off_t* offset, size_t count)
{
    ssize_t ret;

    off_t off = 0;
    if(offset != 0)
        off = *offset;

    struct stat st;
    originalInterface::original_fstat(in_fd,&st);

    xdja::zs::sp<virtualFileDescribe> in_vfd(virtualFileDescribeSet::getVFDSet().get(in_fd));
    xdja::zs::sp<virtualFileDescribe> out_vfd(virtualFileDescribeSet::getVFDSet().get(out_fd));
    if(in_vfd.get() == nullptr && out_vfd.get() == nullptr) {
        if((virtualFileDescribeSet::getVFDSet().getFlag(out_fd)) &&
           (virtualFileDescribeSet::getVFDSet().getFlag(in_fd))) {
            log("sendfile out_fd[%d] and in_fd[%d] flag is closing", out_fd, in_fd);
            return -1;
        }
        //完全不管
        ret = orig_sendfile(out_fd, in_fd, offset, count);
    } else {
        if(in_vfd.get() != nullptr && out_vfd.get() != nullptr) //完全管理
        {
            xdja::zs::sp<virtualFile> in_vf(in_vfd->_vf->get());
            xdja::zs::sp<virtualFile> out_vf(out_vfd->_vf->get());

            size_t real_count = 0;
            int encryptFileHeadLength = in_vf.get()->getHeaderOffSet();
            if(off + count > (st.st_size - encryptFileHeadLength)) {
                real_count = (size_t)(st.st_size - encryptFileHeadLength - off);
            } else {
                real_count = count;
            }

            if(offset != 0)
            {
                in_vf->vlseek(in_vfd.get(), off, SEEK_SET);
            } else {
                in_vf->vlseek(in_vfd.get(), 0, SEEK_CUR);
            }

            char * buf = new char[1024]{0};
            ret = 0;
            int rl = 0;
            int size = 0;
            while(size < real_count) {
                size += 1024;
                if(size > real_count) {
                    rl = in_vf->vread(in_vfd.get(),buf,real_count % 1024);
                } else {
                    rl = in_vf->vread(in_vfd.get(),buf,1024);
                }
                out_vf->vwrite(out_vfd.get(),buf,rl);
                ret += rl;
            }

            delete []buf;

            if(offset != 0)
            {
                in_vf->vlseek(in_vfd.get(), off, SEEK_SET);
            }
        }
        else if(in_vfd.get() == nullptr && out_vfd.get() != nullptr)
        {
            if (virtualFileDescribeSet::getVFDSet().getFlag(in_fd)) {
                log("sendfile in_fd[%d] flag is closing", in_fd);
                return -1;
            }

            xdja::zs::sp<virtualFile> out_vf(out_vfd->_vf->get());

            size_t real_count = 0;
            if(off + count > st.st_size) {
                real_count = (size_t)(st.st_size - off);
            } else {
                real_count = count;
            }

            if(offset != 0)
            {
                ignoreFile::lseek(in_fd, off, SEEK_SET);
            } else {
                ignoreFile::lseek(in_fd, 0, SEEK_CUR);
            }

            char * buf = new char[1024]{0};
            ret = 0;
            int rl = 0;
            int size = 0;
            while(size < real_count) {
                size += 1024;
                if(size > real_count) {
                    rl = ignoreFile::read(in_fd,buf,real_count % 1024);
                } else {
                    rl = ignoreFile::read(in_fd,buf,1024);
                }
                out_vf->vwrite(out_vfd.get(),buf,rl);
                ret += rl;
            }

            delete []buf;

            if(offset != 0)
            {
                ignoreFile::lseek(in_fd, off, SEEK_SET);
            }
        }
        else if(in_vfd.get() != nullptr && out_vfd.get() == nullptr)
        {
            if (virtualFileDescribeSet::getVFDSet().getFlag(out_fd)) {
                log("sendfile out_fd[%d] flag is closing", out_fd);
                return -1;
            }

            xdja::zs::sp<virtualFile> in_vf(in_vfd->_vf->get());

            size_t real_count = 0;
            int encryptFileHeadLength = in_vf.get()->getHeaderOffSet();
            if(off + count > (st.st_size - encryptFileHeadLength)) {
                real_count = (size_t)(st.st_size - encryptFileHeadLength - off);
            } else {
                real_count = count;
            }

            if(offset != 0)
            {
                in_vf->vlseek(in_vfd.get(), off, SEEK_SET);
            } else {
                in_vf->vlseek(in_vfd.get(), 0, SEEK_CUR);
            }

            char * buf = new char[1024];
            ret = 0;
            int rl = 0;
            int size = 0;
            while(size < real_count) {
                size += 1024;
                if(size > real_count) {
                    rl = in_vf->vread(in_vfd.get(),buf,real_count % 1024);
                } else {
                    rl = in_vf->vread(in_vfd.get(),buf,1024);
                }
                ignoreFile::write(out_fd,buf,rl);
                ret += rl;
            }

            delete []buf;

            if(offset != 0)
            {
                in_vf->vlseek(in_vfd.get(), off, SEEK_SET);
            }
        }
    }

    return ret;
}

//ssize_t sendfile64(int out_fd, int in_fd, off64_t* offset, size_t count)
HOOK_DEF(ssize_t, sendfile64, int out_fd, int in_fd, off64_t* offset, size_t count)
{
    ssize_t ret;

    off64_t off = 0;
    if(offset != 0)
        off = *offset;

    struct stat st;
    originalInterface::original_fstat(in_fd,&st);

    unsigned long off_hi = static_cast<unsigned long>(off >> 32);
    unsigned long off_lo = static_cast<unsigned long>(off);

    xdja::zs::sp<virtualFileDescribe> in_vfd(virtualFileDescribeSet::getVFDSet().get(in_fd));
    xdja::zs::sp<virtualFileDescribe> out_vfd(virtualFileDescribeSet::getVFDSet().get(out_fd));
    if(in_vfd.get() == nullptr && out_vfd.get() == nullptr) {
        if((virtualFileDescribeSet::getVFDSet().getFlag(out_fd)) &&
           (virtualFileDescribeSet::getVFDSet().getFlag(in_fd))) {
            log("sendfile64 out_fd[%d] and in_fd[%d] flag is closing", out_fd, in_fd);
            return -1;
        }
        //完全不管
        ret = orig_sendfile64(out_fd, in_fd, offset, count);
    } else {
        if(in_vfd.get() != nullptr && out_vfd.get() != nullptr) //完全管理
        {
            xdja::zs::sp<virtualFile> in_vf(in_vfd->_vf->get());
            xdja::zs::sp<virtualFile> out_vf(out_vfd->_vf->get());

            size_t real_count = 0;
            int encryptFileHeadLength = in_vf.get()->getHeaderOffSet();
            if(off + count > (st.st_size - encryptFileHeadLength)) {
                real_count = (size_t)(st.st_size - encryptFileHeadLength - off);
            } else {
                real_count = count;
            }

            if(offset != 0)
            {
                loff_t result;
                in_vf->vllseek(in_vfd.get(), off_hi, off_lo, &result, SEEK_SET);
            } else {
                in_vf->vlseek(in_vfd.get(), 0, SEEK_CUR);
            }

            char * buf = new char[1024]{0};
            ret = 0;
            int rl = 0;
            int size = 0;
            while(size < real_count) {
                size += 1024;
                if(size > real_count) {
                    rl = in_vf->vread(in_vfd.get(),buf,real_count % 1024);
                } else {
                    rl = in_vf->vread(in_vfd.get(),buf,1024);
                }
                out_vf->vwrite(out_vfd.get(),buf,rl);
                ret += rl;
            }

            delete []buf;

            if(offset != 0)
            {
                loff_t result;
                in_vf->vllseek(in_vfd.get(), off_hi, off_lo, &result, SEEK_SET);
            }
        }
        else if(in_vfd.get() == nullptr && out_vfd.get() != nullptr)
        {
            if (virtualFileDescribeSet::getVFDSet().getFlag(in_fd)) {
                log("sendfile64 in_fd[%d] flag is closing", in_fd);
                return -1;
            }
            xdja::zs::sp<virtualFile> out_vf(out_vfd->_vf->get());

            size_t real_count = 0;
            if(off + count > st.st_size) {
                real_count = (size_t)(st.st_size - off);
            } else {
                real_count = count;
            }

            if(offset != 0)
            {
                loff_t result;
                ignoreFile::llseek(in_fd, off_hi, off_lo, &result, SEEK_SET);
            } else {
                ignoreFile::lseek(in_fd, 0, SEEK_CUR);
            }

            char * buf = new char[1024]{0};
            ret = 0;
            int rl = 0;
            int size = 0;
            while(size < real_count) {
                size += 1024;
                if(size > real_count) {
                    rl = ignoreFile::read(in_fd,buf,real_count % 1024);
                } else {
                    rl = ignoreFile::read(in_fd,buf,1024);
                }
                out_vf->vwrite(out_vfd.get(),buf,rl);
                ret += rl;
            }

            delete []buf;

            if(offset != 0)
            {
                loff_t result;
                ignoreFile::llseek(in_fd, off_hi, off_lo, &result, SEEK_SET);
            }
        }
        else if(in_vfd.get() != nullptr && out_vfd.get() == nullptr)
        {
            if (virtualFileDescribeSet::getVFDSet().getFlag(out_fd)) {
                log("sendfile64 out_fd[%d] flag is closing", out_fd);
                return -1;
            }

            xdja::zs::sp<virtualFile> in_vf(in_vfd->_vf->get());

            size_t real_count = 0;
            int encryptFileHeadLength = in_vf.get()->getHeaderOffSet();
            if(off + count > (st.st_size - encryptFileHeadLength)) {
                real_count = (size_t)(st.st_size - encryptFileHeadLength - off);
            } else {
                real_count = count;
            }

            if(offset != 0)
            {
                loff_t result;
                in_vf->vllseek(in_vfd.get(), off_hi, off_lo, &result, SEEK_SET);
            } else {
                in_vf->vlseek(in_vfd.get(), 0, SEEK_CUR);
            }

            char * buf = new char[1024];
            ret = 0;
            int rl = 0;
            int size = 0;
            while(size < real_count) {
                size += 1024;
                if(size > real_count) {
                    rl = in_vf->vread(in_vfd.get(),buf,real_count % 1024);
                } else {
                    rl = in_vf->vread(in_vfd.get(),buf,1024);
                }
                ignoreFile::write(out_fd,buf,rl);
                ret += rl;
            }

            delete []buf;

            if(offset != 0)
            {
                loff_t result;
                in_vf->vllseek(in_vfd.get(), off_hi, off_lo, &result, SEEK_SET);
            }
        }
    }

    return ret;
}

//int dup(int oldfd);
HOOK_DEF(int, dup, int oldfd)
{
    int ret = static_cast<int>(syscall(__NR_dup, oldfd));

    zString path, path2;
    getPathFromFd(oldfd, path);
    getPathFromFd(ret, path2);

    if (getApiLevel() >= 29) {
        xdja::zs::sp<virtualFileDescribe> oldVfd(
                virtualFileDescribeSet::getVFDSet().get(ret));
        if (oldVfd.get() != nullptr) {
            virtualFileDescribeSet::getVFDSet().reset(ret);
            xdja::zs::sp<virtualFile> vf(oldVfd->_vf->get());
            if (vf.get() != nullptr) {
                virtualFileManager::getVFM().releaseVF(vf->getPath(), oldVfd.get());
            }
            oldVfd.get()->decStrong(0);
        }
    }

    if(ret > 0 && (is_TED_Enable()||changeDecryptState(false,1)) && isEncryptPath(path2.toString())) {
        /*******************only here**********************/
        virtualFileDescribe *pvfd = new virtualFileDescribe(ret);
        pvfd->incStrong(0);
        /***************************************************/
        xdja::zs::sp<virtualFileDescribe> vfd(pvfd);

        int _Errno;
        xdja::zs::sp<virtualFile> vf(virtualFileManager::getVFM().getVF(vfd.get(), path2.toString(), &_Errno));

        virtualFileDescribeSet::getVFDSet().set(ret, pvfd);

        if (vf.get() != nullptr) {
            LOGE("judge : dup vf [PATH %s] [VFS %d] [FD %d]", vf->getPath(), vf->getVFS(), ret);
            vf->vlseek(vfd.get(), 0, SEEK_SET);
        } else {
            virtualFileDescribeSet::getVFDSet().reset(ret);
            /******through this way to release vfd *********/
            virtualFileDescribeSet::getVFDSet().release(pvfd);
            /***********************************************/

            if(_Errno < 0)
            {
                //这种情况需要让openat 返回失败
                /*originalInterface::original_close(ret);
                ret = -1;
                errno = EACCES;

                if(flags & O_CREAT)
                {
                    originalInterface::original_unlinkat(AT_FDCWD, relocated_path, 0);
                }

                LOGE("judge : **** force openat fail !!! ****");*/
            }
        }
    }

    return ret;
}

//int dup3(int oldfd, int newfd, int flags);
HOOK_DEF(int, dup3, int oldfd, int newfd, int flags)
{
    return static_cast<int>(syscall(__NR_dup3, oldfd, newfd, flags));
}

HOOK_DEF(int, fcntl, int fd, int cmd, ...) {
    va_list arg;
    int ret = -1;
    va_start (arg, cmd);
    switch (cmd) {
        case F_DUPFD:
        case F_DUPFD_CLOEXEC: {
            int target = va_arg (arg, int);
            ret = static_cast<int>(syscall(__NR_fcntl, fd, cmd, target));

            if (getApiLevel() >= 29 && ret > 0) {
                xdja::zs::sp<virtualFileDescribe> oldVfd(
                        virtualFileDescribeSet::getVFDSet().get(ret));
                if (oldVfd.get() != nullptr) {
                    virtualFileDescribeSet::getVFDSet().reset(ret);
                    xdja::zs::sp<virtualFile> vf(oldVfd->_vf->get());
                    if (vf.get() != nullptr) {
                        virtualFileManager::getVFM().releaseVF(vf->getPath(), oldVfd.get());
                    }
                    oldVfd.get()->decStrong(0);
                }
            }

            zString path;
            getPathFromFd(ret, path);

            if (ret > 0 && (is_TED_Enable() || changeDecryptState(false, 1)) &&
                isEncryptPath(path.toString())) {
                /*******************only here**********************/
                virtualFileDescribe *pvfd = new virtualFileDescribe(ret);
                pvfd->incStrong(0);
                /***************************************************/
                xdja::zs::sp<virtualFileDescribe> vfd(pvfd);

                int _Errno;
                xdja::zs::sp<virtualFile> vf(
                        virtualFileManager::getVFM().getVF(vfd.get(), path.toString(), &_Errno));

                virtualFileDescribeSet::getVFDSet().set(ret, pvfd);

                if (vf.get() != nullptr) {
                    LOGE("judge : fcntl vf [PATH %s] [VFS %d] [FD %d]", vf->getPath(), vf->getVFS(),
                         ret);
                    vf->vlseek(vfd.get(), 0, SEEK_SET);
                } else {
                    virtualFileDescribeSet::getVFDSet().reset(ret);
                    /******through this way to release vfd *********/
                    virtualFileDescribeSet::getVFDSet().release(pvfd);
                    /***********************************************/
                }
            }
            va_end(arg);
        }
            break;
        default:
            void * target = va_arg(arg, void*);
            ret = orig_fcntl(fd, cmd, target);
            va_end(arg);
            break;
    }

    return ret;
}

HOOK_DEF(int, getaddrinfo,const char *__node, const char *__service, const struct addrinfo *__hints,
         struct addrinfo **__result) {
    int ret = -1;
    if (is_limbus_container_process()) {
        ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
        log_limbus_network_io("getaddrinfo", __node, ret, ret == 0 ? 0 : errno);
        return ret;
    }
    if (__node != nullptr) {
        if (getNetWorkState()) {
            if(isWhiteList()) {
                if(isIPAddress(__node) || isContainsStr(__node, ":")) {
                    if(isIPAddress(__node)) {
                        if(isIpV4Enable(__node)) {
                            ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
                            //log("wkw getaddrinfo: node %s ret %d",__node,ret);
                            return ret;
                        }
                    } else if(isContainsStr(__node,":")) {
                        if(isIpV6Enable(__node)) {
                            ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
                            //log("wkw getaddrinfo: node %s ret %d",__node,ret);
                            return ret;
                        }
                    }
                    errno = EAI_FAIL;
                    //log("wkw getaddrinfo: node %s ret %d",__node,ret);
                    return ret;
                }

                if (!isDomainEnable(__node)) {
                    errno = EAI_FAIL;
                    return ret;
                }
                ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
                if (ret == 0) {
                    struct addrinfo *add_result = (*__result);
                    do {
                        if (add_result->ai_addr->sa_family == AF_INET) {
                            sockaddr_in *pSin = (sockaddr_in *) (add_result->ai_addr);
                            char *ipv4 = inet_ntoa(pSin->sin_addr);
                            //log("wkw getaddrinfo ipv4 %s domain %s", ipv4, __node);
                            addWhiteIpStrategy(ipv4);
                        } else if (add_result->ai_addr->sa_family == AF_INET6) {
                            sockaddr_in6 sin6;
                            memcpy(&sin6, add_result->ai_addr, sizeof(sin6));
                            char ipv6[INET6_ADDRSTRLEN];
                            inet_ntop(AF_INET6, &sin6.sin6_addr, ipv6, sizeof(ipv6));
                            //log("wkw getaddrinfo ipv6 %s domain %s", ipv6, __node);
                            addWhiteIpStrategy(ipv6);
                        }
                        add_result = add_result->ai_next;
                    } while (add_result != nullptr);
                }
                return ret;
            } else {
                if(isIPAddress(__node) || isContainsStr(__node,":")) {
                    if(isIPAddress(__node)) {
                        if(!isIpV4Enable(__node)) {
                            errno = EAI_FAIL;
                            //log("wkw getaddrinfo: node %s ret %d",__node,ret);
                            return ret;
                        }
                    } else if(isContainsStr(__node,":")) {
                        if(!isIpV6Enable(__node)) {
                            errno = EAI_FAIL;
                            //log("wkw getaddrinfo: node %s ret %d",__node,ret);
                            return ret;
                        }
                    }
                    ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
                    //log("wkw getaddrinfo: node %s ret %d",__node,ret);
                    return ret;
                }

                ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
                if(!isDomainEnable(__node)) {
                    struct addrinfo *add_result = (*__result);
                    do {
                        if (add_result->ai_addr->sa_family == AF_INET) {
                            sockaddr_in *pSin = (sockaddr_in *) (add_result->ai_addr);
                            char *ipv4 = inet_ntoa(pSin->sin_addr);
                            //log("wkw getaddrinfo ipv4 %s domain %s", ipv4, __node);
                            addWhiteIpStrategy(ipv4);
                        } else if (add_result->ai_addr->sa_family == AF_INET6) {
                            sockaddr_in6 sin6;
                            memcpy(&sin6, add_result->ai_addr, sizeof(sin6));
                            char ipv6[INET6_ADDRSTRLEN];
                            inet_ntop(AF_INET6, &sin6.sin6_addr, ipv6, sizeof(ipv6));
                            //log("wkw getaddrinfo ipv6 %s domain %s", ipv6, __node);
                            addWhiteIpStrategy(ipv6);
                        }
                        add_result = add_result->ai_next;
                    } while (add_result != nullptr);
                    errno = EAI_FAIL;
                    return -1;
                }
                return ret;
            }
        }
    }
    ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
    return ret;
}

HOOK_DEF(ssize_t, sendto, int fd, const void *buf, size_t n, int flags, struct sockaddr *dst_addr,
         socklen_t dst_addr_length) {
    ssize_t ret = -1;
    if (is_limbus_container_process() && getNetWorkState()) {
        ret = syscall(__NR_sendto, fd, buf, n, flags, dst_addr, dst_addr_length);
        log_limbus_network_bypass("sendto", "socket");
        return ret;
    }
    if (getNetWorkState()) {
        if (nullptr != dst_addr) {
            if (dst_addr->sa_family == AF_INET) {
                sockaddr_in *pSin = (sockaddr_in *) dst_addr;
                char *ipv4 = inet_ntoa(pSin->sin_addr);
                //log("wkw sendto [ipv4 %s]", ipv4);
                if (!isIpV4Enable(ipv4)) {
                    //log("return [ret %d] ",ret);
                    errno = EACCES;
                    return ret;
                }
            } else if (dst_addr->sa_family == AF_INET6) {
                sockaddr_in6 sin6;
                memcpy(&sin6, dst_addr, sizeof(sin6));
                char ipv6[INET6_ADDRSTRLEN];
                inet_ntop(AF_INET6, &sin6.sin6_addr, ipv6, sizeof(ipv6));
                //log("wkw sendto ipv6:%s", ipv6);
                if (!isIpV6Enable(ipv6)) {
                    errno = EACCES;
                    return ret;
                }
            }
        }
    }
    ret = syscall(__NR_sendto, fd, buf, n, flags, dst_addr, dst_addr_length);
    return ret;
}

HOOK_DEF(int, connect, int sd, struct sockaddr *addr, socklen_t socklen) {
    int ret = -1;
    if (is_limbus_container_process()) {
        char target[INET6_ADDRSTRLEN + 24] = {};
        format_limbus_socket_target(addr, target, sizeof(target));
        ret = static_cast<int>(syscall(__NR_connect, sd, addr, socklen));
        int error = ret < 0 ? errno : 0;
        log_limbus_network_io("connect", target, ret, error);
        return ret;
    }
    if (getNetWorkState()) {
        if (addr->sa_family == AF_INET) {
            sockaddr_in *pSin = (sockaddr_in *) addr;
            char *ipv4 = inet_ntoa(pSin->sin_addr);
            //int port = pSin->sin_port;
            //log("wkw connect [ipv4 %s]", ipv4);
            if (!isIpV4Enable(ipv4)) {
                //log("return [ret %d] ENETUNREACH",ret);
                errno = ENETUNREACH;//无法传送数据包至指定的主机.
                return ret;
            }
        } else if (addr->sa_family == AF_INET6) {
            sockaddr_in6 sin6;
            memcpy(&sin6, addr, sizeof(sin6));
            char ipv6[INET6_ADDRSTRLEN];
            inet_ntop(AF_INET6, &sin6.sin6_addr, ipv6, sizeof(ipv6));
            //log("wkw connect ipv6:%s", ipv6);
            if (!isIpV6Enable(ipv6)) {
                errno = ENETUNREACH;//无法传送数据包至指定的主机.
                return ret;
            }
        }
    }
    ret = static_cast<int>(syscall(__NR_connect, sd, addr, socklen));
    return ret;
}

HOOK_DEF(void, xlogger_Write, void* _info, const char* _log)
{
    slog_wx("%s", _log);

    orig_xlogger_Write(_info, _log);
}


__END_DECLS
// end IO DEF

bool on_found_syscall_aarch64(const char *path, int num, void *func) {
    static int pass = 0;
    switch (num) {
        case __NR_fchmodat:
            MSHookFunction(func, (void *) new_fchmodat, (void **) &orig_fchmodat);
            pass++;
            break;
        case __NR_faccessat:
            MSHookFunction(func, (void *) new_faccessat, (void **) &orig_faccessat);
            pass++;
            break;
        case __NR_statfs:
            MSHookFunction(func, (void *) new___statfs, (void **) &orig___statfs);
            pass++;
            break;
        case __NR_getcwd:
            MSHookFunction(func, (void *) new_getcwd, (void **) &orig_getcwd);
            pass++;
            break;
        case __NR_openat:
            MSHookFunction(func, (void *) new_openat, (void **) &orig_openat);
            pass++;
            break;
    }
    if (pass == 5) {
        return BREAK_FIND_SYSCALL;
    }
    return CONTINUE_FIND_SYSCALL;
}

bool on_found_linker_syscall_arch64(const char *path, int num, void *func) {
    switch (num) {
        case __NR_openat:
            MSHookFunction(func, (void *) new_openat, (void **) &orig_openat);
            return BREAK_FIND_SYSCALL;
    }
    return CONTINUE_FIND_SYSCALL;
}

void onSoLoaded(const char *name, void *handle) {
    if (handle != nullptr) {
        on_limbus_translation_library_loaded(name, handle);
    }
}

bool relocate_linker(const char* LINKER_PATH) {
    intptr_t linker_addr, dlopen_off, symbol;
    if ((linker_addr = get_addr(LINKER_PATH)) == 0) {
        ALOGE("Cannot found linker addr.");
        return false;
    }
    if (resolve_symbol(LINKER_PATH, "__dl__Z9do_dlopenPKciPK17android_dlextinfoPKv",
                       &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIVV,
                       (void **) &orig_do_dlopen_CIVV);
        return true;
    } else if (resolve_symbol(LINKER_PATH, "__dl__Z9do_dlopenPKciPK17android_dlextinfoPv",
                              &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIVV,
                       (void **) &orig_do_dlopen_CIVV);
        return true;
    } else if (resolve_symbol(LINKER_PATH, "__dl__ZL10dlopen_extPKciPK17android_dlextinfoPv",
                              &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIVV,
                       (void **) &orig_do_dlopen_CIVV);
        return true;
    } else if (
            resolve_symbol(LINKER_PATH, "__dl__Z20__android_dlopen_extPKciPK17android_dlextinfoPKv",
                           &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIVV,
                       (void **) &orig_do_dlopen_CIVV);
        return true;
    } else if (
            resolve_symbol(LINKER_PATH, "__dl___loader_android_dlopen_ext",
                           &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIVV,
                       (void **) &orig_do_dlopen_CIVV);
        return true;
    } else if (resolve_symbol(LINKER_PATH, "__dl__Z9do_dlopenPKciPK17android_dlextinfo",
                              &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIV,
                       (void **) &orig_do_dlopen_CIV);
        return true;
    } else if (resolve_symbol(LINKER_PATH, "__dl__Z8__dlopenPKciPKv",
                              &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIV,
                       (void **) &orig_do_dlopen_CIV);
        return true;
    } else if (resolve_symbol(LINKER_PATH, "__dl___loader_dlopen",
                              &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIV,
                       (void **) &orig_do_dlopen_CIV);
        return true;
    } else if (resolve_symbol(LINKER_PATH, "__dl_dlopen",
                              &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_dlopen_CI,
                       (void **) &orig_dlopen_CI);
        return true;
    }
    return false;
}

void startIOHook(int api_level) {
    // Populate the cache outside signal context and fail before any partial hooks.
    if (!runtime_page::size()) {
        ALOGE("IO hook >>> unsupported or unknown kernel page size");
        return;
    }
    void *handle = dlopen("libc.so", RTLD_NOW);

    if (handle) {
        init_limbus_container_process_flag();
        HOOK_SYMBOL(handle, fchownat);
        // Bionic short wrappers can also be backported by an OEM. Choose from
        // the actual entry instructions instead of the Android version number.
        void *rename_entry = dlsym(handle, "renameat");
        const size_t rename_words = (reinterpret_cast<uintptr_t>(rename_entry) & 7u) ? 5 : 4;
        if (arm64_entry_has_room(static_cast<const uint32_t *>(rename_entry), 5, rename_words)) {
            MSHookFunction(rename_entry, reinterpret_cast<void *>(new_renameat),
                           reinterpret_cast<void **>(&orig_renameat));
        } else {
            void *entry2 = dlsym(handle, "renameat2");
            const size_t words2 = (reinterpret_cast<uintptr_t>(entry2) & 7u) ? 5 : 4;
            if (arm64_entry_has_room(static_cast<const uint32_t *>(entry2), 5, words2)) {
                MSHookFunction(entry2, reinterpret_cast<void *>(new_renameat2),
                               reinterpret_cast<void **>(&orig_renameat2));
            } else {
                ALOGE("IO hook >>> no safe rename wrapper; leave libc unchanged");
            }
        }
        HOOK_SYMBOL(handle, mkdirat);
        HOOK_SYMBOL(handle, mknodat);
        HOOK_SYMBOL(handle, truncate);
        HOOK_SYMBOL(handle, linkat);
        HOOK_SYMBOL(handle, readlinkat);
        HOOK_SYMBOL(handle, unlinkat);
        HOOK_SYMBOL(handle, symlinkat);
        HOOK_SYMBOL(handle, utimensat);
        HOOK_SYMBOL(handle, chdir);
        HOOK_SYMBOL(handle, execve);
        HOOK_SYMBOL(handle, statfs64);
        HOOK_SYMBOL(handle, opendir);
        HOOK_SYMBOL(handle, open);
        HOOK_SYMBOL(handle, open64);
        HOOK_SYMBOL(handle, __open_2);
        HOOK_SYMBOL(handle, openat64);
        HOOK_SYMBOL(handle, fopen);
        HOOK_SYMBOL(handle, fopen64);
        HOOK_SYMBOL(handle, fread);
        HOOK_SYMBOL(handle, fread_unlocked);
        HOOK_SYMBOL(handle, fgets);
        HOOK_SYMBOL(handle, vfork);
        HOOK_SYMBOL(handle, faccessat);
        HOOK_SYMBOL(handle, openat);
        HOOK_SYMBOL(handle, fchmodat);
        HOOK_SYMBOL(handle, fstatat64);
        HOOK_SYMBOL(handle, statfs);
        HOOK_SYMBOL(handle, __statfs);
        HOOK_SYMBOL(handle, __statfs64);
        HOOK_SYMBOL(handle, getcwd);
        HOOK_SYMBOL(handle, stat);
        HOOK_SYMBOL(handle, lstat);
        HOOK_SYMBOL(handle, fstatat);
        HOOK_SYMBOL(handle, close);
        // Android 12's arm64 bionic read stub is not safe for this legacy inline
        // hook implementation.  A virtual package may replace its process title
        // (GMS does this with "com.google.android.gms"), so process-name based
        // exemptions are insufficient and leave read+8 containing a broken
        // trampoline instruction.  Keep libc read untouched for every arm64
        // virtual process; path routing is handled at open/openat/syscall.
        ALOGE("IO hook >>> skip arm64 libc read hook to avoid bionic read+8 SIGILL");
        HOOK_SYMBOL(handle, write);
        HOOK_SYMBOL(handle, mmap);
        HOOK_SYMBOL(handle, munmap);
        HOOK_SYMBOL(handle, pread64);
        HOOK_SYMBOL(handle, pwrite64);
        HOOK_SYMBOL(handle, fstat);
        HOOK_SYMBOL(handle, __llseek);
        HOOK_SYMBOL(handle, lseek);
        HOOK_SYMBOL(handle, ftruncate64);
        HOOK_SYMBOL(handle, sendfile);
        HOOK_SYMBOL(handle, sendfile64);
        HOOK_SYMBOL(handle, dup);
        HOOK_SYMBOL(handle, dup3);
        HOOK_SYMBOL(handle, fcntl);
        HOOK_SYMBOL(handle, getaddrinfo);
        HOOK_SYMBOL(handle, sendto);
#if defined(__x86_64__)
#else
        HOOK_SYMBOL(handle, connect);
#endif
        HOOK_SYMBOL(handle, msync);

        findSyscalls("/system/lib64/libc.so", on_found_syscall_aarch64);
        findSyscalls("/system/bin/linker64", on_found_linker_syscall_arch64);
        dlclose(handle);
    }
    originalInterface::original_lseek = orig_lseek;
    originalInterface::original_llseek = orig___llseek;
    originalInterface::original_fstat = orig_fstat;
    originalInterface::original_pwrite64 = orig_pwrite64;
    originalInterface::original_pread64 = orig_pread64;
    originalInterface::original_ftruncate64 = orig_ftruncate64;
    originalInterface::original_sendfile = orig_sendfile;
    originalInterface::original_getaddrinfo = orig_getaddrinfo;
}


void
IOUniformer::startUniformer(const char *so_path, const char *so_path_64, const char *native_path,
                            int api_level,
                            int preview_api_level) {
    bool ret = ff_Recognizer::getFFR().init(getMagicPath());
    LOGE("FFR path %s init %s", getMagicPath(), ret ? "success" : "fail");
    char api_level_chars[56];
    char pre_api_level_chars[56];
    setenv("V_SO_PATH", so_path, 1);
//    setenv("V_SO_PATH_64", so_path_64, 1);
    sprintf(api_level_chars, "%i", api_level);
    setenv("V_API_LEVEL", api_level_chars, 1);
    sprintf(pre_api_level_chars, "%i", preview_api_level);
    setenv("V_PREVIEW_API_LEVEL", pre_api_level_chars, 1);
    setenv("V_API_LEVEL", api_level_chars, 1);
    setenv("V_NATIVE_PATH", native_path, 1);
    startIOHook(api_level);
    // The legacy IO layer inline-hooks a number of bionic entry points.  Install
    // the translation dlsym lifecycle hook only after that pass has finished;
    // otherwise A64HookFunction tries to relocate an already patched prologue
    // and can crash in __fix_instructions during process startup.
    activate_limbus_translation_lifecycle_hook();
}
