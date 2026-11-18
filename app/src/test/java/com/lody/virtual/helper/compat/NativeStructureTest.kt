package com.lody.virtual.helper.compat

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class NativeStructureTest {
    @Test fun nativeHeadersMustMatchActualPageSizeAndRemainBounded() {
        val header = ByteArray(16384)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(127, 69, 76, 70, 2, 1, 1))
        b.putShort(16, 3); b.putShort(18, 183); b.putInt(20, 1)
        b.putLong(32, 64); b.putShort(52, 64); b.putShort(54, 56); b.putShort(56, 1)
        b.putInt(64, 1); b.putInt(68, 5)
        b.putLong(96, 4096); b.putLong(104, 4096); b.putLong(112, 16384)
        fun check(page: Long) = LimbusNativeStructure.requireLoadAlignment(
            ByteArrayInputStream(header), 64L * 1024 * 1024, page)
        fun rejected(page: Long) {
            try { check(page); fail("Accepted malformed/unaligned native library") }
            catch (_: java.io.IOException) { }
        }
        check(4096); check(16384) // No attempt to decompress the remaining 64 MB.
        b.putLong(112, 4096)
        check(4096); rejected(16384)
        b.putLong(112, 16384); b.putLong(80, 4096)
        rejected(4096) // p_vaddr and p_offset must agree modulo p_align.
        b.putLong(80, 0); b.putLong(96, Long.MAX_VALUE)
        rejected(4096)
        b.putLong(96, 4096); b.putLong(32, 16370)
        rejected(4096)
        b.putLong(32, 64); rejected(0); rejected(65536)
        try {
            LimbusNativeStructure.requireLoadAlignment(ByteArrayInputStream(header.copyOf(80)), 4096, 4096)
            fail("Accepted truncated native header")
        } catch (_: java.io.IOException) { }
    }
}
