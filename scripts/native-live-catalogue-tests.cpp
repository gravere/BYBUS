#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFontProof.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <unordered_map>
#include <vector>
#ifdef _WIN32
#include <winsock2.h>
#pragma comment(lib, "Ws2_32.lib")
#else
#include <arpa/inet.h>
#endif
const uint32_t kMaxEntries = 1000000, kMaxStringBytes = 16 * 1024 * 1024;
std::string g_active_pointer;
std::unordered_map<std::string, std::string> g_index, g_model_fields;
limbus_font_proof::Catalogue g_font_catalogue;
size_t loaded_labels = 0;
void rebuild_package_line_break_index(const std::unordered_map<std::string, std::string> &values) {
    loaded_labels = values.size();
}
#define LT_LOGI(...) ((void)0)
#include "runtime-live-catalogue-under-test.inc"
int main(int argc, char **argv) {
    assert(argc == 4); // production writer output, model count, label count
    const std::string input = argv[1];
    std::ifstream stream(input, std::ios::binary);
    std::vector<char> bytes((std::istreambuf_iterator<char>(stream)), {});
    assert(bytes.size() > 14);
    g_active_pointer = "catalogue-roundtrip-active.json";
    // Keep corruption tests in the test working directory; never alter the source.
    auto write = [&](size_t count) {
        std::ofstream out("live-fonts.bin", std::ios::binary | std::ios::trunc);
        out.write(bytes.data(), count);
    };
    write(bytes.size());
    assert(load_live_font_catalogue());
    assert(g_model_fields.size() == std::stoul(argv[2]));
    assert(loaded_labels == std::stoul(argv[3]));
    const auto original = g_model_fields;
    // A truncated, old-schema or trailing-byte file cannot publish a partial map.
    write(bytes.size() - 1); assert(!load_live_font_catalogue()); assert(g_model_fields == original);
    bytes[4] = '1'; write(bytes.size()); assert(!load_live_font_catalogue());
    bytes[4] = '2'; bytes.push_back(0); write(bytes.size()); assert(!load_live_font_catalogue());
    assert(g_model_fields == original);
    std::remove("live-fonts.bin");
    printf("Production live catalogue: %zu model fields, %zu labels; Kotlin/native roundtrip and corruption rejection passed\n",
           g_model_fields.size(), loaded_labels);
}
