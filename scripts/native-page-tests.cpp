#include <cassert>
#include <cstdio>
#include <initializer_list>
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/RuntimePage.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/Arm64Entry.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/OwnedExecutablePages.h"

int main() {
    runtime_page::Span span;
    for (size_t page : {size_t(4096), size_t(16384)}) {
        const uintptr_t base = page * 16;
        assert(runtime_page::span(base, page, page, span));
        assert(span.start == base && span.length == page);
        assert(runtime_page::span(base + page - 16, 16, page, span));
        assert(span.start == base && span.length == page); // exact last instruction, no extra page
        assert(runtime_page::span(base + page - 16, 20, page, span));
        assert(span.start == base && span.length == page * 2);
        assert(runtime_page::span(base + 4, 20, page, span));
        assert(span.start == base && span.length == page);
        assert(!runtime_page::span(0, 20, page, span));
        assert(!runtime_page::span(base, 0, page, span));
        assert(!runtime_page::span(UINTPTR_MAX - 7, 20, page, span));
        assert(!runtime_page::span(UINTPTR_MAX - page + 1, 4, page, span));
        assert(!runtime_page::span(base, SIZE_MAX, page, span));
        assert(span.start == 0 && span.length == 0);
    }
    assert(!runtime_page::span(0x10000, 20, 0, span));
    assert(!runtime_page::span(0x10000, 20, 8192, span));
    assert(!runtime_page::span(0x10000, 20, 65536, span));
    const uint32_t short_wrapper[] = {0x2a1f03e4, 0xd503201f, 0x14000002, 0xd503201f, 0xd503201f};
    const uint32_t syscall_wrapper[] = {0xd28004c8, 0xd4000001, 0xb140041f, 0xda9f23e0, 0xd65f03c0};
    const uint32_t call_then_return[] = {0x94000002, 0xd503201f, 0xd503201f, 0xd503201f, 0xd65f03c0};
    const uint32_t authenticated_return[] = {0xd65f0bff, 0xd503201f, 0xd503201f, 0xd503201f};
    assert(!arm64_entry_has_room(short_wrapper, 5, 4));
    assert(!arm64_entry_has_room(short_wrapper, 5, 5));
    assert(arm64_entry_has_room(syscall_wrapper, 5, 5));
    assert(arm64_entry_has_room(call_then_return, 5, 5));
    assert(!arm64_entry_has_room(authenticated_return, 4, 4));
    assert(!arm64_entry_has_room(syscall_wrapper, 3, 4));
    assert(!arm64_entry_has_room(nullptr, 5, 4));
    for (size_t page : {size_t(4096), size_t(16384)}) {
        OwnedExecutablePages<2> owned;
        assert(!owned.contains(0x10000, 0x10000 + page, page));
        assert(!owned.add(0x10001, page));
        assert(owned.add(0x10000, page));
        assert(owned.add(0x10000, page));
        assert(owned.contains(0x10000, 0x10000 + page, page));
        assert(!owned.contains(0x20000, 0x20000 + page, page));
        assert(!owned.contains(0x10000, 0x10000 + page * 2, page));
        assert(owned.add(0x30000, page));
        assert(!owned.add(0x50000, page));
        owned.remove(0x10000);
        assert(!owned.contains(0x10000, 0x10000 + page, page));
        assert(owned.add(0x50000, page));
    }
    puts("PASS: 4K/16K page spans, invalid/overflow bounds, short ARM64 wrapper guards");
}
