// Executes the unmodified production wrapper against the Android kernel. A held
// simulated file-hook lock makes any pre-exec stdio re-entry fail deterministically.
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#include "ExecEnvironment.h"
#include "ExecLaunchTrace.h"
#include "SandboxFs.h"

static pthread_mutex_t hook_lock = PTHREAD_MUTEX_INITIALIZER;
static pid_t parent_pid;
static FILE *hooked_fopen(const char *path, const char *mode) {
    if (getpid() != parent_pid) pthread_mutex_lock(&hook_lock);
    return fopen(path, mode);
}
static auto orig_syscall = syscall;
#define HOOK_DEF(ret, name, ...) ret new_##name(__VA_ARGS__)
#define fopen hooked_fopen
#include "exec-under-test.inc"
#undef fopen

static const char *value(char *const *values, const char *name) {
    const size_t n = strlen(name);
    for (size_t i = 0; values && values[i]; ++i)
        if (!strncmp(values[i], name, n) && values[i][n] == '=') return values[i] + n + 1;
    return nullptr;
}
static void environment_cases() {
    char *inherited[] = {const_cast<char *>("V_SO_PATH=/32.so"),const_cast<char *>("V_SO_PATH_64=/64.so"),
        const_cast<char *>("V_API_LEVEL=35"),const_cast<char *>("V_KEEP_ITEM_0=/system/"),
        const_cast<char *>("V_FORBID_ITEM_0=/denied"),const_cast<char *>("V_REPLACE_ITEM_SRC_0=/virtual/"),
        const_cast<char *>("V_REPLACE_ITEM_DST_0=/actual/"),const_cast<char *>("PRIVATE_SENTINEL=no-export"),nullptr};
    char *requested[] = {const_cast<char *>("REQUEST=kept"),const_cast<char *>("LD_PRELOAD=/caller.so"),
        const_cast<char *>("V_API_LEVEL=caller"),nullptr};
    container_exec::Environment env;
    for (int elf : {1,2}) {
        assert(env.build(requested,inherited,elf));
        assert(!strcmp(value(env.values(),"LD_PRELOAD"),elf==1?"/32.so:/caller.so":"/64.so:/caller.so"));
        assert(!strcmp(value(env.values(),"REQUEST"),"kept"));
        assert(!strcmp(value(env.values(),"V_API_LEVEL"),"caller"));
        assert(!strcmp(value(env.values(),"V_KEEP_ITEM_0"),"/system/"));
        assert(!strcmp(value(env.values(),"V_FORBID_ITEM_0"),"/denied"));
        assert(!strcmp(value(env.values(),"V_REPLACE_ITEM_DST_0"),"/actual/"));
        assert(!value(env.values(),"PRIVATE_SENTINEL"));
    }
    assert(env.build(nullptr,inherited,2));
    assert(!strcmp(value(env.values(),"LD_PRELOAD"),"/64.so"));
    assert(env.build(requested,inherited,0));
    assert(!strcmp(value(env.values(),"LD_PRELOAD"),"/caller.so"));
    assert(!value(env.values(),"V_SO_PATH_64"));
    char oversized[PATH_MAX*2+32]; memset(oversized,'x',sizeof(oversized));
    memcpy(oversized,"LD_PRELOAD=",11); oversized[sizeof(oversized)-1]=0;
    char *large[]={oversized,nullptr};
    assert(!env.build(large,inherited,2) && errno==E2BIG);
    char *many[4098]; for (int i=0;i<4097;++i) many[i]=requested[0]; many[4097]=nullptr;
    assert(!env.build(many,inherited,2) && errno==E2BIG);
}
static void *hold_lock(void *arg) {
    int *fds=static_cast<int *>(arg);
    pthread_mutex_lock(&hook_lock);
    assert(write(fds[1],"r",1)==1);
    char release; assert(read(fds[2],&release,1)==1);
    pthread_mutex_unlock(&hook_lock);
    return nullptr;
}
static int await_child(pid_t pid) {
    for(int i=0;i<100;++i) {
        int status; pid_t result=waitpid(pid,&status,WNOHANG);
        if(result==pid) return WIFEXITED(status)?WEXITSTATUS(status):128+WTERMSIG(status);
        assert(result==0); usleep(20000);
    }
    kill(pid,SIGKILL); int status; waitpid(pid,&status,0); return 124;
}
int main(int argc,char **argv) {
    if(argc==2 && !strcmp(argv[1],"--child")) {
        assert(getenv("REQUEST") && !strcmp(getenv("REQUEST"),"kept"));
        assert(getenv("V_REPLACE_ITEM_SRC_0") && !strcmp(getenv("V_REPLACE_ITEM_SRC_0"),"/virtual/bin/worker"));
        assert(getenv("V_REPLACE_ITEM_DST_0") && !strcmp(getenv("V_REPLACE_ITEM_DST_0"),argv[0]));
        assert(!getenv("PRIVATE_SENTINEL"));
        return 37;
    }
    assert(argc==1); parent_pid=getpid();
    environment_cases();
    setenv("V_SO_PATH_64","/system/lib64/libdl.so",1);
    setenv("V_SO_PATH","/system/lib/libdl.so",1);
    setenv("V_API_LEVEL","35",1);setenv("V_PREVIEW_API_LEVEL","0",1);setenv("V_NATIVE_PATH","/data/local/tmp",1);
    setenv("PRIVATE_SENTINEL","no-export",1);
    add_replace_item("/virtual/bin/worker",argv[0]);
    add_replace_item("/virtual/root/","/data/local/tmp/");
    add_forbidden_item("/virtual/denied");
    char buffer[PATH_MAX];
    assert(relocate_path("/virtual/root",buffer,sizeof(buffer))==buffer);
    assert(!strcmp(buffer,"/data/local/tmp"));
    char tiny[2];assert(!relocate_path("/virtual/root",tiny,sizeof(tiny)));
    char *requested[]={const_cast<char *>("REQUEST=kept"),nullptr};
    char *args[]={argv[0],const_cast<char *>("--child"),nullptr};
    // A real second thread holds the simulated hook lock throughout each fork.
    int ready[2],release[2];assert(!pipe(ready)&&!pipe(release));
    int channels[]={ready[0],ready[1],release[0],release[1]};pthread_t holder;
    assert(!pthread_create(&holder,nullptr,hold_lock,channels));char byte;assert(read(ready[0],&byte,1)==1);
    pid_t children[3];
    for(int i=0;i<3;++i) {
        children[i]=fork();assert(children[i]>=0);
        if(!children[i]) {new_execve("/virtual/bin/worker",args,requested);_exit(90);}
    }
    int status=0;for(pid_t pid:children) {int got=await_child(pid);if(got!=37)status=got;}
    assert(write(release[1],"x",1)==1);assert(!pthread_join(holder,nullptr));
    if(status){printf("exec probe failed: child status=%d (124=pre-exec lock timeout)\n",status);return status;}
    assert(new_execve("/bybus-missing-executable",args,requested)==-1 && errno==ENOENT);
    assert(new_execve("/virtual/denied",args,requested)==-1 && errno==EACCES);
    // A non-executable FIFO must fail like execve, never block probing its header.
    int fifo[2];assert(!pipe(fifo));
    char fifoPath[80];snprintf(fifoPath,sizeof(fifoPath),"/proc/self/fd/%d",fifo[0]);
    assert(new_execve(fifoPath,args,requested)==-1 && errno==EACCES);
    close(fifo[0]);close(fifo[1]);
    // Match the Java error-pipe contract: failure is returned to the parent.
    int failure[2];assert(!pipe2(failure,O_CLOEXEC));pid_t failed=fork();assert(failed>=0);
    if(!failed) {
        close(failure[0]);new_execve("/bybus-missing-executable",args,requested);
        int error=errno;assert(write(failure[1],&error,sizeof(error))==sizeof(error));_exit(0);
    }
    close(failure[1]);assert(await_child(failed)==0);int error;
    assert(read(failure[0],&error,sizeof(error))==sizeof(error)&&error==ENOENT);close(failure[0]);
    puts("exec: concurrent fork, environment, routing, forbidden path, kernel errno, bounds, FIFO, failure handshake passed");
}
