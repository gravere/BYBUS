#pragma once

#include <stddef.h>
#include <stdint.h>

// Records only mutations of the exact profile's two qualified libraries, under one lock.
// The ledger stores bytes, not broad address exemptions.
class LimbusOwnedCodePatch {
public:
    LimbusOwnedCodePatch(void *address, size_t size);
    ~LimbusOwnedCodePatch();
    LimbusOwnedCodePatch(const LimbusOwnedCodePatch &) = delete;
    LimbusOwnedCodePatch &operator=(const LimbusOwnedCodePatch &) = delete;
private:
    void *address_ = nullptr;
    size_t size_ = 0;
    bool protection_ = false;
    unsigned char before_[20] = {};
};

void hook_limbus_owned_function(void *target, void *replacement, void **original);
void install_limbus_code_integrity_compat(uintptr_t protection_base);
// True only after the host qualified the known family and the current ELF layout parsed.
bool has_limbus_structural_profile();
