#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFontProof.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusMappedIndex.h"
#include <algorithm>
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
limbus_index::Table g_index, g_model_fields;
std::shared_ptr<limbus_index::File> g_live_mapping;
std::unordered_map<std::string, std::string> g_package_line_breaks;
limbus_font_proof::Catalogue g_font_catalogue;
#define LT_LOGI(...) ((void)0)
#include "runtime-live-catalogue-under-test.inc"
int main(int argc, char **argv) {
    assert(argc == 4); // production writer output, model count, label count
    const std::string input = argv[1];
    std::ifstream stream(input, std::ios::binary);
    std::vector<char> bytes((std::istreambuf_iterator<char>(stream)), {});
    assert(bytes.size() >= 14);
    g_active_pointer = "catalogue-roundtrip-active.json";
    // Keep corruption tests in the test working directory; never alter the source.
    auto write = [&](size_t count) {
        std::ofstream out("live-fonts.pending", std::ios::binary | std::ios::trunc);
        out.write(bytes.data(), count);
        out.close();
        std::remove("live-fonts.bin");
        assert(std::rename("live-fonts.pending", "live-fonts.bin") == 0);
    };
    write(bytes.size());
    assert(load_live_font_catalogue());
    assert(g_model_fields.size() == std::stoul(argv[2]));
    uint32_t encoded_labels;
    memcpy(&encoded_labels, bytes.data() + 6, sizeof(encoded_labels));
    assert(ntohl(encoded_labels) == std::stoul(argv[3]));
    assert(g_font_catalogue.label_count() > 0);
    const auto original = g_model_fields;
    // A truncated, old-schema or trailing-byte file cannot publish a partial map.
    write(bytes.size() - 1); assert(!load_live_font_catalogue()); assert(g_model_fields == original);
    bytes[4] = '1'; write(bytes.size()); assert(!load_live_font_catalogue());
    bytes[4] = '2'; bytes.push_back(0); write(bytes.size()); assert(!load_live_font_catalogue());
    assert(g_model_fields == original);
    // Cross-input conflicts and identity-key priority survive direct traversal.
    std::unordered_set<std::string> identity = {"甲\n乙", "前\n后"};
    g_index = {{"other", "甲乙\n"}, {"前\n后", "不应\n出现"}, {"unique", "一\n二"}};
    rebuild_package_line_break_index(g_index, &identity);
    assert(!g_package_line_breaks.count("甲乙"));
    assert(g_package_line_breaks.at("前后") == "前\n后");
    assert(!g_package_line_breaks.count("不应出现"));
    assert(g_package_line_breaks.at("一二") == "一\n二");
    rebuild_package_line_break_index({{"fresh", "新\n值"}});
    assert(!g_package_line_breaks.count("前后"));
    assert(g_package_line_breaks.at("新值") == "新\n值");
    std::remove("live-fonts.bin");
    printf("Production live catalogue: %zu model fields, %zu labels; Kotlin/native roundtrip and corruption rejection passed\n",
           g_model_fields.size(), static_cast<size_t>(ntohl(encoded_labels)));
}
