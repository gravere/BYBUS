package com.example.limbuszhcn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.gamefs.GameTextLanguage
import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.translation.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class AiEntryDeviceTest {
    @Test fun libraryFiltersPersistsFreezesAndDeletesWithoutResurrectingAi() {
        val parent = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath()
        val root = Files.createTempDirectory(parent, "ai-entry-test-")
        try {
            val folder = Files.createDirectories(root.resolve("Localize/jp"))
            val file = folder.resolve("JP_MainUIText.json")
            val raw = """{"dataList":[{"id":1,"content":"確認です"},{"id":2,"content":"お知らせです"},{"id":3,"content":"未翻訳です"}]}"""
            Files.write(file, raw.toByteArray())
            val storage = ContainerWorkspaceStorage(folder.parent)
            val sources = TranslationSources(root); val store = AiEntryStore(root)
            val report = store.inspect(sources, storage, null)
            assertEquals(3, report.total); assertEquals(0, report.aiCount)
            assertEquals(report.key, AiEntryStore(root).inspect(sources, storage, null).key)
            assertEquals(0, store.entries(report, AiEntryFilter(search = "' OR 1=1 --"), 0).total)
            assertEquals(1, store.entries(report, AiEntryFilter(search = "#2"), 0).total)
            val request = AiEntryRequest(report, AiEntryFilter(AiEntryKind.Pending), count = 3)
            val plan = store.plan(request, 2); assertEquals(2, plan.total); assertEquals(3, plan.available)
            val tasks = AiTaskStore(root); val config = AiConfig(missingOnly = false, limit = 2)
            val task = tasks.create(config, TranslationSource("global", "全局 AI 翻译", independent = true), null, null, explicitPlan = plan)
            var requests = 0
            val client = AiTranslationClient(config, "fixture", AiHttpTransport { _, _, body ->
                requests++
                val input = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
                val output = JSONArray()
                for (i in 0 until input.length()) output.put(JSONObject().put("id", input.getJSONObject(i).getString("id")).put("text", "中文译文"))
                JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message",
                    JSONObject().put("content", JSONObject().put("translations", output).toString())))).toString()
            })
            val result = AiTranslationJob(root, storage, config, client).run(task.baseId, null, task.outputIdentity, session = tasks)
            publishAiResult(sources, tasks, tasks.read()!!, result); tasks.discard()
            val source = sources.read().selected; val index = store.inspect(sources, storage, source.id)
            assertEquals(2, index.aiCount); assertEquals(1, index.counts[AiEntryKind.Pending])
            val row = store.entries(index, AiEntryFilter(AiEntryKind.Ai), 0).rows.first()
            val remove = AiEntryRequest(index, ids = setOf(row.id), count = 1)
            assertFalse(AiEntryRemoval.remove(root, sources, storage, remove))
            val after = store.inspect(sources, storage, source.id)
            assertEquals(1, after.aiCount); assertEquals(2, after.counts[AiEntryKind.Pending])
            assertTrue(store.entries(after, AiEntryFilter(AiEntryKind.Pending), 0).rows.any { it.id == row.id })
            assertEquals(1, AiTranslationReuse.collect(root, sources.read().selected, sources.prepare(sources.read().selected), storage).size)
            assertTrue(runCatching { store.validate(index, sources, storage, true) }.isFailure)
            val pending = store.entries(after, AiEntryFilter(AiEntryKind.Pending), 0).rows.first()
            assertTrue(runCatching { store.plan(AiEntryRequest(after, ids = setOf(pending.id), count = 1), 1000, true) }.isFailure)
            val last = store.entries(after, AiEntryFilter(AiEntryKind.Ai), 0).rows.single()
            assertTrue(AiEntryRemoval.remove(root, sources, storage, AiEntryRequest(after, ids = setOf(last.id), count = 1)))
            assertEquals("zero", sources.read().selectedId); assertEquals(1, sources.read().sources.size)
            assertEquals(1, requests); assertEquals(raw, String(Files.readAllBytes(file)))
        } finally { AtomicFiles.deleteOwnedTree(parent, root) }
    }
}
