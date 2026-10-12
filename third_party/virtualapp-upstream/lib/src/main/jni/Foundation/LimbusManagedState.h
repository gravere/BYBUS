#pragma once

#include <algorithm>
#include <cstdint>
#include <iterator>
#include <list>
#include <memory>
#include <string>
#include <unordered_map>
#include <utility>

namespace limbus_managed {

// Unity 6 Il2CppGCHandle is an opaque pointer, not a uint32_t table index.
// Truncation can silently lose retained component/asset identity on ARM64.
using Handle = void *;
struct GcApi {
    Handle (*retain)(void *, bool) = nullptr;
    Handle (*weak)(void *, bool) = nullptr;
    void *(*target)(Handle) = nullptr;
    void (*release)(Handle) = nullptr;
    bool ready() const { return retain && weak && target && release; }
};

class Reference {
    GcApi *api_;
    Handle handle_;
public:
    Reference(GcApi &api, void *object, bool weak) : api_(&api), handle_(0) {
        if (object && api.ready()) handle_ = (weak ? api.weak : api.retain)(object, false);
    }
    ~Reference() { if (handle_) api_->release(handle_); }
    Reference(const Reference &) = delete;
    Reference &operator=(const Reference &) = delete;
    void *get() const { return handle_ ? api_->target(handle_) : nullptr; }
};
using Ref = std::shared_ptr<Reference>;
inline Ref retain(GcApi &api, void *object) {
    auto ref = std::make_shared<Reference>(api, object, false);
    return ref->get() == object && object ? ref : nullptr;
}

// Live entries are never evicted. GC collection/native destruction reclaims
// entries incrementally; capacity failure tells the caller not to mutate UI.
template<class State> class Registry {
    struct Entry {
        Ref owner;
        int32_t id;
        State state;
        typename std::list<void *>::iterator sweep;
    };
    GcApi &api_;
    size_t capacity_;
    std::unordered_map<void *, Entry> entries_;
    std::list<void *> sweep_;
    bool (*alive_)(void *) = nullptr;
    void erase(typename std::unordered_map<void *, Entry>::iterator entry) {
        sweep_.erase(entry->second.sweep);
        entries_.erase(entry);
    }
public:
    Registry(GcApi &api, size_t capacity) : api_(api), capacity_(capacity) {}
    void set_alive(bool (*alive)(void *)) { alive_ = alive; }
    size_t size() const { return entries_.size(); }
    void collect(size_t budget = 8) {
        size_t count = std::min(budget, sweep_.size());
        while (count--) {
            auto entry = entries_.find(sweep_.front());
            void *object = entry->second.owner->get();
            if (!object || (alive_ && !alive_(object))) erase(entry);
            else sweep_.splice(sweep_.end(), sweep_, sweep_.begin());
        }
    }
    State *find(void *object, int32_t id = 0) {
        auto entry = entries_.find(object);
        if (entry == entries_.end()) return nullptr;
        if (entry->second.owner->get() != object || entry->second.id != id) {
            erase(entry);
            return nullptr;
        }
        return &entry->second.state;
    }
    bool put(void *object, int32_t id, State state) {
        collect();
        auto current = entries_.find(object);
        if (current != entries_.end()) erase(current);
        if (entries_.size() >= capacity_) collect(entries_.size());
        if (!object || !api_.ready() || entries_.size() >= capacity_) return false;
        auto owner = std::make_shared<Reference>(api_, object, true);
        if (owner->get() != object) return false;
        sweep_.push_back(object);
        entries_.emplace(object, Entry{owner, id, std::move(state), std::prev(sweep_.end())});
        return true;
    }
    bool take(void *object, int32_t id, State *state) {
        auto saved = find(object, id);
        if (!saved) return false;
        *state = *saved;
        erase(entries_.find(object));
        return true;
    }
};

// Font evidence for copies. It is never consulted to decide whether an original
// source should be translated. Eviction removes one oldest value, not all proof.
class RecentOutputs {
    std::list<std::string> order_;
    std::unordered_map<std::string, std::list<std::string>::iterator> entries_;
    size_t bytes_ = 0;
public:
    bool contains(const std::string &text) {
        auto it = entries_.find(text);
        if (it == entries_.end()) return false;
        order_.splice(order_.end(), order_, it->second);
        return true;
    }
    void add(const std::string &text) {
        if (text.size() > 16384 || contains(text)) return;
        while (!order_.empty() && (order_.size() >= 4096 || bytes_ + text.size() > 2 * 1024 * 1024)) {
            bytes_ -= order_.front().size();
            entries_.erase(order_.front());
            order_.pop_front();
        }
        order_.push_back(text);
        entries_.emplace(text, std::prev(order_.end()));
        bytes_ += text.size();
    }
};
} // namespace limbus_managed
