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
    @Test fun equivalentEnglishNumbersSurvivePartialFailureAndProcessRecoveryWithoutRebilling() {
        Files.createDirectories(localize.resolve("en"))
        Files.write(localize.resolve("en/EN_MainUIText.json"), document({ "Attack two enemies" }, 3).toByteArray())
        val storage = ContainerWorkspaceStorage(localize, language = {
            com.example.limbuszhcn.gamefs.GameTextLanguageState(com.example.limbuszhcn.gamefs.GameTextLanguage.English, "en")
        })
        val config = AiConfig(missingOnly = false, limit = 3)
        val tasks = AiTaskStore(cache)
        val task = tasks.create(config, TranslationSource("global", "全局 AI 翻译", independent = true), null, null, language = "en")
        val counts = mutableListOf<Int>()
        val client = AiTranslationClient(config, "non-secret-fixture", AiHttpTransport { _, _, body ->
            val rows = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
            counts += rows.length()
            val answer = JSONArray()
            repeat(rows.length()) { i -> answer.put(JSONObject().put("id", rows.getJSONObject(i).getString("id"))
                .put("text", if (counts.size == 1 && i == 2) "攻击999个敌人" else "攻击2个敌人")) }
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message",
                JSONObject().put("content", JSONObject().put("translations", answer).toString())))).toString()
        })
        val error = assertThrows(AiBatchValidationException::class.java) {
            AiTranslationJob(cache, storage, config, client).run(task.baseId, null, task.outputIdentity, session = tasks)
        }
        assertEquals(2, tasks.read()!!.progress.completed)
        assertTrue(error.message!!.contains("en/EN_MainUIText.json /dataList/2/content"))
        val fresh = AiTaskStore(cache)
        fresh.recover(TranslationSources(cache).read())
        val result = AiTranslationJob(cache, storage, config, client).run(task.baseId, null, task.outputIdentity, session = fresh)
        assertEquals(listOf(3, 1), counts)
        assertEquals(2, result.progress.reused); assertEquals(1, result.progress.completed)
        java.util.zip.ZipFile(result.archive.toFile()).use { zip ->
            val rows = JSONObject(zip.getInputStream(zip.getEntry("en/EN_MainUIText.json")).bufferedReader().readText()).getJSONArray("dataList")
            repeat(3) { assertEquals("攻击2个敌人", rows.getJSONObject(it).getString("content")) }
        }
    }
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

    @Test fun legacyTaskChangesProvidersTwiceWithoutRebillingCompletedAnswersOrExpandingItsPlan() {
        val (source, base) = fixture()
        val original = AiConfig(endpoint = "https://first.example/v1", model = "first-model", includeMissing = false, includeReview = true, limit = 6)
        val tasks = AiTaskStore(cache); val task = tasks.create(original, source, base, base)
        var calls = 0
        val firstSeen = mutableListOf<String>()
        val firstIds = mutableSetOf<String>()
        assertThrows(AiTranslationException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), original,
                client(original, firstSeen, wrap = { text ->
                    val rows = JSONObject(text).getJSONArray("translations")
                    repeat(rows.length()) { firstIds += rows.getJSONObject(it).getString("id") }
                    text
                }, before = { if (++calls == 2) throw SocketTimeoutException() }))
                .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        assertEquals(4, firstSeen.size)
        assertFalse(JSONObject(String(Files.readAllBytes(tasks.root.resolve("task.json")), Charsets.UTF_8)).has("cacheConnection")) // Existing build 138 task.
        val sources = TranslationSources(cache)
        val priorSources = sources.read()
        val fresh = AiTaskStore(cache)
        val recovered = fresh.recover(sources.read())!!
        val selected = AiConfig(endpoint = "https://second.example/v1", model = "second-model", missingOnly = false,
            limit = MAX_AI_TASK_ENTRIES, prompt = "Do not use this new-task prompt", stream = false)
        val switched = fresh.write(recovered.withConnection(selected, AiPrice(3.0, 9.0)))
        assertEquals(recovered.plan, switched.plan); assertEquals(task.outputIdentity, switched.outputIdentity)
        assertEquals(recovered.binding, switched.binding)
        assertEquals(original.copy(endpoint = selected.endpoint, model = selected.model, stream = false), switched.config)
        assertEquals(AiCacheConnection(original.endpoint, original.model), switched.cacheConnection)
        val sent = mutableListOf<String>()
        fun via(config: AiConfig, wrongLast: Boolean) = AiTranslationClient(config, "fixture-${config.model}", AiHttpTransport { url, key, raw ->
            assertEquals(config.endpoint + "/chat/completions", url)
            assertEquals("fixture-${config.model}", key)
            val request = JSONObject(raw); assertEquals(config.model, request.getString("model"))
            assertTrue(request.getJSONArray("messages").getJSONObject(0).getString("content").endsWith(original.prompt))
            val rows = JSONArray(request.getJSONArray("messages").getJSONObject(1).getString("content"))
            val result = JSONArray()
            repeat(rows.length()) { i ->
                val row = rows.getJSONObject(i); sent += row.getString("id")
                result.put(JSONObject().put("id", row.getString("id"))
                    .put("text", if (wrongLast && i == rows.length() - 1) "错误999" else row.getString("text").replace("確認する", "确认")))
            }
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", JSONObject().put("translations", result).toString())))).toString()
        })
        assertThrows(AiBatchValidationException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), switched.config, via(switched.config, true))
                .run(source.id, fresh.prepared("base"), task.outputIdentity, session = fresh)
        }
        assertEquals(2, sent.size)
        assertTrue(sent.none { it in firstIds })
        val restarted = AiTaskStore(cache)
        val next = restarted.recover(sources.read())!!.withConnection(selected.copy(model = "third-model"), AiPrice(5.0, 10.0))
        restarted.write(next)
        assertEquals(switched.cacheConnection, restarted.read()!!.cacheConnection)
        sent.clear()
        val result = AiTranslationJob(cache, ContainerWorkspaceStorage(localize), next.config, via(next.config, false))
            .run(source.id, restarted.prepared("base"), task.outputIdentity, session = restarted)
        assertEquals(1, sent.size)
        assertEquals(5, result.progress.reused); assertEquals(1, result.progress.completed)
        assertEquals(6, result.progress.limit); assertEquals(6, result.aiEntryCount)
        java.util.zip.ZipFile(result.archive.toFile()).use { zip ->
            val rows = JSONObject(zip.getInputStream(zip.getEntry("jp/JP_MainUIText.json")).bufferedReader().readText()).getJSONArray("dataList")
            repeat(6) { assertEquals("确认 $it", rows.getJSONObject(it).getString("content")) }
        }
        assertEquals(priorSources, TranslationSources(cache).read()) // The result is still pending publication.
    }

    @Test fun formatFailureThenFencedResponseResumeDoesNotResendTheCompletedBatch() {
        val (source, base) = fixture()
        val config = AiConfig(includeMissing = false, includeReview = true, limit = 6)
        val tasks = AiTaskStore(cache); val task = tasks.create(config, source, base, base)
        var calls = 0
        val broken = client(config, mutableListOf(), wrap = { text ->
            if (++calls == 2) "```json\n{truncated" else text
        })
        assertThrows(AiResponseFormatException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, broken)
                .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        assertEquals(4, tasks.read()!!.progress.completed)
        assertEquals(6, tasks.read()!!.plan!!.total)
        val recovered = AiTaskStore(cache)
        assertEquals(AiTaskPhase.Paused, recovered.recover(TranslationSources(cache).read())!!.phase)
        val resumedRequests = mutableListOf<String>()
        val result = AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config,
            client(config, resumedRequests, wrap = { "```json\n$it\n```" }))
            .run(source.id, recovered.prepared("base"), task.outputIdentity, session = recovered)
        assertEquals(4, result.progress.reused)
        assertEquals(2, result.progress.completed)
        assertEquals(2, resumedRequests.size)
        assertEquals(6, result.progress.limit)
    }

    @Test fun oneInvalidAnswerSavesItsValidSiblingsAndRecoveryRequestsOnlyUnfinishedEntries() {
        val (source, base) = fixture()
        val config = AiConfig(includeMissing = false, includeReview = true, limit = 6)
        val tasks = AiTaskStore(cache); val task = tasks.create(config, source, base, base)
        var rejectedId = ""
        val broken = client(config, mutableListOf(), wrap = { body ->
            val data = JSONObject(body)
            val row = data.getJSONArray("translations").getJSONObject(2)
            rejectedId = row.getString("id")
            row.put("text", row.getString("text") + "新增999点")
            data.toString()
        })
        val before = TranslationSources(cache).read()
        val error = assertThrows(AiBatchValidationException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, broken)
                .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        assertEquals(3, tasks.read()!!.progress.completed)
        assertEquals(1, tasks.read()!!.progress.requests)
        assertEquals(rejectedId, error.rejected.single().id)
        assertTrue(error.message!!.contains("已保存 3 条"))
        assertEquals(before, TranslationSources(cache).read())
        assertFalse(Files.exists(tasks.work.resolve("ai-jobs/${task.outputIdentity}/generated.zip")))
        Files.walk(tasks.work).use { files -> files.filter { Files.isRegularFile(it) }.forEach {
            assertFalse(String(Files.readAllBytes(it)).contains("新增999"))
        } }
        val recovered = AiTaskStore(cache)
        assertEquals(AiTaskPhase.Paused, recovered.recover(before)!!.phase)
        val requested = mutableListOf<String>()
        val result = AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, client(config, requested))
            .run(source.id, recovered.prepared("base"), task.outputIdentity, session = recovered)
        assertEquals(3, requested.size)
        assertEquals(3, result.progress.reused); assertEquals(3, result.progress.completed)
        assertEquals(6, result.aiEntryCount)
        assertEquals(2, result.progress.requests) // No automatic retry, one request per explicit run.
        assertTrue(Files.isRegularFile(result.archive))
    }

    @Test fun missingAnswerIsRetriedAloneAndMalformedEnvelopeCannotCacheAnySibling() {
        val (source, base) = fixture()
        val config = AiConfig(includeMissing = false, includeReview = true, limit = 4)
        val tasks = AiTaskStore(cache); val task = tasks.create(config, source, base, base)
        val malformed = client(config, mutableListOf(), wrap = { body ->
            val data = JSONObject(body); data.getJSONArray("translations").getJSONObject(3).put("id", "unknown")
            data.toString()
        })
        assertThrows(AiTranslationException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, malformed)
                .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        assertEquals(0, tasks.read()!!.progress.completed)
        val missing = client(config, mutableListOf(), wrap = { body ->
            val data = JSONObject(body); data.getJSONArray("translations").remove(1); data.toString()
        })
        assertThrows(AiBatchValidationException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, missing)
                .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        assertEquals(3, tasks.read()!!.progress.completed)
        val requested = mutableListOf<String>()
        val result = AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, client(config, requested))
            .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        assertEquals(1, requested.size); assertEquals(3, result.progress.reused)
        assertEquals(4, result.aiEntryCount)
    }

    @Test fun unreadablePaidAnswersStopRecoveryBeforeAnotherRequest() {
        val (source, base) = fixture()
        val config = AiConfig(includeMissing = false, includeReview = true, limit = 6)
        val tasks = AiTaskStore(cache); val task = tasks.create(config, source, base, base)
        var paused = false
        assertThrows(CancellationException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, client(config, mutableListOf()) { paused = true }, { paused })
                .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        val file = Files.list(tasks.work.resolve("ai-jobs/${task.outputIdentity}/cache")).use { it.findFirst().get() }
        val valid = Files.readAllBytes(file)
        for (bad in listOf("private-corrupt-body", "{} trailing", "{\"key\":42}")) {
            Files.write(file, bad.toByteArray())
            val seen = mutableListOf<String>()
            val error = assertThrows(AiTranslationException::class.java) {
                AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, client(config, seen))
                    .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
            }
            assertTrue(seen.isEmpty()); assertFalse(error.message!!.contains(bad))
            assertEquals(bad, String(Files.readAllBytes(file)))
        }
        java.io.RandomAccessFile(file.toFile(), "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
        assertThrows(AiTranslationException::class.java) { readAiResponseCache(file) }
        Files.write(file, valid)
        assertEquals(4, readAiResponseCache(file).length())
        val large = JSONObject().put("entry", "a".repeat(300_000)).toString()
        Files.write(file, large.toByteArray())
        assertEquals(300_000, readAiResponseCache(file).getString("entry").length)
    }

    @Test fun timeoutThenProcessRecoveryKeepsExactReviewScopeAndDoesNotRebillCompletedBatch() {
        val (source, base) = fixture()
        val config = AiConfig(includeMissing = false, includeReview = true, limit = 6)
        val tasks = AiTaskStore(cache); val task = tasks.create(config, source, base, base)
        val seen = mutableListOf<String>(); var calls = 0
        val http = client(config, seen) { if (++calls == 2) throw SocketTimeoutException() }
        val failure = assertThrows(AiTranslationException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, http)
                .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        assertTrue(failure.message!!.contains("响应超时"))
        assertEquals(4, tasks.read()!!.progress.completed)
        assertEquals(6, tasks.read()!!.plan!!.total)
        assertEquals(28, tasks.read()!!.plan!!.available) // The missing file must never enter a Review task.
        val recovered = AiTaskStore(cache)
        assertEquals(AiTaskPhase.Paused, recovered.recover(TranslationSources(cache).read())!!.phase)
        assertEquals(2, calls) // Merely loading/recovering a task cannot request anything.
        val resumed = AiTranslationJob(cache, ContainerWorkspaceStorage(localize), recovered.read()!!.config, client(config, seen))
            .run(source.id, recovered.prepared("base"), task.outputIdentity, session = recovered)
        assertEquals(6, seen.size); assertTrue(seen.all { it.startsWith("確認する") })
        assertEquals(4, resumed.progress.reused); assertEquals(2, resumed.progress.completed)
        assertEquals(22, resumed.remaining)
        assertFalse(String(Files.readAllBytes(recovered.root.resolve("task.json"))).contains("non-secret-fixture"))
    }

    @Test fun pauseAfterAResponsePersistsItAndResumeHonorsTheOriginalLimit() {
        val (source, base) = fixture(); val config = AiConfig(includeMissing = false, includeReview = true, limit = 6)
        val tasks = AiTaskStore(cache); val task = tasks.create(config, source, base, base)
        var paused = false; val seen = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, client(config, seen) { paused = true }, { paused })
                .run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        assertEquals(4, tasks.read()!!.progress.completed)
        paused = false
        val fresh = AiTaskStore(cache); fresh.recover(TranslationSources(cache).read())
        val result = AiTranslationJob(cache, ContainerWorkspaceStorage(localize), fresh.read()!!.config, client(config, seen))
            .run(source.id, fresh.prepared("base"), task.outputIdentity, session = fresh)
        assertEquals(6, result.progress.completed + result.progress.reused)
        assertEquals(6, seen.size)
    }

    @Test fun changedOriginalIsRejectedBeforeSendingAnyResumedRequest() {
        val (source, base) = fixture(); val config = AiConfig(includeMissing = false, includeReview = true, limit = 6)
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
