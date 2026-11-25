#pragma once

#include <errno.h>
#include <limits.h>
#include <stddef.h>
#include <string.h>

namespace container_exec {

// execve can run between fork/vfork and exec. Borrow the existing strings and
// build only the pointer table / LD_PRELOAD value on this thread's stack. Never
// call stdio, getenv, allocation, JNI or a logger here.
class Environment {
    static constexpr size_t kEntries = 4096;
    char *entries_[kEntries + 1];
    char preload_[PATH_MAX * 2];
    size_t count_ = 0;

    static bool sizeOf(char *const *values, size_t &count) {
        count = 0;
        if (!values) return true;
        while (count < kEntries && values[count]) ++count;
        if (count == kEntries && values[count]) { errno = E2BIG; return false; }
        return true;
    }
    static bool key(const char *value, const char *name) {
        const size_t length = strlen(name);
        return strncmp(value, name, length) == 0 && value[length] == '=';
    }
    static const char *find(char *const *values, size_t count, const char *name) {
        for (size_t i = 0; i < count; ++i)
            if (key(values[i], name)) return values[i] + strlen(name) + 1;
        return nullptr;
    }
    static bool routingValue(const char *value) {
        return key(value, "V_SO_PATH") || key(value, "V_SO_PATH_64")
            || key(value, "V_API_LEVEL") || key(value, "V_PREVIEW_API_LEVEL")
            || key(value, "V_NATIVE_PATH")
            || strncmp(value, "V_KEEP_ITEM_", 12) == 0
            || strncmp(value, "V_FORBID_ITEM_", 14) == 0
            || strncmp(value, "V_REPLACE_ITEM_SRC_", 19) == 0
            || strncmp(value, "V_REPLACE_ITEM_DST_", 19) == 0;
    }
    bool append(char *value) {
        if (count_ == kEntries) { errno = E2BIG; return false; }
        entries_[count_++] = value;
        entries_[count_] = nullptr;
        return true;
    }
    bool containsKey(const char *value) const {
        const char *equal = strchr(value, '=');
        if (!equal) return false;
        const size_t length = equal - value + 1;
        for (size_t i = 0; i < count_; ++i)
            if (strncmp(entries_[i], value, length) == 0) return true;
        return false;
    }

public:
    bool build(char *const *requested, char *const *inherited, int elfClass) {
        count_ = 0; entries_[0] = nullptr;
        size_t requestedCount, inheritedCount;
        if (!sizeOf(requested, requestedCount) || !sizeOf(inherited, inheritedCount)) return false;
        const char *library = elfClass == 1 ? find(inherited, inheritedCount, "V_SO_PATH")
            : elfClass == 2 ? find(inherited, inheritedCount, "V_SO_PATH_64") : nullptr;
        if (!library || !*library) {
            for (size_t i = 0; i < requestedCount; ++i) if (!append(requested[i])) return false;
            return true;
        }
        const char *existing = find(requested, requestedCount, "LD_PRELOAD");
        const size_t libraryLength = strnlen(library, sizeof(preload_));
        const size_t existingLength = existing ? strnlen(existing, sizeof(preload_)) : 0;
        if (libraryLength + existingLength + 13 > sizeof(preload_)) { errno = E2BIG; return false; }
        memcpy(preload_, "LD_PRELOAD=", 11);
        memcpy(preload_ + 11, library, libraryLength);
        size_t used = 11 + libraryLength;
        if (existingLength) {
            preload_[used++] = ':';
            memcpy(preload_ + used, existing, existingLength); used += existingLength;
        }
        preload_[used] = 0;
        for (size_t i = 0; i < requestedCount; ++i) {
            if (!key(requested[i], "LD_PRELOAD") && !append(requested[i])) return false;
        }
        if (!append(preload_)) return false;
        for (size_t i = 0; i < inheritedCount; ++i) {
            if (routingValue(inherited[i]) && !containsKey(inherited[i]) && !append(inherited[i])) return false;
        }
        return true;
    }
    char *const *values() const { return entries_; }
};

}
