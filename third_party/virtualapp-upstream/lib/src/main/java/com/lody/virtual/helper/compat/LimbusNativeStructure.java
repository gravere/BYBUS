package com.lody.virtual.helper.compat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounded ELF checks for the Android dynamic loader. */
public final class LimbusNativeStructure {
    private LimbusNativeStructure() {}
    public static final int MIN_SDK = 26;
    private static final long MAX_LIBRARY = 512L * 1024 * 1024;
    private static final int HEAD = 16 * 1024;
    public static boolean supportsEnvironment(int sdk, String abi, long pageSize) {
        return sdk >= MIN_SDK && "arm64-v8a".equals(abi) &&
                (pageSize == 4096 || pageSize == 16384);
    }

    static final class Segment {
        final long flags, offset, address, size, memory;
        Segment(byte[] data, int at, long fileSize, long pageSize) throws IOException {
            flags = u32(data, at + 4); offset = u64(data, at + 8); address = u64(data, at + 16);
            size = u64(data, at + 32); memory = u64(data, at + 40);
            long alignment = u64(data, at + 48);
            require(range(offset, size, fileSize) && size <= memory && range(address, memory, MAX_LIBRARY * 2),
                    "Invalid load segment");
            require(alignment >= pageSize && (alignment & (alignment - 1)) == 0 &&
                    address % alignment == offset % alignment, "Load alignment");
        }
    }

    /** Inspect only bounded ELF headers; do not decompress large game libraries twice. */
    public static void requireLoadAlignment(InputStream input, long fileSize, long pageSize) throws IOException {
        require(fileSize >= 64 && fileSize <= MAX_LIBRARY, "Library size");
        byte[] header = new byte[(int) Math.min(HEAD, fileSize)];
        int offset = 0;
        while (offset < header.length) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Inspection interrupted");
            int read = input.read(header, offset, header.length - offset);
            require(read > 0, "Truncated ELF header");
            offset += read;
        }
        loadSegments(header, fileSize, pageSize);
    }

    private static List<Segment> loadSegments(byte[] h, long fileSize, long pageSize) throws IOException {
        require(pageSize == 4096 || pageSize == 16384, "Unsupported page size");
        require(h.length >= 64 && h[0] == 127 && h[1] == 'E' && h[2] == 'L' && h[3] == 'F' &&
                h[4] == 2 && h[5] == 1 && h[6] == 1 && u16(h, 16) == 3 &&
                u16(h, 18) == 183 && u32(h, 20) == 1 && u16(h, 52) == 64, "ELF identity");
        int count = u16(h, 56);
        long at = u64(h, 32);
        require(u16(h, 54) == 56 && count > 0 && count <= 64 && at >= 64 &&
                range(at, count * 56L, h.length), "ELF program table");
        List<Segment> loads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int row = (int) at + i * 56;
            if (u32(h, row) == 1) loads.add(new Segment(h, row, fileSize, pageSize));
        }
        require(!loads.isEmpty(), "Missing load segments");
        for (int i = 0; i < loads.size(); i++) for (int j = i + 1; j < loads.size(); j++) {
            Segment a = loads.get(i), b = loads.get(j);
            require(a.address + a.memory <= b.address || b.address + b.memory <= a.address,
                    "Overlapping load segments");
        }
        return loads;
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
