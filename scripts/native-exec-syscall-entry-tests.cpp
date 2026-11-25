#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/ExecSyscallEntry.h"
#include <cassert>
#include <cstdio>

int main() {
    const uint32_t bionic[] = {0xd2801ba8, 0xd4000001, 0xb140041f,
                              0xda809400, 0x54ffe828, 0xd65f03c0};
    assert(container_exec::isExecSyscallEntry(bionic, 6));
    assert(!container_exec::isExecSyscallEntry(bionic, 5));
    assert(!container_exec::isExecSyscallEntry(nullptr, 6));
    for (unsigned n = 0; n < 6; ++n) {
        uint32_t mutated[6];
        for (unsigned j = 0; j < 6; ++j) mutated[j] = bionic[j];
        mutated[n] ^= 1;
        assert(!container_exec::isExecSyscallEntry(mutated, 6));
    }
    uint32_t moved_errno[6];
    for (unsigned n = 0; n < 6; ++n) moved_errno[n] = bionic[n];
    moved_errno[4] = 0x54000108;
    assert(container_exec::isExecSyscallEntry(moved_errno, 6));
    puts("exec entry: complete veneer required; unrelated syscalls and short wrappers rejected");
}
