package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.*
import com.example.limbuszhcn.gamefs.GameTextLanguage
import com.example.limbuszhcn.gamefs.GameTextLanguageState
import com.example.limbuszhcn.update.sha256
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class AiNoticeWorkflowTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun aMissingOrInvalidChunkDoesNotDiscardOtherVerifiedNoticeText() {
        val cache = Files.createDirectories(temp.root.toPath().resolve("partial"))
        val first = "Notice body. ".repeat(440) + "\n"
        val original = first + "Updated reward details. ".repeat(300)
        val parts = requireNotNull(NoticeTexts.chunks(original))
        assertTrue(parts.size > 1)
        val notices = NoticeTexts(cache)
        notices.capture(GameTextLanguage.English, original)
        val translated = NoticeTexts.document(listOf("公告正文。\n"))
        val archive = cache.resolve("input.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use {
            it.putNextEntry(java.util.zip.ZipEntry(notices.key(GameTextLanguage.English, original)))
            it.write(translated.toString().toByteArray()); it.closeEntry()
        }
        val pack = LocalTranslationArchive(cache).extract(archive, "local-${archive.sha256()}")
        val snapshot = LiveTranslationStore(cache).prepare(pack.directory, pack.manifest) { GameTextLanguage.Japanese.resourceKey(it) }
        NoticeRuntime(cache, snapshot).use { runtime ->
            assertEquals("公告正文。\n" + parts.drop(1).joinToString(""), runtime.translate("en", original))
            assertNull(runtime.translate("en", original + " A different notice"))
            assertNull(runtime.translate("kr", original))
        }
    }

    @Test fun newNoticeInvalidatesSavedCoverageWithoutChangingTheTranslationPackage() {
        val cache = Files.createDirectories(temp.root.toPath().resolve("freshness"))
        val language = GameTextLanguage.English
        val notices = NoticeTexts(cache)
        notices.capture(language, "First public notice")
        val storage = NoticeGameStorage(ContainerWorkspaceStorage(Files.createDirectories(cache.resolve("Localize")),
            language = { GameTextLanguageState(language, "en") }), cache)
        val source = TranslationSource("local", "本地包", revision = "same-package")
        val store = CoverageStore(cache)
        val input = store.inputs(source, storage)
        val report = CoverageReport(source.name, 123456L, 1, 1, 0, 1, 0, 1, 0, emptyList(), language = language)
        store.save(input, report, store.beginCheck(input))
        assertFalse(store.read(store.inputs(source, storage))!!.needsCheck)
        notices.capture(language, "First public notice")
        assertEquals(123456L, store.read(store.inputs(source, storage))!!.report.checkedAt)
        notices.capture(language, "Second public notice")
        assertEquals(2, storage.textResources(language).size)
        assertTrue(store.read(store.inputs(source, storage))!!.needsCheck)
        assertEquals(123456L, store.read(store.inputs(source, storage))!!.report.checkedAt)
    }

    @Test fun announcementPassesRequestPersistenceAndActualWholeNoticeLookupWithoutAnotherPaidCall() {
        val root = temp.root.toPath(); val cache = root.resolve("cache")
        val original = "Period: Nov. 11th 2026 (KST)\nWALPURGISNACHT-X"
        val expected = "期间：2026年11月11日（KST）\n第10届瓦尔普吉斯之夜"
        val notices = NoticeTexts(cache)
        notices.capture(GameTextLanguage.English, original)
        val localize = Files.createDirectories(root.resolve("Localize"))
        val storage = NoticeGameStorage(ContainerWorkspaceStorage(localize, language = {
            GameTextLanguageState(GameTextLanguage.English, "en")
        }), cache)
        val config = AiConfig(missingOnly = false, limit = 100)
        val tasks = AiTaskStore(cache)
        val task = tasks.create(config, TranslationSource("global", "全局 AI 翻译", independent = true), null, null, language = "en")
        var calls = 0
        val client = AiTranslationClient(config, "non-secret-fixture", AiHttpTransport { _, _, body ->
            calls++
            val input = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals(1, input.length())
            val output = JSONArray().put(JSONObject().put("id", input.getJSONObject(0).getString("id"))
                .put("text", "期间：⟦P1⟧年11月⟦P0⟧日（KST）⟦P2⟧第10届瓦尔普吉斯之夜"))
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message",
                JSONObject().put("content", JSONObject().put("translations", output).toString())))).toString()
        })
        AiTranslationJob(cache, storage, config, client).run(task.baseId, null, task.outputIdentity, session = tasks)
        val fresh = AiTaskStore(cache)
        fresh.recover(TranslationSources(cache).read())
        val result = AiTranslationJob(cache, storage, config, client).run(task.baseId, null, task.outputIdentity, session = fresh)
        assertEquals(1, calls); assertEquals(1, result.progress.reused)
        val pack = LocalTranslationArchive(cache).extract(result.archive, "local-${result.archive.sha256()}")
        val snapshot = LiveTranslationStore(cache).prepare(pack.directory, pack.manifest) { GameTextLanguage.Japanese.resourceKey(it) }
        assertEquals(0, snapshot.labels)
        NoticeRuntime(cache, snapshot).use { runtime ->
            assertEquals(expected, runtime.translate("en", original))
            assertNull(runtime.translate("en", original + " Changed"))
            assertNull(runtime.translate("jp", original))
        }
        assertTrue(TranslationInspection(storage, pack).sharedTranslations().isEmpty())
    }
}
