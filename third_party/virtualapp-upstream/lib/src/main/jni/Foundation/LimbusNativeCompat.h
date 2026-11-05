#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

// IO aliases and context comparison adapted from BBBEANNN/limbusZhCN PR #17.
inline bool limbus_equivalent_io_aliases(const char *a, const char *b) {
    static const char *const groups[][3] = {
        {"open", "open64", nullptr}, {"openat", "openat64", nullptr},
        {"fopen", "fopen64", nullptr}, {"statfs", "statfs64", "__statfs"},
        {"fstatat", "fstatat64", nullptr}, {"sendfile", "sendfile64", nullptr}
    };
    if (!a || !b) return false;
    for (const auto &group : groups) {
        bool first = false, second = false;
        for (auto name : group) if (name) {
            first |= std::strcmp(a, name) == 0;
            second |= std::strcmp(b, name) == 0;
        }
        if (first && second) return true;
    }
    return false;
}

// Caller serializes installation. The installer receives the live original-function slot.
class LimbusIoHookRegistry {
    struct Entry { void *address, *replacement, *original; const char *symbol; };
    Entry entries[128]{};
    size_t count = 0;
public:
    template<class Installer>
    bool install(void *address, const char *symbol, void *replacement,
                 void **original, Installer installer) {
        if (!address || !replacement || !original || !symbol) return false;
        for (size_t i = 0; i < count; ++i) if (entries[i].address == address) {
            const auto &entry = entries[i];
            if (entry.replacement != replacement && !limbus_equivalent_io_aliases(entry.symbol, symbol))
                return false;
            *original = entry.original;
            return true;
        }
        if (count == sizeof(entries) / sizeof(entries[0])) return false;
        *original = nullptr;
        installer(address, replacement, original);
        if (!*original) return false;
        entries[count++] = {address, replacement, *original, symbol};
        return true;
    }
};

struct LimbusSignalContextSnapshot { uint64_t regs[31], pc, sp, pstate; };
template<class Context>
inline LimbusSignalContextSnapshot limbus_snapshot_context(const Context &context) {
    LimbusSignalContextSnapshot snapshot{};
    for (size_t i = 0; i < 31; ++i) snapshot.regs[i] = context.regs[i];
    snapshot.pc = context.pc; snapshot.sp = context.sp; snapshot.pstate = context.pstate;
    return snapshot;
}
template<class Context>
inline bool limbus_machine_context_changed(const Context &context, const LimbusSignalContextSnapshot &before) {
    if (context.pc != before.pc || context.sp != before.sp || context.pstate != before.pstate) return true;
    for (size_t i = 0; i < 31; ++i) if (context.regs[i] != before.regs[i]) return true;
    return false;
}
