#pragma once

#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <sys/types.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>

namespace container_exec {

// Only used by the diagnostic sampler, never between fork and exec. The reader
// supplied by the runtime uses the saved kernel entry, not the guest proc view.
template<class Read>
bool readStartupProc(pid_t owner, pid_t pid, pid_t tid, int kind, Read read, std::string &out) {
    static const char *files[] = {"comm", "status", "wchan", "syscall", "children"};
    if (owner <= 0 || pid <= 0 || tid < 0 || kind < 0 || kind > 4) return false;
    if (tid && pid != owner) return false;
    if (!tid && pid == owner) return false;
    char base[96], path[112], data[4097]{};
    if (tid) snprintf(base, sizeof(base), "/proc/%d/task/%d", pid, tid);
    else snprintf(base, sizeof(base), "/proc/%d", pid);
    snprintf(path, sizeof(path), "%s/status", base);
    int length = read(path, data, 4096);
    if (length <= 0 || length > 4096) return false;
    data[length] = 0;
    std::string status(data, length);
    auto field = [&](const char *key) {
        std::string pattern = std::string("\n") + key + ":";
        size_t at = status.find(pattern);
        if (at == std::string::npos) return std::string();
        at += pattern.size();
        size_t end = status.find('\n', at);
        return status.substr(at, end - at);
    };
    const long parent = strtol(field("PPid").c_str(), nullptr, 10);
    const long group = strtol(field("Tgid").c_str(), nullptr, 10);
    if ((tid && group != owner) || (!tid && parent != owner)) return false;
    if (kind == 1) {
        std::string state = field("State");
        size_t at = state.find_first_not_of(" \t");
        if (at == std::string::npos || state[at] < 'A' || state[at] > 'Z') return false;
        out = "State:\t" + state.substr(at, 1) + "\nPPid:\t" + std::to_string(parent) + "\n";
        return true;
    }
    if (!tid && (kind == 0 || kind == 4)) return false;
    snprintf(path, sizeof(path), "%s/%s", base, files[kind]);
    length = read(path, data, kind == 0 ? 64 : 512);
    if (length < 0 || length > 512) return false;
    data[length] = 0;
    std::string value(data, length);
    if (kind == 0) {
        out = (value == "UnityMain\n" || value == "UnityMain") ? "UnityMain" : "other";
    } else if (kind == 2) {
        size_t end = value.find_first_of("\n \t");
        out = value.substr(0, end);
        if (out.empty() || out.size() > 96) return false;
        for (char c : out) if (!std::isalnum(static_cast<unsigned char>(c)) && c != '_' && c != '.') return false;
    } else if (kind == 3) {
        out = value.substr(0, value.find_first_of("\n \t"));
        if (out == "running") return true;
        char *end = nullptr;
        long number = strtol(out.c_str(), &end, 10);
        if (out.empty() || *end || number < -1 || number > 65535) return false;
        out = std::to_string(number);
    } else {
        out.clear();
        const char *cursor = value.c_str();
        for (int count = 0; count < 4; ++count) {
            while (*cursor == ' ' || *cursor == '\n' || *cursor == '\t') ++cursor;
            if (!*cursor) break;
            char *end = nullptr;
            long child = strtol(cursor, &end, 10);
            if (end == cursor || child <= 0 || child > 2147483647L) return false;
            if (*end && *end != ' ' && *end != '\n' && *end != '\t') return false;
            if (!out.empty()) out += ' ';
            out += std::to_string(child);
            cursor = end;
        }
    }
    return true;
}

template<class Syscall>
bool readStartupProcUsing(Syscall entry, pid_t pid, pid_t tid, int kind, std::string &out) {
    return readStartupProc(getpid(), pid, tid, kind, [entry](const char *path, char *bytes, int limit) {
        int fd = static_cast<int>(entry(__NR_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0));
        if (fd < 0) return -1;
        int used = 0;
        while (used < limit) {
            long n = entry(__NR_read, fd, bytes + used, limit - used);
            if (n < 0 && errno == EINTR) continue;
            if (n < 0) { used = -1; break; }
            if (n == 0) break;
            used += static_cast<int>(n);
        }
        entry(__NR_close, fd);
        return used;
    }, out);
}

extern "C" bool readStartupProcFromKernel(pid_t pid, pid_t tid, int kind, std::string &out);
}
