#pragma once

#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <string_view>
#include <unordered_map>
#ifdef _WIN32
#include <fstream>
#include <vector>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace limbus_index {

// Published index generations are immutable; live catalogues are atomically
// replaced. Keep the opened inode alive, not a pointer into a temporary buffer.
// Read-only file pages can be reclaimed under pressure without losing entries.
class File {
    const char *data_ = nullptr;
    size_t size_ = 0;
#ifdef _WIN32
    std::vector<char> storage_; // Portable host tests; Android always uses mmap.
#endif
public:
    File() = default;
    File(const File &) = delete;
    File &operator=(const File &) = delete;
    ~File() {
#ifndef _WIN32
        if (data_) munmap(const_cast<char *>(data_), size_);
#endif
    }
    static std::shared_ptr<File> open(const char *path) {
        constexpr size_t limit = 256u * 1024u * 1024u;
        auto file = std::make_shared<File>();
#ifdef _WIN32
        std::ifstream stream(path, std::ios::binary | std::ios::ate);
        const auto size = stream.tellg();
        if (!stream || size <= 0 || static_cast<uint64_t>(size) > limit) return {};
        file->storage_.resize(static_cast<size_t>(size));
        stream.seekg(0);
        if (!stream.read(file->storage_.data(), size)) return {};
        file->size_ = file->storage_.size();
        file->data_ = file->storage_.data();
#else
        const int fd = ::open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) return {};
        struct stat stat{};
        if (fstat(fd, &stat) != 0 || !S_ISREG(stat.st_mode) ||
            stat.st_size <= 0 || static_cast<uint64_t>(stat.st_size) > limit) {
            close(fd);
            return {};
        }
        void *mapped = mmap(nullptr, static_cast<size_t>(stat.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (mapped == MAP_FAILED) return {};
        file->data_ = static_cast<const char *>(mapped);
        file->size_ = static_cast<size_t>(stat.st_size);
#endif
        return file;
    }
    const char *data() const { return data_; }
    size_t size() const { return size_; }
    void release_scan_pages() const {
#ifndef _WIN32
        // Validation/catalogue construction scanned every entry. Do not keep
        // all three languages resident for the next battle; hot entries fault
        // back from the immutable file on demand. This never discards heap data.
        if (data_) (void)madvise(const_cast<char *>(data_), size_, MADV_DONTNEED);
#endif
    }
};

using Table = std::unordered_map<std::string_view, std::string_view>;

class Reader {
    std::shared_ptr<File> file_;
    size_t position_ = 0;
public:
    explicit Reader(std::shared_ptr<File> file) : file_(std::move(file)) {}
    bool bytes(size_t count, std::string_view *value) {
        if (!file_ || count > file_->size() - position_) return false;
        *value = std::string_view(file_->data() + position_, count);
        position_ += count;
        return true;
    }
    bool at_end() const { return file_ && position_ == file_->size(); }
};

inline bool read_exact(Reader &reader, void *out, size_t count) {
    std::string_view data;
    if (!reader.bytes(count, &data)) return false;
    if (count) memcpy(out, data.data(), count);
    return true;
}
inline bool read_u32(Reader &reader, uint32_t *out) {
    unsigned char bytes[4];
    if (!read_exact(reader, bytes, sizeof(bytes))) return false;
    *out = (uint32_t(bytes[0]) << 24) | (uint32_t(bytes[1]) << 16) |
           (uint32_t(bytes[2]) << 8) | uint32_t(bytes[3]);
    return true;
}
inline bool read_string(Reader &reader, std::string_view *out) {
    uint32_t count = 0;
    return read_u32(reader, &count) && count <= 16u * 1024u * 1024u && reader.bytes(count, out);
}
inline bool read_string(Reader &reader, std::string *out) {
    std::string_view value;
    if (!read_string(reader, &value)) return false;
    out->assign(value.data(), value.size());
    return true;
}
} // namespace limbus_index
