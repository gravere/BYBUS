package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.gamefs.GameStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.net.SocketTimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile

class AiResumeCheckpointTest {
    @get:Rule val temp = TemporaryFolder()
    private val cache get() = temp.root.toPath().resolve("cache")
    private val localize get() = temp.root.toPath().resolve("Localize")
    private val reads = mutableListOf<String>()
    private fun storage(): GameStorage {
        val delegate = ContainerWorkspaceStorage(localize)
        return object : GameStorage by delegate {
            override fun read(path: String): ByteArray? { reads += path; return delegate.read(path) }
        }
    }
    private fun rows(n: Int, text: String = "確認する") = JSONObject().put("dataList", JSONArray().apply {
        repeat(n) { put(JSONObject().put("id", it).put("content", "$text $it")) }
    }).toString()
    private fun fixture(): Pair<TranslationSource, PreparedTranslation> {
        Files.createDirectories(localize.resolve("jp"))
        val zip = temp.root.toPath().resolve("source.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            val resources = (0 until 200).associate { "Background$it.json" to rows(3) } +
                mapOf("TargetA.json" to rows(2), "TargetB.json" to rows(6))
            for ((name, raw) in resources) {
                Files.write(localize.resolve("jp/JP_$name"), raw.toByteArray())
                output.putNextEntry(ZipEntry(name)); output.write(raw.toByteArray()); output.closeEntry()
            }
        }
        val sources = TranslationSources(cache)
        val source = Files.newInputStream(zip).use { sources.import(it, "fixture.zip") }
        return source to sources.prepare(source)
    }
    private fun client(config: AiConfig, before: (JSONArray) -> Unit) = AiTranslationClient(config, "fixture", AiHttpTransport { _, _, raw ->
        val request = JSONObject(raw)
        val items = JSONArray(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        before(items)
        assertTrue(items.length() <= 4)
        assertTrue(request.getInt("max_completion_tokens") <= 4096)
        val translated = JSONArray()
        repeat(items.length()) { i ->
            val item = items.getJSONObject(i)
            translated.put(JSONObject().put("id", item.getString("id")).put("text", item.getString("text").replace("確認する", "确认")))
        }
        JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message",
            JSONObject().put("content", JSONObject().put("translations", translated).toString())))).toString()
    })

    @Test fun processRestartAndApiChangeResumeAtTheUnfinishedFileWithoutScanningUnrelatedText() {
        val (source, base) = fixture(); val storage = storage()
        val config = AiConfig(includeMissing = false, includeReview = true, limit = 8)
        val inspection = TranslationInspection(storage, base)
        val selected = listOf("jp/JP_TargetA.json", "jp/JP_TargetB.json").associateWith { inspection.read(it) }
        val plan = AiTaskPlan(selected.mapValues { it.value.fields.map { f -> f.id }.toSet() },
            selected.mapValues { AiTranslationJob.digest(it.value.raw) }, 8)
        val tasks = AiTaskStore(cache)
        val task = tasks.create(config, source, base, base, explicitPlan = plan)
        reads.clear(); var calls = 0; var firstReads = 0
        val sent = mutableListOf<String>()
        assertThrows(AiTranslationException::class.java) {
            AiTranslationJob(cache, storage, config, client(config) { items ->
                calls++
                if (calls == 1) firstReads = reads.size
                if (calls == 3) throw SocketTimeoutException()
                repeat(items.length()) { sent += items.getJSONObject(it).getString("id") }
            }).run(source.id, tasks.prepared("base"), task.outputIdentity, session = tasks)
        }
        assertEquals(6, sent.size)
        // A fresh store simulates process death. Switching API must not invalidate preparation or paid answers.
        val fresh = AiTaskStore(cache)
        val resumed = fresh.write(fresh.recover(TranslationSources(cache).read())!!.withConnection(
            config.copy(endpoint = "https://second.example/v1", model = "second-model"), null))
        reads.clear(); val resumeReads = mutableListOf<String>(); val resumedIds = mutableListOf<String>()
        val result = AiTranslationJob(cache, storage(), resumed.config, client(resumed.config) { items ->
            resumeReads += reads
            repeat(items.length()) { resumedIds += items.getJSONObject(it).getString("id") }
        }).run(source.id, fresh.prepared("base"), task.outputIdentity, session = fresh)
        assertEquals(2, resumedIds.size); assertTrue(resumedIds.none { it in sent })
        assertTrue("first preparation must exercise the full scan", firstReads >= 202)
        assertEquals(3, resumeReads.size) // Two selected-file guards and the unfinished file; no glossary/global re-scan.
        assertTrue(resumeReads.none { it.contains("Background") })
        assertEquals(6, result.progress.reused); assertEquals(2, result.progress.completed)
        assertEquals(8, result.aiEntryCount); assertEquals(8, result.changes!!.total); assertEquals(2, result.changes.files)
        ZipFile(result.archive.toFile()).use { zip ->
            for ((key, count) in mapOf("jp/JP_TargetA.json" to 2, "jp/JP_TargetB.json" to 6)) {
                val data = JSONObject(zip.getInputStream(zip.getEntry(key)).bufferedReader().readText()).getJSONArray("dataList")
                repeat(count) { assertEquals("确认 $it", data.getJSONObject(it).getString("content")) }
            }
            assertNotNull(zip.getEntry("jp/JP_Background0.json"))
        }
        println("resume-before-http: first=$firstReads reads, resumed=${resumeReads.size} reads, new=${resumedIds.size}, reused=${result.progress.reused}")
    }

    @Test fun aDamagedDerivedCheckpointCanBeRebuiltWithoutChargingForCompletedAnswers() {
        val (source, base) = fixture(); val config = AiConfig(limit = 6, includeMissing = false, includeReview = true)
        val tasks = AiTaskStore(cache); val task = tasks.create(config, source, base, base)
        var requests = 0
        assertThrows(AiTranslationException::class.java) {
            AiTranslationJob(cache, storage(), config, client(config) { if (++requests == 2) throw SocketTimeoutException() })
                .run(source.id, base, task.outputIdentity, session = tasks)
        }
        val root = tasks.work.resolve("ai-jobs/${task.outputIdentity}")
        Files.write(root.resolve("prepared-context.json"), "broken".toByteArray())
        val output = Files.list(root.resolve("output/jp")).use { it.findFirst().get() }
        Files.write(output, "broken".toByteArray())
        var retried = 0
        val result = AiTranslationJob(cache, storage(), config, client(config) { retried += it.length() })
            .run(source.id, base, task.outputIdentity, session = AiTaskStore(cache))
        assertEquals(3, retried); assertEquals(3, result.progress.reused); assertEquals(6, result.aiEntryCount)
    }
}
