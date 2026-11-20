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
