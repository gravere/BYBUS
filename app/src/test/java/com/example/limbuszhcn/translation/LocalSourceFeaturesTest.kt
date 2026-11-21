package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.gamefs.GameTextLanguage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LocalSourceFeaturesTest {
    @get:Rule val temp = TemporaryFolder()
    private val cache get() = temp.root.toPath().resolve("cache")
    private val sources get() = TranslationSources(cache)
    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (key, value) ->
            zip.putNextEntry(ZipEntry(key).apply { time = 0 }); zip.write(value.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()
    private fun text(value: String) = """{"dataList":[{"id":1,"content":"$value"}]}"""
    private fun game(language: GameTextLanguage = GameTextLanguage.Japanese, vararg files: Pair<String, String>): ContainerWorkspaceStorage {
        val root = temp.root.toPath().resolve("Localize")
        files.forEach { (name, value) ->
            val path = root.resolve(language.resourceKey("en/EN_$name.json"))
            Files.createDirectories(path.parent); path.writeText(value)
        }
        return ContainerWorkspaceStorage(root)
    }
    private fun pack(name: String, vararg files: Pair<String, String>) = sources.import(zip(*files).inputStream(), "$name.zip")

    @Test fun supplementPreservesExistingAndSameTextAndReviewLeavesAcrossAllGameLanguages() {
        for (language in GameTextLanguage.entries) {
            val original = """{"dataList":[{"id":1,"name":"EGO","desc":"旧文","content":"原文一","voicefile":"original"},{"id":2,"content":"原文二"}]}"""
            val storage = game(language, "MainUIText" to original)
            // Explicit language is used below, since the test stores all three language folders.
            val localized = object : com.example.limbuszhcn.gamefs.GameStorage by storage {
                override fun textLanguage() = com.example.limbuszhcn.gamefs.GameTextLanguageState(language, "fixture")
            }
            val local = pack("导入${language.code}", "MainUIText.json" to """{"dataList":[{"id":1,"name":"EGO","desc":"未翻訳です","voicefile":"ignored"}]}""")
            val zero = pack("零协${language.code}", "MainUIText.json" to """{"dataList":[{"id":1,"name":"上游名字","desc":"零协说明","content":"中文一"},{"id":2,"content":"中文二"}]}""")
            sources.select(local.id)
            val next = LocalSourceUpdates(sources).supplemented(local, sources.prepare(zero), localized)
            assertEquals(local, sources.read().selected) // staging never changes selection or the registry
            sources.commitLocal(next, local)
            val inspected = TranslationInspection(localized, sources.prepare(next)).read(language.resourceKey("en/EN_MainUIText.json"))
            val byText = inspected.fields.associateBy { it.text }
            assertEquals("EGO", byText.getValue("EGO").translated)
            assertEquals("未翻訳です", byText.getValue("旧文").translated)
            assertTrue(byText.getValue("旧文").needsReview)
            assertEquals("中文一", byText.getValue("原文一").translated)
            assertEquals("中文二", byText.getValue("原文二").translated)
            assertEquals("original", (inspected.document as org.json.JSONObject).getJSONArray("dataList").getJSONObject(0).getString("voicefile"))
            assertEquals(2, next.supplement?.added)
            sources.pruneUnused()
            assertNotNull(sources.prepare(next.original))
            sources.commitLocal(next.original, next)
            assertNull(sources.read().selected.supplement)
            assertEquals(local.revision, sources.read().selected.revision)
        }
    }

    @Test fun sharedLocalTranslationWinsEvenWhenOnlyOfficialHasTheOtherFile() {
        val storage = game(files = arrayOf("MainUIText" to text("同一原文"), "Tips" to text("同一原文")))
        val local = pack("导入", "MainUIText.json" to text("本地优先"))
        val zero = pack("零协", "MainUIText.json" to text("上游文本"), "Tips.json" to text("上游文本"))
        sources.select(local.id)
        val next = LocalSourceUpdates(sources).supplemented(local, sources.prepare(zero), storage)
        val fields = TranslationInspection(storage, sources.prepare(next)).read("jp/JP_Tips.json").fields
        assertEquals("本地优先", fields.single().translated)
        assertEquals(0, TranslationCoverage.scan(storage, sources.prepare(next), next.name).missing)
    }

    @Test fun newerImportedRowsAndUnavailableFilesAreNeverDropped() {
        val storage = game(files = arrayOf("MainUIText" to text("旧游戏原文")))
        val rows = """{"dataList":[{"id":1,"content":"本地译文"},{"id":999,"content":"游戏尚未下载的译文"}]}"""
        val local = pack("本地", "MainUIText.json" to rows, "StoryData/Future.json" to text("未来剧情"))
        val zero = pack("零协", "MainUIText.json" to text("上游译文"), "Tips.json" to text("零协独有"))
        sources.select(local.id)
        val next = LocalSourceUpdates(sources).supplemented(local, sources.prepare(zero), storage)
        val prepared = sources.prepare(next)
        val entry = prepared.manifest.entries.first { it.targetPath.endsWith("EN_MainUIText.json") }
        assertEquals(rows, prepared.directory.resolve(entry.cachePath).readText())
        assertEquals(3, prepared.manifest.entries.size)
    }

    private class Response(url: URL, val code: Int = 200, val bytes: ByteArray = byteArrayOf(),
        val headers: Map<String, String> = emptyMap()) : HttpURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getContentLengthLong() = headers["Content-Length"]?.toLong() ?: bytes.size.toLong()
        override fun getContentType() = headers["Content-Type"] ?: "application/zip"
        override fun getInputStream() = bytes.inputStream()
    }
    private fun subscribed(): TranslationSource {
        val local = pack("自定义", "MainUIText.json" to text("旧译文"))
        sources.updateLink(local.id, SourceUpdateLink("https://example.com/latest.zip"))
        return sources.read().selected
    }

    @Test fun updateKeepsIdentityAndIconAndChecksOnlyTheSameFinalTargetConditionally() {
        val old = subscribed()
        sources.setIcon(old.id, byteArrayOf(1))
        val source = sources.read().selected
        val bytes = zip("MainUIText.json" to text("新版译文"))
        val responses = mutableListOf<Response>()
        var version = 1
        val downloader = LocalSourceDownload({ url ->
            val response = if (url.path == "/latest.zip") Response(url, 302, headers = mapOf("Location" to "https://cdn.example.com/v$version.zip"))
            else Response(url, if (version == 2) 200 else if (responses.any { it.url == url }) 304 else 200,
                bytes, mapOf("ETag" to "\"pack\""))
            responses += response; response
        }, now = { 1_000_000 })
        val updates = LocalSourceUpdates(sources)
        val first = updates.update(source, downloader)
        assertTrue(first.changed)
        assertEquals(source.id, first.source.id); assertEquals(source.name, first.source.name); assertEquals(source.icon, first.source.icon)
        val second = updates.update(first.source, downloader)
        assertFalse(second.changed)
        assertEquals(first.source.updatedAtMillis, second.source.updatedAtMillis)
        assertNull(responses[2].getRequestProperty("If-None-Match"))
        assertEquals("\"pack\"", responses[3].getRequestProperty("If-None-Match"))
        version = 2
        updates.update(second.source, downloader)
        assertNull(responses.last().getRequestProperty("If-None-Match"))
        assertTrue(responses.all { it.closed && it.getRequestProperty("Authorization") == null && it.getRequestProperty("Cookie") == null })
    }

    @Test fun badDownloadsAndFailedSupplementLeaveWholeRegistryAndValidatorsUnchanged() {
        val source = subscribed()
        val before = cache.resolve("sources/sources.json").readText()
        val badBodies = listOf("<html>not a zip</html>".toByteArray(), zip("../escape.json" to text("错误")),
            zip("MainUIText.json" to "broken"))
        badBodies.forEach { body ->
            assertThrows(Exception::class.java) { LocalSourceUpdates(sources).update(source, LocalSourceDownload({ url -> Response(url, bytes = body) })) }
            assertEquals(before, cache.resolve("sources/sources.json").readText())
        }
        val supplemented = source.copy(supplement = OfficialSupplement(source.revision, source.archive, "1", 0))
        sources.commitLocal(supplemented, source)
        val expected = cache.resolve("sources/sources.json").readText()
        assertThrows(IllegalStateException::class.java) {
            LocalSourceUpdates(sources).update(supplemented, LocalSourceDownload({ url ->
                Response(url, bytes = zip("MainUIText.json" to text("新原包")), headers = mapOf("ETag" to "new"))
            }), supplement = { error("synthetic merge failure") })
        }
        assertEquals(expected, cache.resolve("sources/sources.json").readText())
        assertNotNull(sources.prepare(sources.read().selected))
        assertFalse(Files.exists(cache.resolve("sources/.update.download")))
    }

    @Test fun latestLinkGetsFreshContentAndReappliesSupplementBeforeCommit() {
        val storage = game(files = arrayOf("MainUIText" to """{"dataList":[{"id":1,"content":"原文甲"},{"id":2,"content":"原文乙"}]}"""))
        val local = subscribed()
        val zero = pack("零协", "MainUIText.json" to """{"dataList":[{"id":1,"content":"上游甲"},{"id":2,"content":"上游乙"}]}""")
        sources.select(local.id)
        val updates = LocalSourceUpdates(sources)
        val prepared = sources.prepare(zero)
        val supplemented = updates.supplemented(local, prepared, storage)
        sources.commitLocal(supplemented, local)
        val bytes = zip("MainUIText.json" to text("新本地甲"))
        val result = updates.update(supplemented, LocalSourceDownload({ url -> Response(url, bytes = bytes) }),
            supplement = { updates.supplemented(it, prepared, storage) })
        assertTrue(result.changed)
        val values = TranslationInspection(storage, sources.prepare(result.source)).read("jp/JP_MainUIText.json").fields.map { it.translated }
        assertEquals(listOf("新本地甲", "上游乙"), values)
        assertEquals(result.source.original.revision, result.source.updateLink?.validatorRevision)
        assertNotEquals(result.source.revision, result.source.original.revision)
    }

    @Test fun urlValidationAndCheckFrequencyDoNotAcceptPagesWithCredentialsOrUnsafeRedirects() {
        listOf("http://example.com/file.zip", "file:///file.zip", "https://user:pass@example.com/x", "https://localhost/x", "https://example.com/a#b").forEach {
            assertThrows(IllegalArgumentException::class.java) { SourceUpdateLink.downloadUrl(it) }
        }
        val link = SourceUpdateLink("https://github.com/example/repo/releases/latest/download/text.zip", checkedAt = 10_000)
        assertFalse(link.due(20_000)); assertTrue(link.due(3_610_001)); assertFalse(link.copy(automatic = false).due(Long.MAX_VALUE))
        assertTrue(link.due(1))
        assertThrows(IllegalArgumentException::class.java) {
            LocalSourceDownload({ url -> Response(url, 302, headers = mapOf("Location" to "http://example.com/insecure.zip")) })
                .fetch(link, "a".repeat(64), cache.resolve("download.tmp"))
        }
    }
}
