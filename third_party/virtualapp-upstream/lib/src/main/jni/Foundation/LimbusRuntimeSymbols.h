#pragma once

#include <string.h>

namespace limbus_runtime {
struct ModuleOwner {
    const void *base;
    const char *path;
    ModuleOwner(const void *base = nullptr, const char *path = nullptr) : base(base), path(path) {}
};
using Lookup = void *(*)(void *, const char *);
using Locate = bool (*)(const void *, ModuleOwner &);

inline bool is_il2cpp(const ModuleOwner &owner) {
    if (!owner.base || !owner.path) return false;
    const char *name = strrchr(owner.path, '/');
    return strcmp(name ? name + 1 : owner.path, "libil2cpp.so") == 0;
}

// Borrow Unity's actual handle across linker namespaces. No dlopen, path lookup,
// ELF section-table assumptions or ownership transfer of that handle.
class Symbols {
public:
    bool bind(void *handle, const void *init, Lookup lookup, Locate locate) {
        ModuleOwner owner;
        if (!init || !lookup || !locate || !locate(init, owner) || !is_il2cpp(owner) ||
            (base_ && base_ != owner.base)) return false;
        handle_ = handle;
        base_ = owner.base;
        lookup_ = lookup;
        locate_ = locate;
        return true;
    }

    template <typename T> T resolve(const char *name) const {
        if (!base_ || !name || strncmp(name, "il2cpp_", 7) || !name[7]) return nullptr;
        void *symbol = lookup_(handle_, name);
        ModuleOwner owner;
        return symbol && locate_(symbol, owner) && owner.base == base_ && is_il2cpp(owner)
                ? reinterpret_cast<T>(symbol) : nullptr;
    }
    bool ready() const { return base_ != nullptr; }
private:
    void *handle_ = nullptr;
    const void *base_ = nullptr;
    Lookup lookup_ = nullptr;
    Locate locate_ = nullptr;
};
} // namespace limbus_runtime
