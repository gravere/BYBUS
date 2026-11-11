#pragma once
#include "LimbusManagedState.h"

namespace limbus_font_materials {

// The component registry owns these leases. The pool only holds weak lookups,
// so visiting more pages does not pin every material for the process lifetime.
class Material {
    limbus_managed::Ref root_;
    limbus_managed::Ref source_;
    bool restore_source_;
    void (*release_)(void *);
public:
    Material(limbus_managed::Ref root, void (*release)(void *),
             limbus_managed::Ref source, bool restore_source)
        : root_(std::move(root)), source_(std::move(source)),
          restore_source_(restore_source), release_(release) {}
    ~Material() { if (auto object = root_->get()) release_(object); }
    Material(const Material &) = delete;
    Material &operator=(const Material &) = delete;
    void *get() const { return root_->get(); }
    const limbus_managed::Ref &source_root() const { return source_; }
    void *source() const { return source_ ? source_->get() : nullptr; }
    bool restores_source() const { return restore_source_; }
};
using Lease = std::shared_ptr<Material>;

// Unity material APIs and this pool run on the Unity UI thread.
class Pool {
    limbus_managed::GcApi &gc_;
    std::unordered_map<void *, std::weak_ptr<Material>> entries_;
    unsigned acquisitions_ = 0;
public:
    explicit Pool(limbus_managed::GcApi &gc) : gc_(gc) {}
    Lease find(void *object) {
        auto it = entries_.find(object);
        if (it == entries_.end()) return {};
        auto lease = it->second.lock();
        if (lease && lease->get() == object) return lease;
        entries_.erase(it);
        return {};
    }
    void collect() {
        for (auto it = entries_.begin(); it != entries_.end();) {
            if (it->second.expired()) it = entries_.erase(it);
            else ++it;
        }
    }
    Lease acquire(void *object, bool (*retain)(void *), void (*release)(void *),
                  limbus_managed::Ref source = {}, bool restore_source = false) {
        if (!object || !retain || !release) return {};
        if (auto existing = find(object)) return existing;
        if ((++acquisitions_ & 31) == 0) collect();
        auto root = limbus_managed::retain(gc_, object);
        if (!root || !retain(object)) return {};
        auto lease = std::make_shared<Material>(std::move(root), release,
                std::move(source), restore_source);
        entries_[object] = lease;
        return lease;
    }
    size_t size() const { return entries_.size(); }
};
}
