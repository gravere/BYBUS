#include <jni.h>
#include <cassert>
#include <cstring>
#include <cstdio>
#include <cstdarg>
#define ALOGE(...) ((void)0)

jclass found = reinterpret_cast<jclass>(0x1000);
jclass nativeEngineClass = reinterpret_cast<jclass>(0x2000);
jmethodID bridge = reinterpret_cast<jmethodID>(0x3000);
jstring name_string = reinterpret_cast<jstring>(0x4000);
jthrowable original_error = reinterpret_cast<jthrowable>(0x5000);
jthrowable fallback_error = reinterpret_cast<jthrowable>(0x6000);
jthrowable allocation_error = reinterpret_cast<jthrowable>(0x7000);
jthrowable pending = nullptr;
bool primary_ok, fallback_ok, fallback_throws, allocation_fails, original_throws;
int calls, refs;
jclass original(JNIEnv *, const char *) {
    if (primary_ok) return found;
    pending = original_throws ? original_error : nullptr;
    return nullptr;
}
struct { jclass (*orig_findClass)(JNIEnv *, const char *); jmethodID method_onLimbusFindClass; }
        patchEnv{original, bridge};
jthrowable exception(JNIEnv *) { if (pending) ++refs; return pending; }
void clear(JNIEnv *) { pending = nullptr; }
jboolean check(JNIEnv *) { return pending != nullptr; }
jint raise(JNIEnv *, jthrowable error) { assert(!pending); pending = error; return 0; }
void drop(JNIEnv *, jobject value) { assert(value && refs > 0); --refs; }
jstring make_name(JNIEnv *, const char *) {
    assert(!pending);
    if (allocation_fails) { pending = allocation_error; return nullptr; }
    ++refs; return name_string;
}
jobject fallback(JNIEnv *, jclass owner, jmethodID method, va_list arguments) {
    assert(!pending && owner == nativeEngineClass && method == bridge);
    assert(va_arg(arguments, jstring) == name_string);
    ++calls;
    if (fallback_throws) { pending = fallback_error; return nullptr; }
    return fallback_ok ? found : nullptr;
}
#include "runtime-findclass-under-test.inc"
void reset() {
    pending = nullptr; primary_ok = fallback_ok = fallback_throws = allocation_fails = false;
    original_throws = true; calls = refs = 0; patchEnv.method_onLimbusFindClass = bridge;
}
int main() {
    JNINativeInterface table{};
    table.ExceptionOccurred = exception; table.ExceptionClear = clear; table.ExceptionCheck = check;
    table.Throw = raise; table.DeleteLocalRef = drop; table.NewStringUTF = make_name;
    table.CallStaticObjectMethodV = fallback;
    JNIEnv env{&table};
    const char *target = "com/google/firebase/FirebaseApp";
    reset(); primary_ok = true;
    assert(new_limbus_FindClass(&env, target) == found && !pending && !calls && !refs);
    reset();
    assert(!new_limbus_FindClass(&env, "unrelated/Class") && pending == original_error && !calls && !refs);
    reset(); patchEnv.method_onLimbusFindClass = nullptr;
    assert(!new_limbus_FindClass(&env, target) && pending == original_error && !calls && !refs);
    reset(); fallback_ok = true;
    assert(new_limbus_FindClass(&env, target) == found && !pending && calls == 1 && !refs);
    reset();
    assert(!new_limbus_FindClass(&env, target) && pending == original_error && calls == 1 && !refs);
    reset(); fallback_throws = true;
    assert(!new_limbus_FindClass(&env, target) && pending == original_error && calls == 1 && !refs);
    reset(); original_throws = false; fallback_throws = true;
    assert(!new_limbus_FindClass(&env, target) && pending == fallback_error && calls == 1 && !refs);
    reset(); allocation_fails = true;
    assert(!new_limbus_FindClass(&env, target) && pending == allocation_error && !calls && !refs);
    puts("Production FindClass fallback: failure exceptions, successful fallback, allocation failure, target isolation and local references passed");
}
