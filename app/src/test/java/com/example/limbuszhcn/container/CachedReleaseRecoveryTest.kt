package com.example.limbuszhcn.container

import com.example.limbuszhcn.update.ExtractionRequest
import com.example.limbuszhcn.update.TranslationArchiveExtractor
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.readText
import kotlin.io.path.writeText

class CachedReleaseRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cache get() = temporary.root.toPath().resolve("cache")
    private val localize get() = temporary.root.toPath().resolve("Localize")
    private fun installer() = ContainerPatchInstaller(cache, ContainerWorkspaceStorage(localize))

    private fun cached(version: String): Path {
        val archive = cache.resolve("downloads/LimbusLocalize_$version.7z")
        Files.createDirectories(archive.parent)
        val content = """{"dataList":[{"id":1,"content":"罪人资料"}]}""".toByteArray()
        SevenZOutputFile(archive.toFile()).use { output ->
            output.putArchiveEntry(SevenZArchiveEntry().apply {
                name = "LimbusCompany_Data/Lang/LLC_zh-CN/MainUIText.json"
                size = content.size.toLong()
            })
            output.write(content)
            output.closeArchiveEntry()
        }
        TranslationArchiveExtractor().extract(ExtractionRequest(archive, cache.resolve("patch-cache"), version))
        return archive
    }

    private fun resources() {
        val jp = localize.resolve("jp/JP_MainUIText.json")
        Files.createDirectories(jp.parent)
        jp.writeText("""{"dataList":[{"id":1,"content":"囚人の資料"}]}""")
    }

    @Test fun firstIndexCanBeBuiltFromCacheAfterResourcePreparationWithoutAnyNetwork() {
        cached("100")
        val pending = installer().verifyCachedForLaunch()
        assertFalse(pending.indexActivated)
        assertNotNull(pending.pendingReason)
        assertTrue(installer().activeInstallSummary()!!.liveResourcesReady)
        resources()
        val ready = installer().verifyCachedForLaunch()
        assertTrue(ready.indexActivated)
        assertEquals("100", installer().activeInstallSummary()!!.version)
        assertEquals(1, ready.sourceRecordCount)
    }

    @Test fun damagedNewestArchiveFallsBackToAnOlderFullyVerifiedRelease() {
        cached("100")
        val broken = cached("200")
        broken.writeText("truncated archive")
        resources()
        assertEquals("100", installer().verifyCachedForLaunch().version)
        assertTrue(installer().activeInstallSummary()!!.indexActivated)
    }

    @Test fun invalidArchivesCannotBypassVerificationOrRemoveTheExistingIndex() {
        val archive = cached("100")
        resources()
        installer().verifyCachedForLaunch()
        archive.writeText("broken")
        assertThrows(IllegalStateException::class.java) { installer().verifyCachedForLaunch() }
        assertEquals("100", installer().activeInstallSummary()!!.version)
    }

    @Test fun damagedIndexMetadataIsRebuiltFromVerifiedCacheInsteadOfRepeatedlyFailing() {
        cached("100")
        resources()
        assertTrue(installer().verifyCachedForLaunch().indexActivated)
        val root = cache.resolve("translation-index")
        val index = Path.of(root.resolve("active-index.path").readText().trim())
        val metadata = index.parent.resolve("manifest.properties")
        val valid = Files.readAllBytes(metadata)
        val originalIndex = Files.readAllBytes(index)
        val corruptions = listOf<(Properties) -> Unit>(
            { it.remove("availableSourceFileCount") },
            { it.setProperty("sourceRecordCount", "invalid") },
            { it.setProperty("candidateSourceFileCount", "-1") },
            { it.setProperty("availableSourceFileCount", "2") }
        )
        for (corrupt in corruptions) {
            val damaged = Properties().apply { valid.inputStream().use(::load) }
            corrupt(damaged)
            Files.newOutputStream(metadata).use { damaged.store(it, "Damaged metadata fixture") }
            assertNull(TranslationIndexStore(root).activeManifest())
            val repaired = installer().verifyCachedForLaunch()
            assertTrue(repaired.indexActivated)
            assertFalse(repaired.reusedIndex)
            assertEquals(1, repaired.availableSourceFiles)
            assertArrayEquals(originalIndex, Files.readAllBytes(index))
            assertTrue(installer().verifyCachedForLaunch().reusedIndex)
        }
    }

    @Test fun offlineRebuildRetainsItsReasonWhenThePreviousIndexIsPreserved() {
        cached("100")
        resources()
        installer().verifyCachedForLaunch()
        val index = Path.of(cache.resolve("translation-index/active-index.path").readText().trim())
        val previous = Files.readAllBytes(index)
        localize.resolve("jp/JP_MainUIText.json").writeText(
            """{"dataList":[{"id":2,"content":"更新後の説明"}]}""")
        val result = installer().verifyCachedForLaunch()
        assertFalse(result.indexActivated)
        assertTrue(result.previousIndexPreserved)
        assertNotNull(result.pendingReason)
        assertEquals(0, result.availableSourceFiles)
        assertTrue(installer().activeInstallSummary()!!.indexActivated)
        assertArrayEquals(previous, Files.readAllBytes(index))
    }
}
