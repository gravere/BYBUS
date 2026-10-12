package com.example.limbuszhcn.container

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Properties

class TranslationBuildCacheTest {
    @Test
    fun survivesRecreationAndInvalidatesOnAnySourceOrIndexChange() {
        val root = Files.createTempDirectory("build-cache")
        val localize = Files.createDirectories(root.resolve("localize"))
        val original = localize.resolve("JP.json")
        Files.write(original, "日本語".toByteArray())
        val translated = Files.write(root.resolve("CN.json"), "简体字".toByteArray())
        val storage = ContainerWorkspaceStorage(localize)
        val inputs = listOf(TranslationBuildCache.Input(translated, original.toString()))
        val receipt = root.resolve("receipt.properties")
        val cache = TranslationBuildCache(receipt)
        val active = Properties().apply {
            setProperty("schema", "8"); setProperty("version", "2026092201")
            setProperty("indexSha256", "index"); setProperty("inputSha256", "input")
        }
        val initial = cache.fingerprint("release-archive-sha", inputs, storage)
        val digests = com.example.limbuszhcn.update.FileDigestSession()
        assertEquals(initial, cache.fingerprint("release-archive-sha", inputs, storage, digests::sha256))
        assertFalse(cache.matches(initial, active))
        cache.save(initial, active)
        assertTrue(TranslationBuildCache(receipt).matches(initial, active))
        assertFalse(cache.matches(initial, null)) // Invalid active index can never be bypassed.
        val other = Properties().apply { putAll(active); setProperty("indexSha256", "different") }
        assertFalse(cache.matches(initial, other))
        Files.write(original, "更新後".toByteArray()) // Equal length: metadata alone is insufficient.
        assertFalse(cache.matches(cache.fingerprint("release-archive-sha", inputs, storage), active))
        Files.write(original, "日本語".toByteArray())
        assertEquals(initial, cache.fingerprint("release-archive-sha", inputs, storage))
        Files.write(translated, "已更新".toByteArray())
        assertNotEquals(initial, cache.fingerprint("release-archive-sha", inputs, storage))
        Files.write(translated, "简体字".toByteArray())
        Files.delete(original)
        assertNotEquals(initial, cache.fingerprint("release-archive-sha", inputs, storage))
        Files.write(original, "日本語".toByteArray())
        assertNotEquals(initial, cache.fingerprint("new-release", inputs, storage))
        Files.write(receipt, "broken".toByteArray())
        assertFalse(cache.matches(initial, active))
    }
}
