package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.update.sha256
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AiTaskStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val cache get() = temp.root.toPath().resolve("cache")
    private val localize get() = temp.root.toPath().resolve("Localize")
    private fun document(text: (Int) -> String, count: Int = 28) = JSONObject().put("dataList", JSONArray().apply {
        repeat(count) { put(JSONObject().put("id", it).put("content", text(it))) }
    }).toString()
    private fun fixture(): Pair<TranslationSource, PreparedTranslation> {
        Files.createDirectories(localize.resolve("jp"))
        val original = document({ "確認する $it" })
        Files.write(localize.resolve("jp/JP_MainUIText.json"), original.toByteArray())
        Files.write(localize.resolve("jp/JP_Missing.json"), document({ "待機する $it" }).toByteArray())
        val zip = temp.root.toPath().resolve("source.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use {
            it.putNextEntry(ZipEntry("MainUIText.json")); it.write(original.toByteArray()); it.closeEntry()
        }
        val sources = TranslationSources(cache)
        val source = Files.newInputStream(zip).use { sources.import(it, "fixture.zip") }
        return source to sources.prepare(source)
    }
    private fun client(config: AiConfig, seen: MutableList<String>, wrap: (String) -> String = { it }, before: () -> Unit = {}) =
        AiTranslationClient(config, "non-secret-fixture", AiHttpTransport { _, _, request ->
            before()
            val input = JSONArray(JSONObject(request).getJSONArray("messages").getJSONObject(1).getString("content"))
            val result = JSONArray()
            repeat(input.length()) { index ->
                val row = input.getJSONObject(index); val text = row.getString("text"); seen += text
                result.put(JSONObject().put("id", row.getString("id")).put("text", text.replace("確認する", "确认").replace("待機する", "等待")))
            }
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", wrap(JSONObject().put("translations", result).toString()))))).toString()
        })

    @Test fun formatFailureThenFencedResponseResumeDoesNotResendTheCompletedBatch() {
        val (source, base) = fixture()
        val config = AiConfig(includeMissing = false, includeReview = true, limit = 26)
        val tasks = AiTaskStore(cache); val task = tasks.create(config, source, base, base)
        var calls = 0
        val broken = client(config, mutableListOf(), wrap = { text ->
            if (++calls == 2) "```json\n{truncated" else text
        })
        assertThrows(AiResponseFormatException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, broken)
                .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        assertEquals(24, tasks.read()!!.progress.completed)
        assertEquals(26, tasks.read()!!.plan!!.total)
        val recovered = AiTaskStore(cache)
        assertEquals(AiTaskPhase.Paused, recovered.recover(TranslationSources(cache).read())!!.phase)
        val resumedRequests = mutableListOf<String>()
        val result = AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config,
            client(config, resumedRequests, wrap = { "```json\n$it\n```" }))
            .run(source.id, recovered.prepared("base"), task.outputIdentity, session = recovered)
        assertEquals(24, result.progress.reused)
        assertEquals(2, result.progress.completed)
        assertEquals(2, resumedRequests.size)
        assertEquals(26, result.progress.limit)
    }

    @Test fun timeoutThenProcessRecoveryKeepsExactReviewScopeAndDoesNotRebillCompletedBatch() {
        val (source, base) = fixture()
        val config = AiConfig(includeMissing = false, includeReview = true, limit = 26)
        val tasks = AiTaskStore(cache); val task = tasks.create(config, source, base, base)
        val seen = mutableListOf<String>(); var calls = 0
        val http = client(config, seen) { if (++calls == 2) throw SocketTimeoutException() }
        assertThrows(SocketTimeoutException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, http)
                .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        assertEquals(24, tasks.read()!!.progress.completed)
        assertEquals(26, tasks.read()!!.plan!!.total)
        assertEquals(28, tasks.read()!!.plan!!.available) // The missing file must never enter a Review task.
        val recovered = AiTaskStore(cache)
        assertEquals(AiTaskPhase.Paused, recovered.recover(TranslationSources(cache).read())!!.phase)
        assertEquals(2, calls) // Merely loading/recovering a task cannot request anything.
        val resumed = AiTranslationJob(cache, ContainerWorkspaceStorage(localize), recovered.read()!!.config, client(config, seen))
            .run(source.id, recovered.prepared("base"), task.outputIdentity, session = recovered)
        assertEquals(26, seen.size); assertTrue(seen.all { it.startsWith("確認する") })
        assertEquals(24, resumed.progress.reused); assertEquals(2, resumed.progress.completed)
        assertEquals(2, resumed.remaining)
        assertFalse(String(Files.readAllBytes(recovered.root.resolve("task.json"))).contains("non-secret-fixture"))
    }

    @Test fun pauseAfterAResponsePersistsItAndResumeHonorsTheOriginalLimit() {
        val (source, base) = fixture(); val config = AiConfig(includeMissing = false, includeReview = true, limit = 26)
        val tasks = AiTaskStore(cache); val task = tasks.create(config, source, base, base)
        var paused = false; val seen = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, client(config, seen) { paused = true }, { paused })
                .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        assertEquals(24, tasks.read()!!.progress.completed)
        paused = false
        val fresh = AiTaskStore(cache); fresh.recover(TranslationSources(cache).read())
        val result = AiTranslationJob(cache, ContainerWorkspaceStorage(localize), fresh.read()!!.config, client(config, seen))
            .run(source.id, fresh.prepared("base"), task.outputIdentity, session = fresh)
        assertEquals(26, result.progress.completed + result.progress.reused)
        assertEquals(26, seen.size)
    }

    @Test fun changedOriginalIsRejectedBeforeSendingAnyResumedRequest() {
        val (source, base) = fixture(); val config = AiConfig(includeMissing = false, includeReview = true, limit = 26)
        val tasks = AiTaskStore(cache); val task = tasks.create(config, source, base, base)
        var paused = false
        assertThrows(CancellationException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, client(config, mutableListOf()) { paused = true }, { paused })
                .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        Files.write(localize.resolve("jp/JP_MainUIText.json"), document({ "更新した $it" }).toByteArray())
        val requests = mutableListOf<String>()
        assertThrows(IllegalArgumentException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, client(config, requests))
                .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        assertTrue(requests.isEmpty()); assertTrue(tasks.exists())
    }

    @Test fun cancellationDeletesOnlyThePendingTaskAndCannotRestoreItAfterRestart() {
        val (source, base) = fixture(); val config = AiConfig()
        val tasks = AiTaskStore(cache); tasks.create(config, source, base, base)
        Files.createDirectories(tasks.work); Files.write(tasks.work.resolve("partial.json"), "{}".toByteArray())
        val before = TranslationSources(cache).read()
        tasks.requestDiscard()
        assertNull(AiTaskStore(cache).recover(before))
        assertFalse(Files.exists(tasks.root)); assertEquals(before, TranslationSources(cache).read())
        assertNotNull(TranslationSources(cache).prepare(source))
    }

    @Test fun baseSnapshotSurvivesSourceRemovalAndCommittedRecoveryDoesNotReplaceUndoPoint() {
        val (source, base) = fixture(); val config = AiConfig(includeMissing = false, includeReview = true, limit = 2)
        val sources = TranslationSources(cache); val tasks = AiTaskStore(cache); val task = tasks.create(config, source, base, base)
        sources.remove(source.id); sources.pruneUnused()
        val result = AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, client(config, mutableListOf()))
            .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        tasks.write(tasks.read()!!.copy(phase = AiTaskPhase.Applying, resultHash = result.archive.sha256()))
        tasks.retainCommittedCache(task.outputIdentity)
        sources.addGenerated(result.archive, source.id, task.outputIdentity, "AI fixture")
        val committed = sources.read()
        assertNull(AiTaskStore(cache).recover(committed))
        assertEquals(committed.undo, sources.read().undo)
        assertEquals(committed.selectedId, sources.read().selectedId)
    }
}
