#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusRuntimeSymbols.h"
#include <cassert>
#include <cstdio>

int init, api, wrong, base, other_base, handle;
const char *module_path = "/data/app/opaque/base.apk!/lib/arm64-v8a/libil2cpp.so";
bool missing = false;
int lookups = 0;
void *lookup(void *borrowed, const char *name) {
    assert(borrowed == &handle);
    ++lookups;
    if (missing) return nullptr;
    if (!strcmp(name, "il2cpp_domain_get")) return &api;
    if (!strcmp(name, "il2cpp_wrong")) return &wrong;
    return nullptr;
}
bool locate(const void *symbol, limbus_runtime::ModuleOwner &owner) {
    if (symbol == &init || symbol == &api) { owner = {&base, module_path}; return true; }
    if (symbol == &wrong) { owner = {&other_base, module_path}; return true; }
    return false;
}
int main() {
    limbus_runtime::Symbols symbols;
    assert(!symbols.ready() && !symbols.resolve<void *>("il2cpp_domain_get"));
    assert(!symbols.bind(&handle, nullptr, lookup, locate));
    module_path = "/data/unreadable/notlibil2cpp.so";
    assert(!symbols.bind(&handle, &init, lookup, locate));
    module_path = "/data/app/opaque/base.apk!/lib/arm64-v8a/libil2cpp.so";
    assert(symbols.bind(&handle, &init, lookup, locate));
    assert(symbols.ready() && symbols.resolve<void *>("il2cpp_domain_get") == &api);
    assert(!symbols.resolve<void *>("il2cpp_wrong"));
    assert(!symbols.bind(&handle, &wrong, lookup, locate));
    assert(symbols.resolve<void *>("il2cpp_domain_get") == &api);
    const int calls = lookups;
    assert(!symbols.resolve<void *>("domain_get") && !symbols.resolve<void *>("il2cpp_") &&
           !symbols.resolve<void *>(nullptr) && calls == lookups);
    missing = true;
    assert(!symbols.resolve<void *>("il2cpp_domain_get"));
    missing = false;
    module_path = "/data/unreadable/alias/libil2cpp.so";
    assert(symbols.resolve<void *>("il2cpp_domain_get") == &api);
    module_path = "/data/app/another/libunity.so";
    assert(!symbols.resolve<void *>("il2cpp_domain_get"));
    puts("Loaded-handle resolution: APK/path aliases, missing exports and foreign modules passed");
}
