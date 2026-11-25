#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unordered_set>
#include <vector>

struct Field { const char *name, *type; size_t offset; };
struct Method {
    void *pointer;
    const char *name, *signature, *result;
    uint32_t flags = 134;
    bool generic = false;
    void *owner;
};
struct Class { const char *space, *name; std::vector<Field> fields; std::vector<Method *> methods; };
struct TutorialPosition { float x, y, z; };
struct StartTutorialBinding {
    void *owner_class = nullptr, *data_class = nullptr;
    const void *target = nullptr, *key = nullptr, *from_tutorial = nullptr;
} g_start_tutorial;
using TextFn = void (*)(void *, void *, const void *);
using PositionFn = void (*)(void *, void *, TutorialPosition, const void *);
TextFn g_original_start_tutorial_text;
PositionFn g_original_start_tutorial_position;
void replacement_start_tutorial_text(void *, void *, const void *) {}
void replacement_start_tutorial_position(void *, void *, TutorialPosition, const void *) {}
void append_native_metadata(const char *, const char *) {}
void tmp_set(void *, void *, const void *) {}
auto g_original_tmp_set_text = tmp_set;
const void *g_tmp_get_text_info = &g_start_tutorial, *g_tmp_set_text_info = &g_start_tutorial;
int32_t vector_size = 12;
uint32_t vector_alignment = 4;
bool vector_value_type = true;
int32_t value_size(void *, uint32_t *alignment) { *alignment = vector_alignment; return vector_size; }
bool is_value_type(void *) { return vector_value_type; }
size_t field_offset(const void *field) { return static_cast<const Field *>(field)->offset; }
struct Resolver {
    template<class F> F resolve(const char *name) {
        if (!strcmp(name, "il2cpp_class_value_size")) return reinterpret_cast<F>(value_size);
        if (!strcmp(name, "il2cpp_class_is_valuetype")) return reinterpret_cast<F>(is_value_type);
        assert(!strcmp(name, "il2cpp_field_get_offset"));
        return reinterpret_cast<F>(field_offset);
    }
};
uint32_t flags(const void *entry, uint32_t *) { return static_cast<const Method *>(entry)->flags; }
void *owner(const void *entry) { return static_cast<const Method *>(entry)->owner; }
bool generic(const void *entry) { return static_cast<const Method *>(entry)->generic; }
const void *result(const void *entry) { return static_cast<const Method *>(entry)->result; }

void bind(Class &presenter, Class &data, Class &vector, bool space = true, bool occupied = false) {
    g_start_tutorial = {};
    g_original_start_tutorial_text = nullptr;
    g_original_start_tutorial_position = nullptr;
    auto class_from_name = [&](void *, const char *ns, const char *name) -> void * {
        for (auto *candidate : {&presenter, &data})
            if (!strcmp(candidate->space, ns) && !strcmp(candidate->name, name)) return candidate;
        return nullptr;
    };
    void *image = &presenter;
#include "runtime-start-tutorial-classes.inc"
    auto choice_field = [](void *klass, const char *name, const char *type) -> const void * {
        if (klass) for (const auto &field : static_cast<Class *>(klass)->fields)
            if (!strcmp(field.name, name) && !strcmp(field.type, type)) return &field;
        return nullptr;
    };
    auto find_method_by_signature = [](void *klass, const char *name, const char *signature) -> const void * {
        if (klass) for (const auto *method : static_cast<Class *>(klass)->methods)
            if (!strcmp(method->name, name) && !strcmp(method->signature, signature)) return method;
        return nullptr;
    };
    auto method_get_flags = flags;
    auto method_get_class = owner;
    auto method_is_generic = generic;
    auto method_get_return_type = result;
    auto type_get_name = +[](const void *type) -> char * { return strdup(static_cast<const char *>(type)); };
    auto il2cpp_free = free;
    auto class_from_type = +[](const void *type) -> void * { return const_cast<void *>(type); };
    auto method_get_param = [&](const void *, unsigned index) -> const void * { assert(index == 1); return &vector; };
    auto has_inline_hook_space = [&](void *, void *) { return space; };
    std::unordered_set<void *> acquisition_pointers;
    if (occupied) for (auto *method : presenter.methods) acquisition_pointers.insert(method->pointer);
    auto hook_limbus_owned_function = [](void *pointer, void *, void **original) { *original = pointer; };
    Resolver resolver;
#include "runtime-start-tutorial-binding.inc"
}
int main() {
    Class presenter{"StartTutorial", "StartTutorialDescriptionTextObject", {{"tmp_description", "TMPro.TextMeshProUGUI", 24}}, {}};
    Class data{"StartTutorial", "StartTutorialDescriptionTextData", {
        {"<DescriptionKey>k__BackingField", "System.String", 16},
        {"<IsFromTutorial>k__BackingField", "System.Boolean", 32}}, {}};
    Class vector{"UnityEngine", "Vector3", {{"x", "System.Single", 16}, {"y", "System.Single", 20}, {"z", "System.Single", 24}}, {}};
    Method text{reinterpret_cast<void *>(0x1000), "SetText", "StartTutorial.StartTutorialDescriptionTextData", "System.Void", 134, false, &presenter};
    Method positioned{reinterpret_cast<void *>(0x2000), "SetTextAndPosition", "StartTutorial.StartTutorialDescriptionTextData,UnityEngine.Vector3", "System.Void", 134, false, &presenter};
    presenter.methods = {&text, &positioned};
    bind(presenter, data, vector); assert(g_original_start_tutorial_text && g_original_start_tutorial_position);
    presenter.space = ""; bind(presenter, data, vector); assert(!g_original_start_tutorial_text && !g_original_start_tutorial_position); presenter.space = "StartTutorial";
    data.fields[1].type = "System.Int32"; bind(presenter, data, vector); assert(!g_original_start_tutorial_text && !g_original_start_tutorial_position); data.fields[1].type = "System.Boolean";
    for (auto *method : presenter.methods) {
        method->flags |= 0x10; bind(presenter, data, vector); assert(method == &text ? !g_original_start_tutorial_text : !g_original_start_tutorial_position); method->flags = 134;
        method->generic = true; bind(presenter, data, vector); assert(method == &text ? !g_original_start_tutorial_text : !g_original_start_tutorial_position); method->generic = false;
        method->result = "System.String"; bind(presenter, data, vector); assert(method == &text ? !g_original_start_tutorial_text : !g_original_start_tutorial_position); method->result = "System.Void";
        method->owner = &data; bind(presenter, data, vector); assert(method == &text ? !g_original_start_tutorial_text : !g_original_start_tutorial_position); method->owner = &presenter;
    }
    for (auto size : {8, 16}) { vector_size = size; bind(presenter, data, vector); assert(g_original_start_tutorial_text && !g_original_start_tutorial_position); }
    vector_size = 12; vector_alignment = 8; bind(presenter, data, vector); assert(!g_original_start_tutorial_position); vector_alignment = 4;
    vector_value_type = false; bind(presenter, data, vector); assert(!g_original_start_tutorial_position); vector_value_type = true;
    vector.fields[2].offset = 28; bind(presenter, data, vector); assert(!g_original_start_tutorial_position); vector.fields[2].offset = 24;
    bind(presenter, data, vector, false); assert(!g_original_start_tutorial_text && !g_original_start_tutorial_position);
    bind(presenter, data, vector, true, true); assert(!g_original_start_tutorial_text && !g_original_start_tutorial_position);
    bind(presenter, data, vector); assert(g_original_start_tutorial_text && g_original_start_tutorial_position);
    puts("First-battle bindings: class/field/method contracts, Vector3 ABI and duplicate/short entry guards passed");
}
