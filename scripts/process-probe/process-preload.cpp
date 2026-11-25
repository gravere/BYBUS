#include <jni.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>
#include "ExecEnvironment.h"
#include "ExecLaunchTrace.h"
#include "SandboxFs.h"
#include "LimbusProcessScope.h"
static const limbus_process::Scope process_scope;
static auto orig_syscall = syscall;
#define HOOK_DEF(ret,name,...) ret new_##name(__VA_ARGS__)
#include "process-under-test.inc"

static bool enabled;
static bool locked;
static int prepares;
static pthread_mutex_t callback_lock=PTHREAD_MUTEX_INITIALIZER;
static void prepare() {
    __sync_add_and_fetch(&prepares,1);
    if(locked) {
        static const char message[]="probe: unexpected atfork preparation entered\n";
        syscall(__NR_write,2,message,sizeof(message)-1);
        pthread_mutex_lock(&callback_lock);
        pthread_mutex_unlock(&callback_lock);
    }
}
extern "C" JNIEXPORT void JNICALL Java_ProcessLaunchTest_configure(JNIEnv*,jclass,jboolean fault) {
    add_replace_item("/bybus-probe/sh","/system/bin/sh");
    add_replace_item("/bybus-probe/work/","/data/local/tmp/");
    add_forbidden_item("/bybus-probe/denied");
    setenv("V_SO_PATH_64","/system/lib64/libdl.so",1);
    setenv("V_SO_PATH","/system/lib/libdl.so",1);
    pthread_atfork(prepare,nullptr,nullptr);
    locked=fault;
    if(locked)pthread_mutex_lock(&callback_lock);
    enabled=true;
}
extern "C" JNIEXPORT jint JNICALL Java_ProcessLaunchTest_forkCallbacks(JNIEnv*,jclass) {return prepares;}
extern "C" JNIEXPORT void JNICALL Java_ProcessLaunchTest_finish(JNIEnv*,jclass) {
    enabled=false;if(locked){locked=false;pthread_mutex_unlock(&callback_lock);}
}
extern "C" int execve(const char *path,char *const argv[],char *const envp[]) {
    if(!enabled)return orig_syscall(__NR_execve,path,argv,envp);
    // This is the same PID check used by the container's process-only hooks.
    // Native vfork must identify the child without mutating the parent's owner.
    if(process_scope.isOwner()){errno=EFAULT;return -1;}
    return new_execve(path,const_cast<char **>(argv),envp);
}
extern "C" int chdir(const char *path) {
    return enabled?new_chdir(path):orig_syscall(__NR_chdir,path);
}
#ifdef TEST_BASELINE_VFORK
extern "C" pid_t vfork() {return new_vfork();}
#endif
