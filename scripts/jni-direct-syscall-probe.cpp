#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusDirectSyscall.h"
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <limits.h>

// Compile the production wrappers, with logging deliberately changing errno.
static const char *relocate_path(const char *p, char *, size_t) { return p; }
static int redirect_proc_file_guarded(const char *, int, mode_t) { return 0; }
static void limbus_record_localize_open(const char *, const char *, const char *, int, int, void *) { errno = EIO; }
static void limbus_record_localize_read(const char *, int, size_t, ssize_t, off64_t, bool) { errno = EIO; }
#include "direct-wrappers.inc"

extern "C" __attribute__((naked)) long clobber() {
    __asm__ volatile("mov x0, #-2\nmov x1, #90\nmov x8, #91\nmov x9, #92\n"
                     "mov x16, #93\nmov x17, #94\nmovi v0.16b, #0\n"
                     "movi v8.16b, #0\nmovi v31.16b, #0\nmsr nzcv, xzr\nret\n");
}

struct State { uint64_t value[8]; unsigned char vectors[48]; };
extern "C" char raw_probe_begin[], raw_probe_end[];
__asm__(
    ".text\n.balign 4\n.global raw_probe_begin\nraw_probe_begin:\n"
    "mov x21, x30\nsvc #0\n"
    "stp x0, x1, [x19]\nstp x8, x9, [x19, #16]\n"
    "stp x16, x17, [x19, #32]\n"
    "mrs x0, nzcv\nstr x0, [x19, #48]\nsub x0, x30, x21\nstr x0, [x19, #56]\n"
    "str q0, [x19, #64]\nstr q8, [x19, #80]\nstr q31, [x19, #96]\n"
    "mov x30, x21\nret\n.global raw_probe_end\nraw_probe_end:\n");

extern "C" __attribute__((naked)) void capture(void *, State *) {
    __asm__ volatile(
        "stp x19, x20, [sp, #-48]!\nstp x21, x30, [sp, #16]\nstr d8, [sp, #32]\n"
        "mov x20, x0\nmov x19, x1\nmov x1, #11\nmov x8, #18\nmov x9, #19\n"
        "mov x16, #26\nmov x17, #27\nmovi v0.16b, #17\nmovi v8.16b, #34\nmovi v31.16b, #51\n"
        "mov x0, #0xa0000000\nmsr nzcv, x0\nblr x20\n"
        "ldr d8, [sp, #32]\nldp x21, x30, [sp, #16]\nldp x19, x20, [sp], #48\nret\n");
}

static bool preserved(const State &s) {
    const uint64_t expected[] = {static_cast<uint64_t>(-2), 11, 18, 19, 26, 27, 0xa0000000, 0};
    if (memcmp(s.value, expected, sizeof(expected))) return false;
    for (unsigned i = 0; i < 48; ++i) if (s.vectors[i] != 17 * (1 + i / 16)) return false;
    return true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_limbuszhcn_NativeSyscallProbe_check(JNIEnv *env, jclass) {
    const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    auto *memory = static_cast<unsigned char *>(mmap(nullptr, page, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    if (memory == MAP_FAILED) return env->NewStringUTF("mmap failed");
    const size_t source_size = raw_probe_end - raw_probe_begin;
    memcpy(memory, raw_probe_begin, source_size);
    const uintptr_t svc = reinterpret_cast<uintptr_t>(memory + 4), trampoline = reinterpret_cast<uintptr_t>(memory + 256);
    uint32_t branch;
    bool ok = limbus_direct_syscall::build(memory + 256, svc,
        reinterpret_cast<uintptr_t>(&clobber), reinterpret_cast<uintptr_t>(&limbus_direct_syscall::entry))
        && limbus_direct_syscall::branch(svc, trampoline, branch);
    memcpy(memory + 4, &branch, 4);
    __builtin___clear_cache(reinterpret_cast<char *>(memory), reinterpret_cast<char *>(memory + page));
    ok &= mprotect(memory, page, PROT_READ | PROT_EXEC) == 0;
    State current{};
    if (ok) capture(memory, &current);
    ok &= preserved(current);
    // Reproduce the old BL/C trampoline on exactly the same fixture.
    bool legacy_failed = false;
    if (mprotect(memory, page, PROT_READ | PROT_WRITE) == 0) {
        branch |= 0x80000000u;
        memcpy(memory + 4, &branch, 4);
        const uint32_t old[] = {0x58000050, 0xd61f0200};
        memcpy(memory + 256, old, sizeof(old));
        const uintptr_t handler = reinterpret_cast<uintptr_t>(&clobber);
        memcpy(memory + 264, &handler, 8);
        __builtin___clear_cache(reinterpret_cast<char *>(memory), reinterpret_cast<char *>(memory + page));
        if (mprotect(memory, page, PROT_READ | PROT_EXEC) == 0) {
            State legacy{}; capture(memory, &legacy); legacy_failed = !preserved(legacy);
        }
    }
    munmap(memory, page);
    errno = EBUSY;
    char byte;
    bool errors = limbus_direct_read(-1, &byte, 1) == -EBADF && errno == EBUSY
        && limbus_direct_pread64(-1, &byte, 1, 0) == -EBADF && errno == EBUSY
        && limbus_direct_openat(AT_FDCWD, "/bybus-nonexistent-probe-file", O_RDONLY, 0) == -ENOENT && errno == EBUSY;
    int fd = static_cast<int>(limbus_direct_openat(AT_FDCWD, "/dev/zero", O_RDONLY, 0));
    errors &= fd >= 0 && errno == EBUSY;
    if (fd >= 0) { errors &= limbus_direct_read(fd, &byte, 1) == 1 && byte == 0 && errno == EBUSY; close(fd); }
    return env->NewStringUTF(ok && legacy_failed && errors ? "PASS: legacy failure reproduced; GPR/LR/SIMD/NZCV and raw errno preserved" :
        !ok ? "FAIL: machine state" : !legacy_failed ? "FAIL: legacy reproduction" : "FAIL: syscall results");
}
