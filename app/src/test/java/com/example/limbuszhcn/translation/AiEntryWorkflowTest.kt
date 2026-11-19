package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.gamefs.GameTextLanguage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CancellationException

class AiEntryWorkflowTest {
    @get:Rule val temp = TemporaryFolder()
    private val root get() = temp.root.toPath()
    private val cache get() = root.resolve("cache")
    private val sources get() = TranslationSources(cache)
    private val tasks get() = AiTaskStore(cache)
    private val storage get() = ContainerWorkspaceStorage(root.resolve("Localize"))
    private val config = AiConfig(missingOnly = false, limit = 10)
    private var calls = 0
    private fun fixture(count: Int = 3) {
        val dir = Files.createDirectories(root.resolve("Localize/jp"))
        Files.write(dir.resolve("JP_MainUIText.json"), JSONObject().put("dataList", JSONArray().apply {
            repeat(count) { i -> put(JSONObject().put("id", i).put("content", "原文です$i")) }
        }).toString().toByteArray())
    }
    private fun plan(ids: Set<String>? = null, limit: Int = 10): AiTaskPlan {
        val r = TranslationInspection(storage, null).read("jp/JP_MainUIText.json")
        val fields = r.fields.filter { ids == null || it.id in ids }
        return AiTaskPlan(mapOf("jp/JP_MainUIText.json" to fields.take(limit).map { it.id }.toSet()),
            mapOf("jp/JP_MainUIText.json" to AiTranslationJob.digest(r.raw)), fields.size)
    }
    private fun start(source: TranslationSource? = null, selected: AiTaskPlan = plan(), settings: AiConfig = config) {
        val base = source?.let(sources::prepare)
        val proof = source?.let { AiTranslationReuse.collect(cache, it, base!!, storage) }.orEmpty()
        tasks.create(settings, source ?: TranslationSource("global", "全局 AI 翻译", independent = true), base, base,
            committed = proof, explicitPlan = selected)
    }
    private fun run(word: String = "译文", failAfter: Int = Int.MAX_VALUE): AiJobResult {
        val task = tasks.read()!!; val (base, reference) = tasks.inputs(task)
        val client = AiTranslationClient(task.config, "fixture", AiHttpTransport { _, _, body ->
            if (++calls > failAfter) throw CancellationException()
            val input = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
            val translated = JSONArray()
            for (i in 0 until input.length()) input.getJSONObject(i).let {
                translated.put(JSONObject().put("id", it.getString("id")).put("text", it.getString("text").replace("原文です", word)))
            }
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message",
                JSONObject().put("content", JSONObject().put("translations", translated).toString())))).toString()
        })
        return AiTranslationJob(cache, storage, task.config, client).run(task.baseId, base, task.outputIdentity,
            tasks.committed(), reference = reference, session = tasks)
    }
    private fun finish(result: AiJobResult): TranslationSource {
        publishAiResult(sources, tasks, tasks.read()!!, result); tasks.discard(); return sources.read().selected
    }
    @Test fun globalNeedsNoBaseAndCountsActualAiFields() {
        fixture(); start(selected = plan(limit = 1), settings = config.copy(limit = 1))
        assertNull(tasks.inputs(tasks.read()!!).first)
        val result = run(); val pack = finish(result)
        assertEquals(1, result.aiEntryCount); assertEquals(1, pack.aiEntryCount)
        assertEquals("jp", pack.language); assertEquals("global", pack.baseId); assertNull(pack.officialVersion)
        assertTrue(pack.independent); assertFalse(Files.exists(cache.resolve("downloads")))
        val rows = mutableListOf<AiEntry>()
        val counts = AiEntryScan.scan(storage, sources.prepare(pack), GameTextLanguage.Japanese,
            AiTranslationReuse.collect(cache, pack, sources.prepare(pack), storage), add = rows::add).first
        assertEquals(1, counts[AiEntryKind.Ai]); assertEquals(2, counts[AiEntryKind.Pending]); assertEquals(0, counts[AiEntryKind.Base])
        assertFalse(AiUpstreamUpdate.shouldOffer(pack, Long.MAX_VALUE, 0, true))
    }
    @Test fun explicitRetranslationBillsSelectedEntryAndPreservesOtherAi() {
        fixture(); start(); val old = finish(run())
        val fields = TranslationInspection(storage, sources.prepare(old)).read("jp/JP_MainUIText.json").fields
        start(old, plan(setOf(fields[1].id)))
        val result = run("重译"); val current = finish(result)
        assertEquals(1, result.progress.completed); assertEquals(0, result.progress.reused)
        assertEquals(3, result.aiEntryCount)
        val text = TranslationInspection(storage, sources.prepare(current)).read("jp/JP_MainUIText.json").fields.map { it.translated }
        assertEquals(listOf("译文0", "重译1", "译文2"), text)
        assertEquals(3, AiTranslationReuse.collect(cache, current, sources.prepare(current), storage).size)
    }
    @Test fun explicitResumeUsesOnlyItsNewResponsesAndKeepsFrozenLimit() {
        fixture(12); start(selected = plan(limit = 10))
        assertThrows(CancellationException::class.java) { run(failAfter = 1) }
        assertEquals(8, tasks.read()!!.progress.completed)
        tasks.recover(sources.read())
        val done = run()
        assertEquals(8, done.progress.reused); assertEquals(2, done.progress.completed); assertEquals(2, done.remaining)
        assertEquals(10, finish(done).aiEntryCount)
    }
    @Test fun retranslatingOneEntryPreservesUnprovenArchiveTextWhenOldCacheIsMissing() {
        fixture(); start(); val old = finish(run())
        val job = cache.resolve("ai-jobs/${old.id.removePrefix("ai-")}")
        com.example.limbuszhcn.storage.AtomicFiles.deleteOwnedTree(cache, job)
        val first = TranslationInspection(storage, sources.prepare(old)).read("jp/JP_MainUIText.json").fields.first()
        start(old, plan(setOf(first.id)))
        val current = finish(run("重译"))
        val fields = TranslationInspection(storage, sources.prepare(current)).read("jp/JP_MainUIText.json").fields
        assertEquals(listOf("重译0", "译文1", "译文2"), fields.map { it.translated })
        assertEquals(1, current.aiEntryCount)
    }
    @Test fun stalePlanStopsBeforePaidRequest() {
        fixture(); start(); fixture(4)
        assertThrows(IllegalArgumentException::class.java) { run() }
        assertEquals(0, calls)
    }
    @Test fun generatedNamesAndMetadataRemainUniqueAcrossRestart() {
        fixture(); start(); val first = finish(run())
        sources.select("zero"); start(); val second = finish(run())
        assertNotEquals(first.id, second.id); assertEquals(first.name + " (2)", second.name)
        assertEquals("全局 AI 翻译", second.baseName)
        sources.rename(second.id, first.name)
        assertEquals(second.name, sources.read().selected.name)
        assertNotEquals(AiTaskStore.identityFor("zero", config, "en"), AiTaskStore.identityFor("zero", config, "kr"))
    }
    @Test fun keyOrderingRejectsMissingDuplicateAndUnknownIds() {
        AiKeyOrder.validate(listOf("b", "a"), listOf("a", "b"))
        for (bad in listOf(listOf("a"), listOf("a", "a"), listOf("a", "c")))
            assertThrows(IllegalArgumentException::class.java) { AiKeyOrder.validate(bad, listOf("a", "b")) }
        val choices = listOf(AiKeyChoice("a", "个人", "https://example.org", "model"), AiKeyChoice("b", "备用", "https://example.org", "model"))
        assertEquals("新名称", AiKeyOrder.name(" 新名称 ", "a", choices))
        assertThrows(IllegalArgumentException::class.java) { AiKeyOrder.name("备用", "a", choices) }
    }
}
