package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.LimbusTranslationAssets
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class TranslationAssetsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "complete-font-fixture".toByteArray()
    private val digest get() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val target get() = temporary.root.toPath().resolve("runtime-font/font.ttf")

    @Test fun verifiedPreparedFontDoesNotOpenCompressedAssetAgain() {
        var opened = 0
        repeat(2) {
            LimbusTranslationAssets.prepareVerified(target, bytes.size.toLong(), digest) {
                opened++; ByteArrayInputStream(bytes)
            }
        }
        assertEquals(1, opened)
        assertArrayEquals(bytes, Files.readAllBytes(target))
    }

    @Test fun failedFontReplacementPreservesExistingFileAndRemovesStaging() {
        Files.createDirectories(target.parent)
        val previous = ByteArray(bytes.size) { 1 }
        Files.write(target, previous)
        assertThrows(IOException::class.java) {
            LimbusTranslationAssets.prepareVerified(target, bytes.size.toLong(), digest) {
                ByteArrayInputStream("truncated".toByteArray())
            }
        }
        assertArrayEquals(previous, Files.readAllBytes(target))
        assertEquals(1, Files.list(target.parent).use { it.count() }.toInt())
    }

    @Test fun sameSizeCorruptionIsRepairedAndOversizedInputCannotCommit() {
        Files.createDirectories(target.parent)
        Files.write(target, ByteArray(bytes.size) { 2 })
        LimbusTranslationAssets.prepareVerified(target, bytes.size.toLong(), digest) { ByteArrayInputStream(bytes) }
        assertArrayEquals(bytes, Files.readAllBytes(target))
        val tooLarge = target.resolveSibling("large.ttf")
        assertThrows(IOException::class.java) {
            LimbusTranslationAssets.prepareVerified(tooLarge, 2, digest) { ByteArrayInputStream(bytes) }
        }
        assertFalse(Files.exists(tooLarge))
    }

    @Test fun secondaryServiceCannotConfigureMainGameTextRuntimeOrReceipt() {
        val game = "com.ProjectMoon.LimbusCompany"
        assertTrue(LimbusTranslationAssets.isMainGameProcess(game, game))
        assertFalse(LimbusTranslationAssets.isMainGameProcess(game, "$game:ip_service"))
        assertFalse(LimbusTranslationAssets.isMainGameProcess(game, null))
        assertFalse(LimbusTranslationAssets.isMainGameProcess("com.google.android.gms", "com.google.android.gms"))
    }
}
