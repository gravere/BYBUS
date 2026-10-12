package com.lody.virtual.helper.compat

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** Real original libraries are local-only fixtures; never commit the game's binaries. */
class NativeStructureTest {
    private fun fixtures(): Pair<LimbusNativeStructure.Image, LimbusNativeStructure.Image> {
        val path = System.getenv("BYBUS_GAME_NATIVE_TEST_DIR")
        assumeTrue("Local original-game fixture not configured", !path.isNullOrBlank())
        synchronized(NativeStructureTest::class.java) {
            if (fixture == null) {
                val root = Path.of(path!!)
                fun read(name: String, protection: Boolean): LimbusNativeStructure.Image {
                    val file = root.resolve(name)
                    return Files.newInputStream(file).use { LimbusNativeStructure.read(it, Files.size(file), protection) }
                }
                fixture = read("libcovault-appsec.so", true) to read("libil2cpp.so", false)
            }
            return fixture!!
        }
    }

    private fun qualify(engine: LimbusNativeStructure.Image, game: LimbusNativeStructure.Image) =
        LimbusNativeStructure.qualify(engine, game, 36, "arm64-v8a", 4096)

    private fun changed(image: LimbusNativeStructure.Image, offset: Int, value: Byte): LimbusNativeStructure.Image {
        val head = image.head.copyOf(); val tail = image.tail.copyOf(); val full = image.full?.copyOf()
        if (offset < head.size) head[offset] = value
        val tailOffset = offset - (image.size - tail.size)
        if (tailOffset in 0 until tail.size) tail[tailOffset.toInt()] = value
        full?.set(offset, value)
        return LimbusNativeStructure.Image(image.size, head, tail, full, image.sha256)
    }

    @Test fun currentOriginalBuildQualifiesWithoutAnyVersionCatalog() {
        val (engine, game) = fixtures()
        assertEquals("${0x4049e40L}:${0x5e595cL}:${0x462f79cL}:${0x59d24b0L}", qualify(engine, game))
        assertNull("Large game library must never be retained", game.full)
        assertTrue(game.head.size + game.tail.size <= 144 * 1024)
    }

    @Test fun alteredCriticalCodeAndUnsupportedPlatformAreRejected() {
        val (engine, game) = fixtures()
        for (offset in listOf(0x22594, 0x4e6b0, 0x54488, 0x700ec, 0x70b68, 0x99820, 0xac878)) {
            val altered = changed(engine, offset, (engine.full[offset].toInt() xor 1).toByte())
            assertEquals("Changed critical site 0x${offset.toString(16)}", "", qualify(altered, game))
        }
        assertEquals("", LimbusNativeStructure.qualify(engine, game, 35, "arm64-v8a", 4096))
        assertEquals("", LimbusNativeStructure.qualify(engine, game, 36, "x86_64", 4096))
        assertEquals("", LimbusNativeStructure.qualify(engine, game, 36, "arm64-v8a", 16384))
    }

    @Test fun malformedElfCannotSupplyOffsetsOrSizes() {
        val (engine, game) = fixtures()
        for ((offset, value) in listOf(4 to 1, 5 to 2, 18 to 0, 54 to 0, 55 to 127,
                56 to 0, 58 to 0, 61 to 127, 62 to 0, 47 to 127)) {
            assertEquals("Malformed header field $offset", "", qualify(engine, changed(game, offset, value.toByte())))
        }
        val h = ByteBuffer.wrap(game.head).order(ByteOrder.LITTLE_ENDIAN)
        val sectionAt = h.getLong(40)
        val sectionCount = h.getShort(60).toInt() and 65535
        for (i in 0 until sectionCount) {
            val at = sectionAt + i * 64
            val row = ByteBuffer.wrap(game.bytes(at, 64)).order(ByteOrder.LITTLE_ENDIAN)
            if (row.getLong(16) != 0x462f79cL) continue
            // Overflow/unaligned sizes, and a section pointing into the preceding .text section.
            assertEquals("", qualify(engine, changed(game, (at + 39).toInt(), 127)))
            assertEquals("", qualify(engine, changed(game, (at + 32).toInt(), 1)))
            var overlap = game
            val address = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0x4049e40L).array()
            for (j in address.indices) overlap = changed(overlap, (at + 16 + j).toInt(), address[j])
            assertEquals("", qualify(engine, overlap))
            return
        }
        fail("Missing real il2cpp section fixture")
    }

    @Test fun libraryReaderRejectsTruncationOversizeAndKeepsExactWindows() {
        val input = ByteArray(400_000) { (it % 251).toByte() }
        val image = LimbusNativeStructure.read(ByteArrayInputStream(input), input.size.toLong(), false)
        assertArrayEquals(input.copyOfRange(0, 64), image.bytes(0, 64))
        assertArrayEquals(input.copyOfRange(input.size - 64, input.size), image.bytes(input.size - 64L, 64))
        for (size in listOf(63L, input.size - 1L, input.size + 1L, 513L * 1024 * 1024)) {
            try {
                LimbusNativeStructure.read(ByteArrayInputStream(input), size, false)
                fail("Accepted invalid size $size")
            } catch (_: java.io.IOException) { }
        }
    }

    companion object {
        private var fixture: Pair<LimbusNativeStructure.Image, LimbusNativeStructure.Image>? = null
    }
}
