package com.lody.virtual.helper.compat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Read-only, bounded ELF qualification. No code scanning, extraction, or learned fingerprints. */
public final class LimbusNativeStructure {
    private LimbusNativeStructure() {}
    public static final String FAMILY = "limbus-engine1-sdk36-arm64-full";
    private static final long MAX_LIBRARY = 512L * 1024 * 1024;
    private static final int HEAD = 16 * 1024, TAIL = 128 * 1024;
    public static boolean supportsEnvironment(int sdk, String abi, long pageSize) {
        return sdk == 36 && "arm64-v8a".equals(abi) && pageSize == 4096;
    }

    // Immutable code regions shared by the independently inspected 471 and 478 libraries:
    // PLT; report frame/dispatch; path checks/cleanup; maps; both CRC callers; path scan; JNI detach.
    // Encrypted blocks are not wildcard-matched: their live caller instructions and original
    // verifier results must still qualify in LimbusCodeIntegrityCompat before any continuation.
    private static final long[][] REGIONS = {
            {0x16540, 0xbf0}, {0x22594, 0xc0}, {0x22b54, 0xcac}, {0x4e67c, 0x1984},
            {0x50640, 0x9754}, {0x6fc20, 0x1310}, {0x99800, 0x100}, {0xac878, 0x188}};
    private static final String[] FINGERPRINTS = {
            "aa44e10fb224097cc99107e9918d0a6e219bf92080525cd98bdfd02a160481f4",
            "aac53926ae8bddbf090c0e6723aff8750609d3cc80b7d8c4f06c33a7fb8ce5a1",
            "7d0c305364bf5d860cd5d7e16a567ea2a839dea0b835b963211825014b540f0e",
            "a7cc464369613724b4ada3dd0231d04989891e70cd10c5fb5d94636f745fc3ac",
            "1cf19f5c8789cfe4d67eea4dd2a140ff4874836546db20a822690ef13f3455f3",
            "75379f906f8557abed5258474ea96928fac3ced5dbd50d6b81ed9f6b56b2da9f",
            "ef5c59f710f96869b94ce4750684be27d31350aebb47dcee5149c6a447dd03e8",
            "05973e39d9dd6cd686804d6dbcf7aba79b1966b7d740d9aa9779d4b525c33b10"};

    public static final class Image {
        public final String sha256;
        final long size;
        final byte[] head, tail, full;
        Image(long size, byte[] head, byte[] tail, byte[] full, String sha256) {
            this.size = size; this.head = head; this.tail = tail; this.full = full; this.sha256 = sha256;
        }
        byte[] bytes(long offset, long count) throws IOException {
            require(range(offset, count, size) && count <= 4 * 1024 * 1024, "ELF range");
            if (full != null) return Arrays.copyOfRange(full, (int) offset, (int) (offset + count));
            if (range(offset, count, head.length))
                return Arrays.copyOfRange(head, (int) offset, (int) (offset + count));
            long start = size - tail.length;
            require(offset >= start, "ELF metadata outside bounded windows");
            return Arrays.copyOfRange(tail, (int) (offset - start), (int) (offset - start + count));
        }
    }

    /** A full digest and bounded metadata windows in one sequential decompression pass. */
    public static Image read(InputStream input, long size, boolean protection) throws Exception {
        require(size >= 64 && size <= (protection ? 4 * 1024 * 1024 : MAX_LIBRARY), "Library size");
        byte[] head = new byte[(int) Math.min(size, HEAD)];
        byte[] tail = new byte[(int) Math.min(size, TAIL)];
        byte[] full = protection ? new byte[(int) size] : null;
        byte[] buffer = new byte[64 * 1024];
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long offset = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Inspection interrupted");
            require(count > 0 && count <= size - offset, "Incomplete or oversized library");
            digest.update(buffer, 0, count);
            copyWindow(buffer, count, offset, head, 0);
            copyWindow(buffer, count, offset, tail, size - tail.length);
            if (full != null) System.arraycopy(buffer, 0, full, (int) offset, count);
            offset += count;
        }
        require(offset == size, "Truncated library");
        return new Image(size, head, tail, full, hex(digest.digest()));
    }

    private static void copyWindow(byte[] source, int count, long offset, byte[] target, long start) {
        long begin = Math.max(offset, start), end = Math.min(offset + count, start + target.length);
        if (begin < end) System.arraycopy(source, (int) (begin - offset), target,
                (int) (begin - start), (int) (end - begin));
    }

    public static String qualify(Image protection, Image il2cpp, int sdk, String abi, long pageSize) {
        if (!supportsEnvironment(sdk, abi, pageSize)) return "";
        try {
            Elf engine = new Elf(protection), game = new Elf(il2cpp);
            Section text = engine.section(".text"), bss = engine.section(".bss");
            engine.requireCode(text);
            require(text.address == 0x17130 && text.offset == text.address && text.size == 0x135c90,
                    "Protection text layout");
            require(bss.address == 0x1b0bd0 && bss.size == 0x4c6f8 && bss.type == 8,
                    "Protection descriptor layout");
            require(engine.mapped(0x1c23e0, 0x5000, 6, false), "Protection descriptor mapping");
            for (int i = 0; i < REGIONS.length; i++) {
                long offset = REGIONS[i][0], size = REGIONS[i][1];
                require(engine.mapped(offset, size, 5, true), "Protection code mapping");
                require(FINGERPRINTS[i].equals(hex(MessageDigest.getInstance("SHA-256")
                        .digest(protection.bytes(offset, size)))), "Protection code changed");
            }
            Section nativeCode = game.section(".text"), managed = game.section("il2cpp");
            game.requireCode(nativeCode); game.requireCode(managed);
            require(nativeCode.address + nativeCode.size <= managed.address, "Overlapping code sections");
            return nativeCode.address + ":" + nativeCode.size + ":" + managed.address + ":" + managed.size;
        } catch (Exception invalid) { return ""; }
    }

    static final class Section {
        final long type, flags, address, offset, size;
        Section(byte[] data, int at) throws IOException {
            type = u32(data, at + 4); flags = u64(data, at + 8); address = u64(data, at + 16);
            offset = u64(data, at + 24); size = u64(data, at + 32);
        }
    }
    static final class Segment {
        final long flags, offset, address, size, memory;
        Segment(byte[] data, int at, long fileSize) throws IOException {
            flags = u32(data, at + 4); offset = u64(data, at + 8); address = u64(data, at + 16);
            size = u64(data, at + 32); memory = u64(data, at + 40);
            long alignment = u64(data, at + 48);
            require(range(offset, size, fileSize) && size <= memory && range(address, memory, MAX_LIBRARY * 2),
                    "Invalid load segment");
            require(alignment >= 4096 && (alignment & (alignment - 1)) == 0 &&
                    address % alignment == offset % alignment, "Load alignment");
        }
    }
    static final class Elf {
        final Image image;
        final byte[] sections, names;
        final List<Segment> loads = new ArrayList<>();
        Elf(Image image) throws IOException {
            this.image = image;
            byte[] h = image.bytes(0, 64);
            require(h[0] == 127 && h[1] == 'E' && h[2] == 'L' && h[3] == 'F' &&
                    h[4] == 2 && h[5] == 1 && h[6] == 1 && u16(h, 16) == 3 &&
                    u16(h, 18) == 183 && u32(h, 20) == 1 && u16(h, 52) == 64, "ELF identity");
            int pc = u16(h, 56), sc = u16(h, 60), ni = u16(h, 62);
            require(u16(h, 54) == 56 && pc > 0 && pc <= 64 && u16(h, 58) == 64 &&
                    sc > 0 && sc <= 256 && ni > 0 && ni < sc, "ELF table shape");
            byte[] programs = image.bytes(u64(h, 32), pc * 56L);
            for (int i = 0; i < pc; i++) if (u32(programs, i * 56) == 1)
                loads.add(new Segment(programs, i * 56, image.size));
            require(!loads.isEmpty(), "Missing load segments");
            for (int i = 0; i < loads.size(); i++) for (int j = i + 1; j < loads.size(); j++) {
                Segment a = loads.get(i), b = loads.get(j);
                require(a.address + a.memory <= b.address || b.address + b.memory <= a.address,
                        "Overlapping load segments");
            }
            sections = image.bytes(u64(h, 40), sc * 64L);
            Section strings = new Section(sections, ni * 64);
            require(strings.type == 3 && strings.size > 0 && strings.size <= 16 * 1024, "ELF names");
            names = image.bytes(strings.offset, strings.size);
        }
        Section section(String name) throws IOException {
            Section result = null;
            for (int i = 0; i < sections.length; i += 64) {
                long offset = u32(sections, i);
                require(offset < names.length, "Section name offset");
                int end = (int) offset;
                while (end < names.length && names[end] != 0) end++;
                require(end < names.length, "Unterminated section name");
                if (name.equals(new String(names, (int) offset, end - (int) offset, StandardCharsets.US_ASCII))) {
                    require(result == null, "Duplicate section");
                    result = new Section(sections, i);
                }
            }
            require(result != null, "Missing section " + name);
            return result;
        }
        boolean mapped(long address, long size, long flags, boolean fileBacked) {
            for (Segment s : loads) if ((s.flags & 7) == flags && address >= s.address &&
                    range(address - s.address, size, fileBacked ? s.size : s.memory)) return true;
            return false;
        }
        void requireCode(Section section) throws IOException {
            require(section.type == 1 && section.flags == 6 && section.size >= 4 && section.address >= 4 &&
                    range(section.address, section.size, MAX_LIBRARY) &&
                    (section.size & 3) == 0 && (section.address & 3) == 0 &&
                    range(section.offset, section.size, image.size) &&
                    mapped(section.address, section.size, 5, true), "Code section mapping");
            boolean related = false;
            for (Segment s : loads) if (section.address >= s.address && section.offset >= s.offset &&
                    section.address - s.address == section.offset - s.offset &&
                    range(section.offset - s.offset, section.size, s.size)) related = true;
            require(related, "Section file/address mismatch");
        }
    }
    static boolean range(long offset, long size, long limit) {
        return offset >= 0 && size >= 0 && offset <= limit && size <= limit - offset;
    }
    static int u16(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
    }
    static long u32(byte[] data, int offset) {
        return ((long) u16(data, offset) | ((long) u16(data, offset + 2) << 16));
    }
    static long u64(byte[] data, int offset) throws IOException {
        long high = u32(data, offset + 4);
        require(high < 0x80000000L, "ELF integer overflow");
        return u32(data, offset) | (high << 32);
    }
    static void require(boolean condition, String reason) throws IOException {
        if (!condition) throw new IOException(reason);
    }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b & 255) >>> 4, 16))
                .append(Character.forDigit(b & 15, 16));
        return out.toString();
    }
}
