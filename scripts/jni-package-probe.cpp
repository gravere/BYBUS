// Debug fixture: public Android API contract only; no game library or runtime hooks.
#include <jni.h>
#include <pthread.h>

namespace {
struct Query {
    JavaVM *vm;
    jobject context;
    bool flags_api;
    bool applications;
    jint stage = 0;
    jint count = -1;
};

void inspect(JNIEnv *env, Query &query) {
    // Bound the references and leave the outer JNI frame unchanged.
    if (env->PushLocalFrame(32) != JNI_OK) { query.stage = 1; return; }
    do {
        query.stage = 2;
        jclass context_class = env->FindClass("android/content/ContextWrapper");
        if (!context_class) break;
        jmethodID get_pm = env->GetMethodID(context_class, "getPackageManager",
                                          "()Landroid/content/pm/PackageManager;");
        if (!get_pm) break;
        jobject pm = env->CallObjectMethod(query.context, get_pm);
        if (!pm || env->ExceptionCheck()) break;
        query.stage = 3;
        jclass pm_class = env->FindClass("android/content/pm/PackageManager");
        if (!pm_class) break;
        const char *method_name = query.applications ? "getInstalledApplications" : "getInstalledPackages";
        const char *signature = !query.flags_api ? "(I)Ljava/util/List;" : query.applications
                ? "(Landroid/content/pm/PackageManager$ApplicationInfoFlags;)Ljava/util/List;"
                : "(Landroid/content/pm/PackageManager$PackageInfoFlags;)Ljava/util/List;";
        jmethodID method = env->GetMethodID(pm_class, method_name, signature);
        if (!method) break;
        jobject flags = nullptr;
        if (query.flags_api) {
            query.stage = 4;
            jclass flag_class = env->FindClass(query.applications
                ? "android/content/pm/PackageManager$ApplicationInfoFlags"
                : "android/content/pm/PackageManager$PackageInfoFlags");
            if (!flag_class) break;
            jmethodID of = env->GetStaticMethodID(flag_class, "of", query.applications
                ? "(J)Landroid/content/pm/PackageManager$ApplicationInfoFlags;"
                : "(J)Landroid/content/pm/PackageManager$PackageInfoFlags;");
            if (!of) break;
            flags = env->CallStaticObjectMethod(flag_class, of, static_cast<jlong>(128));
            if (!flags || env->ExceptionCheck()) break;
        }
        query.stage = 5;
        jobject list = query.flags_api ? env->CallObjectMethod(pm, method, flags)
                                      : env->CallObjectMethod(pm, method, static_cast<jint>(128));
        if (!list || env->ExceptionCheck()) break;
        query.stage = 6;
        jclass list_class = env->FindClass("java/util/List");
        if (!list_class) break;
        jmethodID size = env->GetMethodID(list_class, "size", "()I");
        if (!size) break;
        query.count = env->CallIntMethod(list, size);
        if (env->ExceptionCheck() || query.count < 0 || query.count > 32) break;
        query.stage = 7;
        jmethodID get = env->GetMethodID(list_class, "get", "(I)Ljava/lang/Object;");
        if (!get) break;
        jclass entry_class = env->FindClass(query.applications ? "android/content/pm/ApplicationInfo"
                                                              : "android/content/pm/PackageInfo");
        if (!entry_class) break;
        jfieldID package_name = env->GetFieldID(entry_class, "packageName", "Ljava/lang/String;");
        if (!package_name) break;
        bool valid = true;
        for (jint i = 0; i < query.count; ++i) {
            jobject item = env->CallObjectMethod(list, get, i);
            if (!item || env->ExceptionCheck() || !env->IsInstanceOf(item, entry_class)) { valid = false; break; }
            jstring name = static_cast<jstring>(env->GetObjectField(item, package_name));
            if (!name || env->ExceptionCheck() || env->GetStringLength(name) == 0) { valid = false; break; }
            env->DeleteLocalRef(name);
            env->DeleteLocalRef(item);
        }
        if (valid && !env->ExceptionCheck()) query.stage = 0;
    } while (false);
    // Return an explicit failing stage. This fixture never clears a game's exception.
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->PopLocalFrame(nullptr);
}

void *run_attached(void *argument) {
    auto &query = *static_cast<Query *>(argument);
    JNIEnv *env = nullptr;
    if (query.vm->AttachCurrentThread(&env, nullptr) != JNI_OK) { query.stage = 8; return nullptr; }
    inspect(env, query);
    if (query.vm->DetachCurrentThread() != JNI_OK) query.stage = 9;
    return nullptr;
}
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_lody_virtual_client_hook_proxies_pm_NativePackageQueryProbe_query(
        JNIEnv *env, jclass, jobject context, jboolean typed, jboolean applications, jboolean attached) {
    Query query{};
    if (env->GetJavaVM(&query.vm) != JNI_OK) return nullptr;
    query.flags_api = typed;
    query.applications = applications;
    query.context = env->NewGlobalRef(context);
    if (!query.context) return nullptr;
    if (attached) {
        pthread_t thread;
        if (pthread_create(&thread, nullptr, run_attached, &query) != 0) query.stage = 10;
        else if (pthread_join(thread, nullptr) != 0) query.stage = 11;
    } else inspect(env, query);
    env->DeleteGlobalRef(query.context);
    jintArray result = env->NewIntArray(2);
    if (result) {
        jint values[] = {query.stage, query.count};
        env->SetIntArrayRegion(result, 0, 2, values);
    }
    return result;
}
