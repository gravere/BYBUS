#pragma once
#include <algorithm>
#include <cstdint>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace limbus_terms {
// Immutable byte trie. Strings remain owned by the validated translation map.
// A byte edge needs no separate allocation; siblings are contiguous and sorted.
class Trie {
public:
    struct Value {
        const std::string *source;
        const std::string *translation;
        Value(const std::string *key, const std::string *text) : source(key), translation(text) {}
    };
    static constexpr uint32_t missing = std::numeric_limits<uint32_t>::max();
private:
    struct Node {
        uint32_t first = 0;
        uint32_t value = 0; // One-based; zero is not a terminal.
        uint16_t count = 0;
    };
    struct Edge {
        uint32_t child;
        unsigned char byte;
        Edge(uint32_t index, unsigned char key) : child(index), byte(key) {}
    };
    struct Work {
        uint32_t node;
        size_t begin, end, depth;
        Work(uint32_t id, size_t first, size_t last, size_t offset)
            : node(id), begin(first), end(last), depth(offset) {}
    };
    std::vector<Node> nodes_;
    std::vector<Edge> edges_;
    std::vector<Value> values_;
public:
    void clear() { nodes_.clear(); edges_.clear(); values_.clear(); }
    size_t size() const { return nodes_.size(); }
    size_t storage_bytes() const {
        return nodes_.capacity() * sizeof(Node) + edges_.capacity() * sizeof(Edge) +
               values_.capacity() * sizeof(Value);
    }
    bool build(std::vector<Value> values) {
        if (values.size() >= missing) return false;
        for (const auto &value : values)
            if (!value.source || !value.translation || value.source->empty()) return false;
        std::sort(values.begin(), values.end(), [](const Value &a, const Value &b) {
            return *a.source < *b.source;
        });
        for (size_t i = 1; i < values.size(); ++i)
            if (*values[i - 1].source == *values[i].source) return false;
        Trie next;
        next.values_.swap(values);
        next.nodes_.emplace_back();
        std::vector<Work> pending;
        pending.emplace_back(0, 0, next.values_.size(), 0);
        while (!pending.empty()) {
            const Work work = pending.back();
            pending.pop_back();
            Node node;
            if (next.edges_.size() >= missing) return false;
            node.first = static_cast<uint32_t>(next.edges_.size());
            size_t first = work.begin;
            if (first < work.end && next.values_[first].source->size() == work.depth)
                node.value = static_cast<uint32_t>(first++) + 1;
            while (first < work.end) {
                const auto &key = *next.values_[first].source;
                if (key.size() <= work.depth || next.nodes_.size() >= missing ||
                    next.edges_.size() >= missing) return false;
                const unsigned char byte = static_cast<unsigned char>(key[work.depth]);
                size_t end = first + 1;
                while (end < work.end && next.values_[end].source->size() > work.depth &&
                       static_cast<unsigned char>((*next.values_[end].source)[work.depth]) == byte) ++end;
                const uint32_t child = static_cast<uint32_t>(next.nodes_.size());
                next.nodes_.emplace_back();
                next.edges_.emplace_back(child, byte);
                ++node.count;
                pending.emplace_back(child, first, end, work.depth + 1);
                first = end;
            }
            next.nodes_[work.node] = node;
        }
        next.nodes_.shrink_to_fit();
        next.edges_.shrink_to_fit();
        next.values_.shrink_to_fit();
        nodes_.swap(next.nodes_); edges_.swap(next.edges_); values_.swap(next.values_);
        return true;
    }
    uint32_t child(uint32_t index, unsigned char byte) const {
        if (index >= nodes_.size()) return missing;
        const Node &node = nodes_[index];
        size_t first = node.first, end = first + node.count;
        while (first < end) {
            const size_t middle = first + (end - first) / 2;
            if (edges_[middle].byte < byte) first = middle + 1;
            else end = middle;
        }
        return first < static_cast<size_t>(node.first) + node.count && edges_[first].byte == byte
                ? edges_[first].child : missing;
    }
    const Value *value(uint32_t index) const {
        return index < nodes_.size() && nodes_[index].value ? &values_[nodes_[index].value - 1] : nullptr;
    }
};
} // namespace limbus_terms
