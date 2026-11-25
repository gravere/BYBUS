#pragma once

#include <cstddef>
#include <cstdint>

namespace container_exec {

// Bionic may inline its exported execve wrapper inside execvp/execvpe. Match
// the complete ARM64 kernel veneer in libc's RX segment, not a fixed offset or
// an arbitrary SVC inside another function. Six instructions leave room for
// both aligned and unaligned Substrate entries.
inline bool isExecSyscallEntry(const uint32_t *code, size_t words) {
    return code && words >= 6
        && code[0] == 0xd2801ba8u // mov x8, #__NR_execve (221)
        && code[1] == 0xd4000001u // svc #0
        && code[2] == 0xb140041fu // cmn x0, #4096
        && code[3] == 0xda809400u // cneg x0, x0, hi
        && (code[4] & 0xff00001fu) == 0x54000008u // b.hi errno handler
        && code[5] == 0xd65f03c0u; // ret
}

}
