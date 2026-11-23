#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusTermTrie.h"
#include <cassert>
#include <cstdio>
#include <map>

int main() {
    using limbus_terms::Trie;
    std::map<std::string, std::string> dictionary;
    dictionary.emplace("On", "一"); dictionary.emplace("On Hit", "二");
    dictionary.emplace("震颤", "三"); dictionary.emplace("진동", "四");
    dictionary.emplace(std::string(8192, 'a'), "长词条");
    for (unsigned i = 0; i < 256; ++i) dictionary.emplace(std::string(1, static_cast<char>(i)), std::to_string(i));
    std::vector<Trie::Value> values;
    for (const auto &entry : dictionary) values.emplace_back(&entry.first, &entry.second);
    Trie trie;
    assert(trie.build(values));
    for (const auto &entry : dictionary) {
        uint32_t node = 0;
        for (unsigned char byte : entry.first) {
            node = trie.child(node, byte); assert(node != Trie::missing);
        }
        const auto *value = trie.value(node);
        assert(value && *value->source == entry.first && *value->translation == entry.second);
        assert(trie.child(node, 1) == Trie::missing || entry.first.size() == 1);
    }
    assert(!trie.value(Trie::missing));
    assert(trie.child(Trie::missing, 0) == Trie::missing);
    const auto count = trie.size();
    values.push_back(values.front());
    assert(!trie.build(values)); assert(trie.size() == count);
    trie.clear(); assert(trie.size() == 0);
    assert(trie.build({})); assert(trie.size() == 1 && !trie.value(0));
    puts("Compact term trie: all 256 byte edges, prefix terms, multilingual keys, long keys and rebuild passed");
}
