#include "LimbusCodeIntegrityCompat.h"
#include "LimbusOwnedCode.h"
#include "LimbusReportBridge.h"
#include "LimbusTranslationRuntime.h"
#include "LimbusIntegrityLayout.h"
#include "../Substrate/SubstrateHook.h"
#include "../Jni/VAJni.h"

#include <algorithm>
#include <atomic>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <vector>
#include <elf.h>

namespace {
constexpr size_t kMaxPatches = 512;
using limbus_owned_code::Patch;
pthread_mutex_t ledger_lock = PTHREAD_MUTEX_INITIALIZER;
std::vector<Patch> patches;
uintptr_t library_base = 0;
bool ledger_invalid = false;
std::vector<Patch> protection_patches;
uintptr_t protection_library_base = 0;
bool protection_ledger_invalid = false;
uintptr_t report_base = 0;
using Detach = void (*)();
Detach original_detach = nullptr;
void *original_report = nullptr;
using IntegrityLayout = LimbusIntegrityLayout;
const IntegrityLayout *integrity_layout() {
    static constexpr IntegrityLayout old_build{0x3dc69d0, 0x437e404, 0x5b7a34, 0x5544508, 0x65c447b9u, 0xf218ebc2u, 0x6430d800u};
    static constexpr IntegrityLayout current_build{0x3e56e50, 0x44158cc, 0x5bea7c, 0x564dbe4, 0xda9e4db3u, 0x0f8f19a2u, 0x9a237967u};
    // Captured expected values independently matched both normalized full
    // sections and all 179 protection ranges on the exact hash-qualified 478.
    static constexpr IntegrityLayout build478{0x4049e40, 0x462f79c, 0x5e595c, 0x59d24b0, 0xb913d99fu, 0xbfb79f2du, 0xb14fa8aeu};
    const char *profile = getenv("V_LIMBUS_NATIVE_PROFILE");
    if (!profile) return nullptr;
    if (strcmp(profile, "limbus-engine1-sdk36-arm64-full") == 0) {
        static const IntegrityLayout automatic = [] {
            IntegrityLayout value;
            limbus_parse_integrity_layout(getenv("V_LIMBUS_NATIVE_LAYOUT"), value);
            return value;
        }();
        return automatic.original_expectations ? &automatic : nullptr;
    }
    if (strcmp(profile, "limbus-471-sdk36-arm64-full") == 0) return &old_build;
    if (strcmp(profile, "limbus-475-sdk36-arm64-full") == 0) return &current_build;
    if (strcmp(profile, "limbus-478-sdk36-arm64-full") == 0) return &build478;
    return nullptr;
}
bool profile_matches() {
    return integrity_layout() != nullptr;
}
bool has_stable_report_bridge() {
    const char *profile = getenv("V_LIMBUS_NATIVE_PROFILE");
    return profile && (strcmp(profile, "limbus-475-sdk36-arm64-full") == 0
                       || strcmp(profile, "limbus-478-sdk36-arm64-full") == 0
                       || has_limbus_structural_profile());
}

// Read only the mapped ELF header. No loader enumeration/re-entry from dlopen hooks.
bool mapped_range(uintptr_t base, uintptr_t offset, size_t size, unsigned flags) {
    if (!base || offset > UINTPTR_MAX - base || size > UINTPTR_MAX - base - offset) return false;
    const auto *elf = reinterpret_cast<const Elf64_Ehdr *>(base);
    if (memcmp(elf->e_ident, ELFMAG, SELFMAG) || elf->e_ident[EI_CLASS] != ELFCLASS64
            || elf->e_ident[EI_DATA] != ELFDATA2LSB || elf->e_machine != EM_AARCH64
            || elf->e_phentsize != sizeof(Elf64_Phdr) || !elf->e_phnum || elf->e_phnum > 64
            || elf->e_phoff > 4096 || elf->e_phnum * sizeof(Elf64_Phdr) > 4096 - elf->e_phoff)
        return false;
    const auto *headers = reinterpret_cast<const Elf64_Phdr *>(base + elf->e_phoff);
    for (unsigned i = 0; i < elf->e_phnum; ++i) {
        const auto &h = headers[i];
        if (h.p_type == PT_LOAD && (h.p_flags & flags) == flags && offset >= h.p_vaddr
                && offset - h.p_vaddr <= h.p_memsz && size <= h.p_memsz - (offset - h.p_vaddr))
            return true;
    }
    return false;
}

// Each outcome is logged at most once per scope, even during periodic checks.
void record_integrity_verdict(bool protection, bool accepted) {
    static std::atomic<unsigned> reported{0};
    unsigned bit = 1u << ((protection ? 2 : 0) + (accepted ? 1 : 0));
    if ((reported.fetch_or(bit, std::memory_order_relaxed) & bit) == 0) {
        __android_log_print(ANDROID_LOG_INFO, "LimbusIntegrity",
                "scope=%s owned_changes_only=%d", protection ? "protection" : "il2cpp", accepted);
        record_limbus_integrity_diagnostic(protection, accepted);
    }
}

bool owned_changes_only_locked(uint32_t expected_text, uint32_t expected_managed) {
    const auto *layout = integrity_layout();
    if (!layout || ledger_invalid || patches.empty() || !library_base) return false;
    if (!mapped_range(library_base, layout->text_offset, layout->text_size, PF_R | PF_X)
            || !mapped_range(library_base, layout->managed_offset, layout->managed_size, PF_R | PF_X))
        return false;
    std::sort(patches.begin(), patches.end(), [](const Patch &a, const Patch &b) {
        return a.offset < b.offset;
    });
    size_t owned = 0;
    uint32_t text_crc = 0, managed_crc = 0;
    bool valid = limbus_owned_code::section_crc(
            reinterpret_cast<const unsigned char *>(library_base + layout->text_offset),
            layout->text_offset, layout->text_size, patches, text_crc, owned)
            && limbus_owned_code::section_crc(
            reinterpret_cast<const unsigned char *>(library_base + layout->managed_offset),
            layout->managed_offset, layout->managed_size, patches, managed_crc, owned);
    static std::atomic<bool> recorded{false};
    if (!recorded.exchange(true, std::memory_order_relaxed)) {
        char detail[192];
        snprintf(detail, sizeof(detail), "il2cpp expected=%08x/%08x computed=%08x/%08x valid=%d owned=%zu/%zu",
                 expected_text, expected_managed, text_crc, managed_crc, valid, owned, patches.size());
        record_limbus_integrity_detail(detail);
    }
    return valid && owned == patches.size() && text_crc == expected_text && managed_crc == expected_managed;
}

bool is_confirmed_record(uintptr_t record, uint32_t &text_crc, uint32_t &managed_crc) {
    const auto *layout = integrity_layout();
    if (!layout) return false;
    pthread_attr_t attr;
    void *stack = nullptr;
    size_t size = 0;
    if (pthread_getattr_np(pthread_self(), &attr) != 0) return false;
    int result = pthread_attr_getstack(&attr, &stack, &size);
    pthread_attr_destroy(&attr);
    uintptr_t begin = reinterpret_cast<uintptr_t>(stack);
    if (result || record < begin || record > begin + size || begin + size - record < 0x5d8) return false;
    const auto *bytes = reinterpret_cast<const unsigned char *>(record);
    // This is a bounded stack record belonging to the verified caller, never an arbitrary argument.
    if (!(memcmp(bytes + 8, "libil2cpp.so", sizeof("libil2cpp.so")) == 0
            && *reinterpret_cast<const uint64_t *>(bytes + 0x320) == layout->text_size
            && *reinterpret_cast<const uint64_t *>(bytes + 0x348) == layout->managed_size)) return false;
    text_crc = *reinterpret_cast<const uint32_t *>(bytes + 0x370);
    managed_crc = *reinterpret_cast<const uint32_t *>(bytes + 0x374);
    // The exact verified caller supplies the game's original expectations. Recompute BOTH
    // complete sections with only registered modifications normalized; never adopt live CRCs.
    return layout->original_expectations ||
            (text_crc == layout->text_crc && managed_crc == layout->managed_crc);
}

} // namespace

bool has_limbus_structural_profile() {
    const auto *layout = integrity_layout();
    return layout && layout->original_expectations;
}

LimbusOwnedCodePatch::LimbusOwnedCodePatch(void *address, size_t size) {
    if (!address || size == 0 || size > sizeof(before_) || !profile_matches()) return;
    Dl_info info = {};
    if (!dladdr(address, &info) || !info.dli_fbase || !info.dli_fname) return;
    const char *name = strrchr(info.dli_fname, '/');
    name = name ? name + 1 : info.dli_fname;
    protection_ = strcmp(name, "libcovault-appsec.so") == 0;
    if (!protection_ && strcmp(name, "libil2cpp.so") != 0) return;
    pthread_mutex_lock(&ledger_lock);
    uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    uintptr_t &owned_base = protection_ ? protection_library_base : library_base;
    bool &invalid = protection_ ? protection_ledger_invalid : ledger_invalid;
    if (!owned_base) owned_base = base;
    else if (owned_base != base) invalid = true;
    address_ = address;
    size_ = size;
    memcpy(before_, address, size);
}

LimbusOwnedCodePatch::~LimbusOwnedCodePatch() {
    if (!address_) return;
    auto &entries = protection_ ? protection_patches : patches;
    bool &invalid = protection_ ? protection_ledger_invalid : ledger_invalid;
    uintptr_t base = protection_ ? protection_library_base : library_base;
    if (memcmp(before_, address_, size_) != 0) {
        Patch patch = {};
        patch.offset = reinterpret_cast<uintptr_t>(address_) - base;
        patch.size = size_;
        memcpy(patch.before, before_, size_);
        memcpy(patch.after, address_, size_);
        for (const Patch &old : entries) {
            if (patch.offset < old.offset + old.size && old.offset < patch.offset + patch.size)
                invalid = true;
        }
        if (entries.size() >= kMaxPatches) invalid = true;
        else entries.push_back(patch);
    }
    pthread_mutex_unlock(&ledger_lock);
}

void hook_limbus_owned_function(void *target, void *replacement, void **original) {
    // Match And64InlineHook's actual write width, including the near-branch case.
    int64_t distance = (reinterpret_cast<int64_t>(replacement) - reinterpret_cast<int64_t>(target)) >> 2;
    size_t width = llabs(distance) < (0x03ffffffu >> 1) ? 4
            : ((reinterpret_cast<uintptr_t>(target) & 7u) ? 20 : 16);
    LimbusOwnedCodePatch patch(target, width);
    MSHookFunction(target, replacement, original);
}

struct LimbusDetachResult { uintptr_t value; uintptr_t continuation; };

extern "C" LimbusReportDecision limbus_report_dispatch_impl(uintptr_t code, uintptr_t caller) {
    if (has_stable_report_bridge()
            && code == 50048 && caller == report_base + 0x4e568) {
        if (limbus_report_matches(code, caller - report_base,
                reinterpret_cast<const uint32_t *>(report_base + 0x4e550), 6)) {
            static std::atomic<bool> reported{false};
            if (!reported.exchange(true, std::memory_order_relaxed))
                __android_log_print(ANDROID_LOG_INFO, "LimbusIntegrity", "Scoped 50048 report compatibility at verified caller");
            return {0, report_base + 0x4e998};
        }
    }
    return {reinterpret_cast<uintptr_t>(original_report), 0};
}


static bool has_current_jni_environment(uintptr_t caller_stack) {
    pthread_attr_t attr;
    void *stack = nullptr;
    size_t size = 0;
    if (pthread_getattr_np(pthread_self(), &attr) != 0) return false;
    int result = pthread_attr_getstack(&attr, &stack, &size);
    pthread_attr_destroy(&attr);
    uintptr_t begin = reinterpret_cast<uintptr_t>(stack);
    if (result || caller_stack < begin || caller_stack > begin + size
            || begin + size - caller_stack < 0x700) return false;
    JNIEnv *current = nullptr;
    return vm && vm->GetEnv(reinterpret_cast<void **>(&current), JNI_VERSION_1_6) == JNI_OK
            && current && *reinterpret_cast<JNIEnv **>(caller_stack + 0x580) == current
            && *reinterpret_cast<const uint32_t *>(caller_stack + 0x2c8) != 0;
}

extern "C" LimbusDetachResult limbus_integrity_detach_impl(
        uintptr_t record, uintptr_t caller, uintptr_t caller_stack) {
    const auto *layout = integrity_layout();
    if (caller == report_base + 0x70b6c && profile_matches()
            && has_current_jni_environment(caller_stack)) {
        int index = *reinterpret_cast<const int *>(caller_stack + 0x2cc);
        int descriptor_index = *reinterpret_cast<const int *>(caller_stack + 0x658);
        uintptr_t module = *reinterpret_cast<const uintptr_t *>(caller_stack + 0x6b8);
        uint32_t expected = *reinterpret_cast<const uint32_t *>(caller_stack + 0x648);
        uintptr_t descriptor = report_base + 0x1c23e0 + 0x2ee0;
        int count = mapped_range(report_base, descriptor - report_base, 0x18 + 179 * 0x18, PF_R)
                ? *reinterpret_cast<const int *>(descriptor + 8) : 0;
        if (index == 0 && descriptor_index == 1 && module == report_base
                && layout && (layout->original_expectations || expected == layout->protection_crc)
                && count == 179 && mapped_range(module, 0, 0x14cdc0, PF_R | PF_X)) {
            std::vector<limbus_owned_code::Range> ranges;
            ranges.reserve(count);
            for (int i = 0; i < count; ++i) {
                uintptr_t item = descriptor + 0x18 + i * 0x18;
                ranges.push_back({*reinterpret_cast<const uintptr_t *>(item),
                        *reinterpret_cast<const uint32_t *>(item + 8)});
            }
            pthread_mutex_lock(&ledger_lock);
            std::sort(protection_patches.begin(), protection_patches.end(),
                    [](const Patch &a, const Patch &b) { return a.offset < b.offset; });
            uint32_t crc = 0;
            size_t substituted = 0;
            bool valid = !protection_ledger_invalid && protection_library_base == module
                    && limbus_owned_code::ranges_crc(reinterpret_cast<const unsigned char *>(module),
                            0x14cdc0, ranges, protection_patches, crc, substituted);
            uint64_t shape = limbus_owned_code::range_fingerprint(ranges);
            pthread_mutex_unlock(&ledger_lock);
            static std::atomic<bool> recorded{false};
            if (!recorded.exchange(true, std::memory_order_relaxed)) {
                char detail[192];
                snprintf(detail, sizeof(detail), "protection expected=%08x computed=%08x valid=%d owned=%zu shape=%016llx",
                         expected, crc, valid, substituted, static_cast<unsigned long long>(shape));
                record_limbus_integrity_detail(detail);
            }
            // The complete 179-entry descriptor fingerprint was captured on this build.
            bool owned = valid && substituted && crc == expected && shape == 0xdd108bf95840fd56ull;
            record_integrity_verdict(true, owned);
            if (owned) return {0, report_base + 0x70ee8};
        }
    }
    uint32_t expected_text = 0, expected_managed = 0;
    if (caller == report_base + 0x700f0 && profile_matches()
            && has_current_jni_environment(caller_stack)
            && is_confirmed_record(record, expected_text, expected_managed)) {
        pthread_mutex_lock(&ledger_lock);
        bool owned = owned_changes_only_locked(expected_text, expected_managed);
        pthread_mutex_unlock(&ledger_lock);
        record_integrity_verdict(false, owned);
        // This failure branch detaches before reporting 30010. Only the fully
        // verified owned changes may rejoin success, while the original env is alive.
        if (owned) return {0, report_base + 0x70464};
    }
    original_detach();
    return {0, 0};
}

#if defined(__aarch64__)
extern "C" __attribute__((naked)) void limbus_integrity_detach_entry() {
    // Capture the caller's record, return address and original stack before C++.
    __asm__ volatile(
            "mov x0, x19\nmov x1, x30\nmov x2, sp\n"
            "stp x29, x30, [sp, #-16]!\nmov x29, sp\n"
            "bl limbus_integrity_detach_impl\n"
            "ldp x29, x30, [sp], #16\n"
            "cmp x1, #0\ncsel x30, x30, x1, eq\nret\n");
}
#endif

void install_limbus_code_integrity_compat(uintptr_t protection_base) {
#if defined(__aarch64__)
    if (!profile_matches() || !protection_base || original_detach
            || !mapped_range(protection_base, 0x17130, 0x135c90, PF_R | PF_X)) return;
    const uint32_t expected[] = {0xd10043ff, 0xa9007bf3, 0xf0000733, 0xf942de73, 0xaa1303e0};
    void *entry = reinterpret_cast<void *>(protection_base + 0xac878);
    if (memcmp(entry, expected, sizeof(expected)) != 0
            || *reinterpret_cast<const uint32_t *>(protection_base + 0x700e4) != 0xb942cbe0
            || *reinterpret_cast<const uint32_t *>(protection_base + 0x700e8) != 0x34000040
            || *reinterpret_cast<const uint32_t *>(protection_base + 0x700ec) != 0x9400f1e3
            || *reinterpret_cast<const uint32_t *>(protection_base + 0x70b68) != 0x9400ef44
            || *reinterpret_cast<const uint32_t *>(protection_base + 0x70ba4) != 0xd63f00a0
            || *reinterpret_cast<const uint32_t *>(protection_base + 0x70ee8) != 0xf9416fe0
            || *reinterpret_cast<const uint32_t *>(protection_base + 0x70eec) != 0xd1266000
            || *reinterpret_cast<const uint32_t *>(protection_base + 0x70128) != 0xd63f00a0
            || *reinterpret_cast<const uint32_t *>(protection_base + 0x70464) != 0x5290d400
            || *reinterpret_cast<const uint32_t *>(protection_base + 0x70468) != 0x72a00020) return;
    report_base = protection_base;
    if (has_stable_report_bridge()) {
        const uint32_t report_entry[] = {0xd12303ff, 0xd10103ff, 0xa90053f3, 0xa9015bf5, 0xa90263f7};
        void *report = reinterpret_cast<void *>(protection_base + 0x22594);
        if (memcmp(report, report_entry, sizeof(report_entry)) == 0)
            hook_limbus_owned_function(report, reinterpret_cast<void *>(limbus_report_dispatch_entry), &original_report);
    }
    hook_limbus_owned_function(entry, reinterpret_cast<void *>(limbus_integrity_detach_entry),
                   reinterpret_cast<void **>(&original_detach));
    __android_log_print(original_detach ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, "LimbusIntegrity",
            "JNI cleanup compatibility hook %s", original_detach ? "installed" : "failed");
#endif
}
