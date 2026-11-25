#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/StartupProcReader.h"
#include <cassert>
#include <cstring>
#include <map>
#include <vector>
#include <iostream>

int main() {
    std::map<std::string, std::string> files{
        {"/proc/20/task/21/status", "Name:\tprivate\nTgid:\t20\nState:\tS (sleeping)\nPPid:\t1\n"},
        {"/proc/20/task/21/comm", "UnityMain\n"},
        {"/proc/20/task/21/wchan", "wait_for_vfork_done\n"},
        {"/proc/20/task/21/syscall", "220 0x1234 PRIVATE_ARGUMENTS"},
        {"/proc/20/task/21/children", "30 31 32 33 34 35"},
        {"/proc/30/status", "Name:\tprivate\nTgid:\t30\nState:\tD (disk sleep)\nPPid:\t20\n"},
        {"/proc/30/wchan", "futex_wait_queue_me"},
        {"/proc/31/status", "Name:\tprivate\nTgid:\t31\nState:\tS\nPPid:\t99\n"}
    };
    std::vector<std::string> reads;
    auto read = [&](const char *path, char *bytes, int limit) {
        reads.emplace_back(path);
        auto it = files.find(path);
        if (it == files.end()) return -1;
        int n = std::min(limit, static_cast<int>(it->second.size()));
        memcpy(bytes, it->second.data(), n);
        return n;
    };
    std::string out;
    assert(container_exec::readStartupProc(20, 20, 21, 0, read, out) && out == "UnityMain");
    assert(container_exec::readStartupProc(20, 20, 21, 1, read, out) && out == "State:\tS\nPPid:\t1\n");
    assert(container_exec::readStartupProc(20, 20, 21, 2, read, out) && out == "wait_for_vfork_done");
    assert(container_exec::readStartupProc(20, 20, 21, 3, read, out) && out == "220");
    assert(container_exec::readStartupProc(20, 20, 21, 4, read, out) && out == "30 31 32 33");
    assert(container_exec::readStartupProc(20, 30, 0, 2, read, out) && out == "futex_wait_queue_me");
    reads.clear();
    assert(!container_exec::readStartupProc(20, 31, 0, 3, read, out));
    assert(reads.size() == 1 && reads[0] == "/proc/31/status");
    reads.clear();
    assert(!container_exec::readStartupProc(20, 99, 21, 0, read, out));
    assert(!container_exec::readStartupProc(20, 20, 0, 0, read, out));
    assert(!container_exec::readStartupProc(20, 20, 21, 5, read, out));
    assert(reads.empty());
    files["/proc/20/task/21/comm"] = "personal@example";
    assert(container_exec::readStartupProc(20, 20, 21, 0, read, out) && out == "other");
    files.erase("/proc/20/task/21/children");
    assert(!container_exec::readStartupProc(20, 20, 21, 4, read, out));
    puts("startup proc: own tasks, verified children, denied reads, bounded fields, no private values passed");
}
