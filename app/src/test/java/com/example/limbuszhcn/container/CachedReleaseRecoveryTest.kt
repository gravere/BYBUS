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
        assertNull(installer().activeInstallSummary())
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
}
