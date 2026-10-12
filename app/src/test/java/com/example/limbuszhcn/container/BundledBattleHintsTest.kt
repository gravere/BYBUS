package com.example.limbuszhcn.container

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BundledBattleHintsTest {
    @Test fun readsOnlyBoundedHintTextAssetsAndRejectsCorruption() {
        val text = """{"dataList":[{"id":1,"content":"旧版のヒント"}]}"""
        val valid = fixture(text)
        assertEquals("JP_BattleHint.json" to text, BundledBattleHints.parse(valid))
        assertNull(BundledBattleHints.parse(fixture(text, "JP_AccountData")))
        assertNull(BundledBattleHints.parse(valid.copyOf(valid.size - 1)))
        assertNull(BundledBattleHints.parse(valid.copyOf().apply { this[11] = 23 }))
        assertNull(BundledBattleHints.parse(valid.copyOf().apply { this[69] = 48 }))
        assertNull(BundledBattleHints.parse(valid.copyOf().apply { this[16] = 1 }))
        assertNull(BundledBattleHints.parse(valid.copyOf().apply { this[size - 1] = 0xff.toByte() }))
    }

    @Test fun duplicateBundledNamesWithDifferentContentsCannotWinByZipOrder() {
        val apk = Files.createTempFile("limbus-hints", ".apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            for (id in 1..2) {
                zip.putNextEntry(ZipEntry("assets/bin/Data/" + id.toString().padStart(32, '0')))
                zip.write(fixture("""{"dataList":[{"id":1,"content":"文$id"}]}"""))
                zip.closeEntry()
            }
        }
        assertTrue(BundledBattleHints.read(listOf(apk.toString())).isEmpty())
    }

    @Test fun bundledAndDownloadedVariantsUseTheSameTranslationWithoutReplacingGameData() {
        val translator = """{"dataList":[{"id":1,"content":"提示正文"}]}"""
        val baseline = """{"dataList":[{"id":1,"content":"古いヒント"}]}"""
        val current = """{"dataList":[{"id":1,"content":"更新したヒント"}]}"""
        val embedded = BundledBattleHints.parse(fixture(baseline))!!.second
        val compiler = TranslationIndexCompiler()
        for (source in listOf(current, embedded)) {
            TranslationJsonPairer.pair("jp/JP_BattleHint.json", source, translator).records.forEach(compiler::add)
        }
        val compiled = compiler.compile("2026092102", "archive")
        assertEquals("提示正文", compiled.uniqueEntries["古いヒント"])
        assertEquals("提示正文", compiled.uniqueEntries["更新したヒント"])
    }

    private fun fixture(text: String, name: String = "JP_BattleHint"): ByteArray {
        val nameBytes = name.toByteArray()
        val textBytes = text.toByteArray()
        val textOffset = ((144 + 4 + nameBytes.size + 3) and -4) + 4
        val size = textOffset + textBytes.size
        val b = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        b.putInt(0).putInt(0).putInt(22).putInt(0).putInt(0)
        b.putInt(85).putLong(size.toLong()).putLong(144).putLong(0)
        b.order(ByteOrder.LITTLE_ENDIAN)
        b.put("6000.3.12f1\u0000".toByteArray()).putInt(13).put(0).putInt(1)
        b.putInt(49).put(0).putShort(-1).put(ByteArray(16)).putInt(1)
        b.putLong(1).putLong(0).putInt(size - 144).putInt(0)
        b.putInt(0).putInt(0).putInt(0).put(0)
        check(b.position() == 133)
        b.position(144)
        b.putInt(nameBytes.size).put(nameBytes)
        b.position(textOffset - 4)
        b.putInt(textBytes.size).put(textBytes)
        return b.array()
    }
}
