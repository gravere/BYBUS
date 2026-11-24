#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

struct Method { const char *name, *parameters, *result; uint32_t flags; bool generic = false; };
struct Class { Method *method; Class *parent = nullptr; };
const void *find(void *k, const char *name, const char *args) {
    if (!k) return nullptr;
    auto *m = static_cast<Class *>(k)->method;
    return m && !strcmp(m->name, name) && !strcmp(m->parameters, args) ? m : nullptr;
}
uint32_t flags(const void *m, uint32_t *) { return static_cast<const Method *>(m)->flags; }
bool generic(const void *m) { return static_cast<const Method *>(m)->generic; }
const void *result(const void *m) { return static_cast<const Method *>(m)->result; }
char *name(const void *type) { return strdup(static_cast<const char *>(type)); }
void *parent(void *k) { return static_cast<Class *>(k)->parent; }
int main() {
    auto find_method_by_signature = find;
    auto method_get_flags = flags;
    auto method_is_generic = generic;
    auto method_get_return_type = result;
    auto type_get_name = name;
    auto il2cpp_free = free;
    auto class_get_parent = parent;
#include "runtime-method-binding-under-test.inc"
    // Official 494 metadata: IsUsing=150 (public static), LoadLocal=134 (instance).
    Method custom{"IsUsing", "", "System.Boolean", 150};
    Method load{"LoadLocal", "LOCALIZE_LANGUAGE", "System.Void", 134};
    Class custom_class{&custom}, load_class{&load}, derived{nullptr, &load_class};
    // Full name from the official 494 metadata. A valid method contract is not
    // enough if the production class lookup never finds its declaring class.
    void *image = &custom_class;
    void *custom_localize_class = nullptr;
    auto class_from_name = [&](void *actual_image, const char *space, const char *name) -> void * {
        assert(actual_image == image);
        return !strcmp(space, "ProjectMoon.CustomLocalization") && !strcmp(name, "CustomLocalizeManager")
                ? &custom_class : nullptr;
    };
#include "runtime-custom-class-under-test.inc"
    assert(custom_localize_class == &custom_class);
    assert(diagnostic_method(custom_localize_class, "IsUsing", "", "System.Boolean", false, true) == &custom);
    assert(!diagnostic_method(&custom_class, "IsUsing", "", "System.Boolean", true));
    assert(diagnostic_method(&custom_class, "IsUsing", "", "System.Boolean", false, true) == &custom);
    assert(diagnostic_method(&load_class, "LoadLocal", "LOCALIZE_LANGUAGE", "System.Void", false) == &load);
    assert(!diagnostic_method(&load_class, "LoadLocal", "LOCALIZE_LANGUAGE", "System.Void", false, true));
    assert(!diagnostic_method(&derived, "LoadLocal", "LOCALIZE_LANGUAGE", "System.Void", false));
    assert(diagnostic_method(&derived, "LoadLocal", "LOCALIZE_LANGUAGE", "System.Void", true) == &load);
    assert(!diagnostic_method(&load_class, "LoadLocal", "System.Int32", "System.Void", true));
    assert(!diagnostic_method(&custom_class, "IsUsing", "", "System.Int32", false, true));
    custom.generic = true;
    assert(!diagnostic_method(&custom_class, "IsUsing", "", "System.Boolean", false, true));
    puts("Method binding: static/instance contracts, inherited lookup, parameters, return type and generic exclusion passed");
}
