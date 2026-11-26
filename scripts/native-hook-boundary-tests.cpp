#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <vector>

using tmp_set_text_fn = void (*)(void *, void *, const void *);
tmp_set_text_fn g_original_tmp_set_text = nullptr;
int hook_calls = 0;
bool hook_succeeds = true;
void replacement_tmp_set_text(void *, void *, const void *) {}
void original_trampoline(void *, void *, const void *) {}
void hook_limbus_owned_function(void *, void *, void **original) {
    ++hook_calls;
    if (hook_succeeds) *original = reinterpret_cast<void *>(original_trampoline);
}
#define LT_LOGW(...) ((void)0)

bool bind(const std::vector<uintptr_t> &managed_method_addresses, uintptr_t address,
          bool have_class = true) {
    void *tmp_text_class = have_class ? reinterpret_cast<void *>(1) : nullptr;
    void *tmp_pointer = reinterpret_cast<void *>(address);
    g_original_tmp_set_text = nullptr;
    hook_calls = 0;
    // Production bounds check and binding, not a second implementation.
#include "runtime-core-text-binding.inc"
    return core_setter_hooked;
}

int main() {
    assert(bind({0x1000, 0x1010}, 0x1000));
    assert(hook_calls == 1 && g_original_tmp_set_text == original_trampoline);
    assert(bind({0x1004, 0x1018}, 0x1004));
    for (const auto &entries : {std::vector<uintptr_t>{0x1000, 0x1004},
            std::vector<uintptr_t>{0x1000, 0x1008}, std::vector<uintptr_t>{0x1000}}) {
        assert(!bind(entries, 0x1000));
        assert(hook_calls == 0);
        assert(reinterpret_cast<void *>(g_original_tmp_set_text) == reinterpret_cast<void *>(0x1000));
    }
    assert(!bind({0x1004, 0x1014}, 0x1004)); // unaligned entry needs 20 bytes
    assert(hook_calls == 0);
    assert(!bind({0x1000, 0x1020}, 0x1004)); // not a verified entry
    assert(!bind({0x1000, 0x1020}, 0x1000, false));
    assert(!bind({0x1000, 0x1020}, 0));
    assert(g_original_tmp_set_text == nullptr);
    hook_succeeds = false;
    assert(!bind({0x1000, 0x1010}, 0x1000));
    assert(hook_calls == 1 && g_original_tmp_set_text == nullptr);
    std::puts("PASS core text bounds and unhooked setter fallback");
}
