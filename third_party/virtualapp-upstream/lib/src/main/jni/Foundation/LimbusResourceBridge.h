#pragma once
#include <jni.h>

extern JavaVM *vm;
extern jclass nativeEngineClass;

// Unity workers are not necessarily attached to ART. Detach only threads attached here.
class LimbusResourceJniScope {
    bool attached_ = false;
    bool frame_ = false;
    bool previous_exception_ = false;
public:
    JNIEnv *env = nullptr;
    LimbusResourceJniScope() {
        if (!vm) return;
        const jint state = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (state == JNI_EDETACHED) {
            attached_ = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
            if (!attached_) env = nullptr;
        } else if (state != JNI_OK) env = nullptr;
        if (env) {
            previous_exception_ = env->ExceptionCheck();
            if (!previous_exception_) frame_ = env->PushLocalFrame(8) == JNI_OK;
        }
    }
    bool valid() const { return env && frame_ && !env->ExceptionCheck(); }
    ~LimbusResourceJniScope() {
        // Exceptions in the optional resource adapter never poison Unity's JNI state.
        if (env && !previous_exception_ && env->ExceptionCheck()) env->ExceptionClear();
        if (frame_) env->PopLocalFrame(nullptr);
        if (attached_) vm->DetachCurrentThread();
    }
};
