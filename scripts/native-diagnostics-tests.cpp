#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusMappedIndex.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusTerminationTrace.h"
#include <cassert>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <sys/wait.h>

static std::string read(const char *name) {
    std::ifstream file(name, std::ios::binary);
    return {std::istreambuf_iterator<char>(file), {}};
}
static void write(const char *name, const std::string &data) {
    std::ofstream out(name, std::ios::binary | std::ios::trunc);
    out.write(data.data(), data.size());
    assert(out.good());
}
int main() {
    char directory[] = "/data/local/tmp/bybus-diag-XXXXXX";
    assert(mkdtemp(directory));
    assert(chdir(directory) == 0);
    const std::string original("\0\0\0\3old", 7);
    write("index.bin", original);
    auto file = limbus_index::File::open("index.bin"); assert(file);
    limbus_index::Reader reader(file);
    std::string_view view;
    assert(read_string(reader, &view) && view == "old" && reader.at_end());
    assert(view.data() == file->data() + 4); // no owning copy
    write("next.bin", std::string("\0\0\0\3new", 7));
    assert(rename("next.bin", "index.bin") == 0);
    assert(view == "old"); // existing readers own the old inode
    limbus_index::Reader next(limbus_index::File::open("index.bin"));
    std::string value; assert(read_string(next, &value) && value == "new");
    write("bad.bin", std::string("\x7f\xff\xff\xffx", 5));
    limbus_index::Reader bad(limbus_index::File::open("bad.bin"));
    assert(!read_string(bad, &value));
    write("short.bin", std::string("\0\0\0\5x", 5));
    limbus_index::Reader short_file(limbus_index::File::open("short.bin"));
    assert(!read_string(short_file, &value));
    assert(!limbus_index::File::open("."));
    assert(symlink("index.bin", "link.bin") == 0);
    assert(!limbus_index::File::open("link.bin"));

    const pid_t child = fork(); assert(child >= 0);
    if (child == 0) {
        limbus_termination::Trace trace;
        assert(trace.open("termination.log"));
        errno = EAGAIN;
        trace.record(limbus_termination::Kill, SIGKILL, getpid(), 123, 0);
        assert(errno == EAGAIN);
        ::kill(getpid(), SIGKILL);
        _exit(99);
    }
    int status = 0; assert(waitpid(child, &status, 0) == child);
    assert(WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
    const auto persisted = read("termination.log");
    assert(persisted.size() == 2 * 384 && persisted.find("event=1 value=9") != std::string::npos);
    assert(persisted.find("target=" + std::to_string(child)) != std::string::npos);

    limbus_termination::Trace bounded; assert(bounded.open("bounded.log"));
    for (int n = 0; n < 10000; ++n) bounded.record(limbus_termination::Kill, SIGTERM, 1, 0, 0);
    bounded.record(limbus_termination::Exit, 19, 1, 0, 2);
    const auto limited = read("bounded.log");
    assert(limited.size() == 10 * 384 && limited.find("event=6 value=19") != std::string::npos);
    for (int n = 0; n < 100; ++n) bounded.record(limbus_termination::Fault, SIGSEGV, 1, 0, -1);
    bounded.record(limbus_termination::UnhandledFault, SIGSEGV, 1, 456, 3);
    assert(read("bounded.log").find("event=9 value=11 target=1 caller=456 disposition=3") != std::string::npos);
    for (const char *name : {"index.bin", "bad.bin", "short.bin", "link.bin", "termination.log", "bounded.log"}) unlink(name);
    assert(chdir("/") == 0); rmdir(directory);
    puts("mapped index atomic replacement/bounds passed; native evidence survived SIGKILL; errno and event limits passed");
}
