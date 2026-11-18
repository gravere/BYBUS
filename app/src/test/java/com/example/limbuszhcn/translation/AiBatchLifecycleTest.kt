package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.container.TranslationSnapshot
import com.example.limbuszhcn.update.sha256
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AiBatchLifecycleTest {
    @get:Rule val temp = TemporaryFolder()
    private val cache get() = temp.root.toPath().resolve("cache")
    private val jp get() = temp.root.toPath().resolve("Localize/jp")
    private val store get() = TranslationSources(cache)
    private val jobs get() = AiTaskStore(cache)
    private val storage get() = ContainerWorkspaceStorage(jp.parent)
    private val independent = AiConfig(missingOnly = false)
    private val sent = mutableListOf<String>()
    private fun rows(values: List<String>) = JSONObject().put("dataList", JSONArray().apply {
        values.forEachIndexed { i, value -> put(JSONObject().put("id", i + 1).put("content", value)) }
    }).toString()
    private fun originals(vararg values: String) {
        Files.createDirectories(jp); Files.write(jp.resolve("JP_MainUIText.json"), rows(values.toList()).toByteArray())
    }
    private fun archive(vararg values: String) = Files.createTempFile(temp.root.toPath(), "texts", ".zip").also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("MainUIText.json")); zip.write(rows(values.toList()).toByteArray()); zip.closeEntry()
        }
    }
    private fun pack(vararg values: String): PreparedTranslation {
        val path = archive(*values)
        return LocalTranslationArchive(cache).extract(path, "local-" + path.sha256())
    }
    private fun visible(source: TranslationSource) = store.prepare(source).let { prepared ->
        val entry = prepared.manifest.entries.single { it.targetPath.contains("MainUIText") }
        val doc = TranslationSnapshot.decode(TranslationFields.parse(String(Files.readAllBytes(prepared.directory.resolve(entry.cachePath))))).document as JSONObject
        doc.getJSONArray("dataList").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).optString("content") } }
    }
    private fun proof(source: TranslationSource = store.read().selected) =
        AiTranslationReuse.collect(cache, source, store.prepare(source), storage)
    private fun start(config: AiConfig, official: PreparedTranslation? = null) {
        val selected = store.read().selected
        val base = official ?: if (config.missingOnly && !selected.isOfficial) store.prepare(selected) else null
        if (official != null) jobs.create(config, TranslationSources.OFFICIAL, base, base, "b".repeat(32), 2L,
            committed = proof(selected), upstream = true)
        else jobs.createForSelection(config, store, base, base, store.read().independentInput(config), storage)
    }
    private fun execute(answer: (String) -> String): AiJobResult {
        val task = jobs.read()!!
        val client = AiTranslationClient(task.config, "fixture", AiHttpTransport { _, _, body ->
            val input = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
            val output = JSONArray()
            for (i in 0 until input.length()) {
                val row = input.getJSONObject(i); val text = row.getString("text"); sent += text
                output.put(JSONObject().put("id", row.getString("id")).put("text", answer(text)))
            }
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message",
                JSONObject().put("content", JSONObject().put("translations", output).toString())))).toString()
        })
        val (base, reference) = jobs.inputs(task)
        return AiTranslationJob(cache, storage, task.config, client).run(task.baseId, base, task.outputIdentity,
            reuse = jobs.committed(), reference = reference, session = jobs)
    }
    private fun finish(result: AiJobResult) = publishAiResult(store, jobs, jobs.read()!!, result).also { jobs.discard() }

    @Test fun independentBatchesKeepPriorFieldsAndPayOnlyForTheRemainingOriginal() {
        val text = (0..100).map { "お知らせ" + (0x4e00 + it).toChar() }; originals(*text.toTypedArray())
        start(independent); finish(execute { "已译" })
        val first = store.read().selected
        assertEquals(100, sent.size)
        val report = TranslationCoverage.scan(storage, store.prepare(first), first.name, proof(first).keys)
        assertEquals(1, report.independentEligible)
        // A user can return to the official source between batches without discarding the active independent pack.
        store.select("zero"); sent.clear(); start(independent); val next = execute { "末条" }
        assertEquals(listOf(text.last()), sent); assertEquals(100, next.carried); assertEquals(1, next.progress.completed)
        finish(next); assertEquals(first.id, store.read().selectedId); assertEquals(List(100) { "已译" } + "末条", visible(store.read().selected))
        assertEquals(101, proof().size)
        assertEquals(0, TranslationCoverage.scan(storage, store.prepare(store.read().selected), first.name, proof().keys).independentEligible)
    }

    @Test fun upstreamWithNothingToBillStillPublishesOfficialWordingAndKeepsOnlyActualAiGaps() {
        originals("同じ言葉","閉じる","先に進む")
        start(independent); finish(execute { "旧译文" }); val old = store.read().selected
        start(AiConfig(), pack("旧译文","关闭窗口")); sent.clear()
        val merged = execute { error("must not bill") }
        assertEquals(0, merged.progress.completed); assertEquals(1, merged.carried)
        assertEquals(AiPublication.Generated, finish(merged)); assertEquals(listOf("旧译文","关闭窗口","旧译文"), visible(store.read().selected))
        assertEquals(1, proof().size); assertEquals(List(3) { "旧译文" }, visible(old))
        start(AiConfig(), pack("一致","关闭窗口","前进")); val all = execute { error("must not bill") }
        assertEquals(AiPublication.Official, finish(all)); assertEquals("zero", store.read().selectedId); assertTrue(sent.isEmpty())
    }

    @Test fun freeCarryDoesNotUseUpThePaidLimitEvenWhenItComesAfterThePaidEntry() {
        originals("始める","順番です","進みます"); start(independent); finish(execute { "已有译文" })
        originals("新たに始める","順番です","進みます","追加です")
        start(AiConfig(limit = 1), pack("正式用语")); sent.clear()
        val result = execute { "新译文" }; finish(result)
        assertEquals(listOf("追加です"), sent); assertEquals(2, result.carried); assertEquals(1, result.progress.completed)
        assertEquals(listOf("正式用语","已有译文","已有译文","新译文"), visible(store.read().selected))
    }

    @Test fun aPausedBuild109UpstreamJobCanFinishWithoutPayingAgainOrLosingItsOfficialUpdate() {
        originals("閉じる","進みます"); start(independent); finish(execute { "旧 AI" })
        val old = store.read().selected
        val config = AiConfig()
        val official = pack("正式译文"); val staged = pack("正式译文","旧 AI")
        val id = AiUpstreamUpdate.identity(old, 2L, config)
        jobs.create(config, TranslationSources.OFFICIAL, staged, official, id, 2L)
        // Exact release-109 marker: no binding, upstream flag, or separate destination.
        val file = jobs.root.resolve("task.json"); val legacy = JSONObject(String(Files.readAllBytes(file)))
        listOf("binding", "upstream", "resultTarget").forEach(legacy::remove)
        Files.write(file, legacy.toString().toByteArray())
        assertFalse(jobs.recover(store.read())!!.upstream)
        val recovered = jobs.forResume(store, storage)
        assertTrue(recovered.upstream); assertEquals(official.manifest.archiveSha256, jobs.inputs(recovered).first!!.manifest.archiveSha256)
        sent.clear(); val merged = execute { error("must not bill") }
        assertEquals(AiPublication.Generated, finish(merged)); assertTrue(sent.isEmpty())
        assertEquals(listOf("正式译文","旧 AI"), visible(store.read().selected))
        assertEquals(1, proof().size); assertEquals(List(2) { "旧 AI" }, visible(old))
    }

    @Test fun explicitReviewDoesNotSubmitAnOldAnswerAsACompletedReview() {
        originals("お知らせです"); start(independent); finish(execute { "通知です" })
        val first = store.read().selected
        assertEquals(1, TranslationCoverage.scan(storage, store.prepare(first), first.name).review)
        start(AiConfig(includeMissing = false, includeReview = true)); sent.clear()
        val correction = execute { "通知" }; finish(correction)
        assertEquals(listOf("お知らせです"), sent); assertEquals(0, correction.progress.reused)
        assertEquals(listOf("通知"), visible(store.read().selected))
        assertEquals(0, TranslationCoverage.scan(storage, store.prepare(store.read().selected), first.name).review)
    }

    @Test fun resumeAfterFileReplacementPreservesSelectionAndSavesBothArchivesAndProvenance() {
        originals("確認する","続ける"); start(independent.copy(limit = 1)); finish(execute { "旧包首条" })
        val old = store.read().selected
        start(AiConfig(limit = 1))
        jobs.recover(store.read())
        val replacement = Files.newInputStream(archive("替换后的第一条","替换后的第二条")).use { store.import(it, "新文件.zip", old.id) }
        val result = execute { "新完成的第二条" }
        assertEquals(AiPublication.SeparateCopy, publishAiResult(store, jobs, jobs.read()!!, result))
        val state = store.read()
        assertEquals(replacement.revision, state.selected.revision); assertEquals(listOf("替换后的第一条","替换后的第二条"), visible(state.selected))
        val copy = state.sources.single { it.kind == "ai" }
        assertEquals(listOf("旧包首条","新完成的第二条"), visible(copy)); assertEquals(2, proof(copy).size)
        assertNull(jobs.recover(state)); assertEquals(state.undo, store.read().undo)
    }

    @Test fun aPartialReviewKeepsPreviouslyTranslatedEntriesReusableWhenSavedSeparately() {
        originals("最初です", "次です", "最後です")
        start(independent); finish(execute { "旧译です" })
        val old = store.read().selected
        start(AiConfig(limit = 1, includeMissing = false, includeReview = true))
        jobs.recover(store.read())
        Files.newInputStream(archive("我的新文件")).use { store.import(it, "新文件.zip", old.id) }
        sent.clear()
        assertEquals(AiPublication.SeparateCopy, finish(execute { "已核对" }))
        assertEquals(listOf("最初です"), sent)
        val copy = store.read().sources.single { it.kind == "ai" }
        assertEquals(listOf("已核对", "旧译です", "旧译です"), visible(copy))
        assertEquals(3, proof(copy).size)
    }

    @Test fun sourceLimitBlocksNewRequestsButAllowsResumeAndUpdatingAnExistingPack() {
        originals("一つ目です", "二つ目です")
        val filler = archive("本地译文")
        fun addLocal() = Files.newInputStream(filler).use { store.import(it, "本地.zip") }
        val locals = List(31) { addLocal() }
        store.select("zero")
        start(independent.copy(limit = 1))
        val blocked = assertThrows(IllegalArgumentException::class.java) { execute { error("unexpected request") } }
        assertTrue(blocked.message.orEmpty().contains("31"))
        assertTrue(sent.isEmpty())
        store.remove(locals.first().id)
        finish(execute { "第一条" })
        val saved = store.read().selected
        assertEquals(32, store.read().sources.size)
        sent.clear(); start(independent.copy(limit = 1)); finish(execute { "第二条" })
        assertEquals(saved.id, store.read().selectedId)
        assertEquals(listOf("二つ目です"), sent)
        start(AiConfig(), pack("正式一", "正式二"))
        assertEquals(AiPublication.Official, finish(execute { error("unexpected request") }))
    }

    @Test fun newerOfficialBaseWhilePausedKeepsTheOfficialSelectionAndTheResultIsSavedSeparately() {
        originals("確認します")
        cachedOfficialFixture(cache, "101", rows(listOf("旧正式"))); start(independent)
        jobs.recover(store.read()); cachedOfficialFixture(cache, "102", rows(listOf("新正式")))
        assertEquals(AiPublication.SeparateCopy, finish(execute { "独立结果" }))
        assertEquals("zero", store.read().selectedId)
        assertEquals(listOf("独立结果"), visible(store.read().sources.single { it.kind == "ai" }))
    }

    @Test fun undoDeletesOnlyTheDiscardedPackCacheAndKeepsThePreviousRevisionReusable() {
        originals("一つ目です", "二つ目です")
        start(independent.copy(limit = 1)); finish(execute { "第一条" })
        val previous = store.read().selected
        val job = cache.resolve("ai-jobs/" + previous.id.removePrefix("ai-"))
        start(independent.copy(limit = 1)); finish(execute { "第二条" })
        store.undoGenerated()
        assertTrue(Files.isDirectory(job)); assertEquals(1, proof().size)
        store.remove(previous.id)
        assertFalse(Files.exists(job))
        start(independent); finish(execute { "新译文" })
        val fresh = store.read().selected
        val freshJob = cache.resolve("ai-jobs/" + fresh.id.removePrefix("ai-"))
        assertTrue(Files.isDirectory(freshJob))
        store.undoGenerated()
        assertFalse(Files.exists(freshJob)); assertEquals("zero", store.read().selectedId)
    }

    @Test fun aDamagedGlossaryFileDoesNotBlockOtherTextAndOversizeFieldsNeverBecomePayable() {
        originals("進みます", "あ".repeat(6001)); Files.write(jp.resolve("JP_Characters.json"), "{".toByteArray())
        val base = pack("前进")
        val report = TranslationCoverage.scan(storage, base, "fixture")
        assertEquals(1, report.unreadable); assertEquals(1, report.independentEligible)
        val state = CoverageState(report = report, fingerprint = "fixture")
        assertEquals(1, state.eligible(independent)); assertFalse(state.copy(error = "读取失败").canGenerate(independent))
        jobs.create(independent, TranslationSources.OFFICIAL, null, base)
        val valid = execute { "前进" }; assertEquals(listOf("進みます"), sent); finish(valid)
        assertEquals(0, TranslationCoverage.scan(storage, store.prepare(store.read().selected), "AI", proof().keys).independentEligible)
    }
}
