#include "RuntimePage.h"
#include "OwnedExecutablePages.h"
#include <unistd.h>

namespace { OwnedExecutablePages<512> owned_executable_pages; }

size_t runtime_page::size() {
    static const size_t cached = [] {
        const long value = sysconf(_SC_PAGESIZE);
        return value > 0 && supported(static_cast<size_t>(value)) ? static_cast<size_t>(value) : 0;
    }();
    return cached;
}

bool runtime_page::register_executable_page(uintptr_t address) {
    return owned_executable_pages.add(address, size());
}
void runtime_page::unregister_executable_page(uintptr_t address) {
    owned_executable_pages.remove(address);
}
bool runtime_page::owns_executable_page(uintptr_t start, uintptr_t end) {
    return owned_executable_pages.contains(start, end, size());
}
