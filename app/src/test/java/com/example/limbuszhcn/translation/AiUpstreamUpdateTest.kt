package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AiUpstreamUpdateTest {
    @get:Rule val temp = TemporaryFolder()
    private fun rows(vararg rows: Pair<Int, String>) = JSONObject().put("dataList", JSONArray().apply {
        rows.forEach { (id, value) -> put(JSONObject().put("id", id).put("content", value)) }
    }).toString()
    private fun pack(name: String, content: String): Path = temp.root.toPath().resolve(name).also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("MainUIText.json")); zip.write(content.toByteArray()); zip.closeEntry()
        }
    }
    private fun contents(archive: Path): Map<Int, String?> = ZipFile(archive.toFile()).use { zip ->
        val rows = JSONObject(zip.getInputStream(zip.getEntry("jp/JP_MainUIText.json")).bufferedReader().readText()).getJSONArray("dataList")
        (0 until rows.length()).associate { i -> val row = rows.getJSONObject(i)
            row.getInt("id") to (row.opt("content") as? String) }
    }
    private fun client(config: AiConfig, seen: MutableList<String>) = AiTranslationClient(config, "fixture", AiHttpTransport { _, _, body ->
        val input = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
        val output = JSONArray()
        for (i in 0 until input.length()) {
            val item = input.getJSONObject(i); seen += item.getString("text")
            output.put(JSONObject().put("id", item.getString("id")).put("text", when(item.getString("text")) {
                "開始する" -> "开始"; "待機中" -> "等待中"; "接続中" -> "连接中"; "接続を確認" -> "确认连接"; else -> "结束"
            }))
        }
        JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message",
            JSONObject().put("content", JSONObject().put("translations", output).toString())))).toString()
    })

    @Test fun eachNewUpstreamVersionIsOfferedOnceAndManualCheckCanReopenIt() {
        val source = TranslationSource("ai-" + "a".repeat(32), "AI", "ai", "revision", officialVersion = 2026111401)
        assertTrue(AiUpstreamUpdate.shouldOffer(source, 2026111701, 0))
        assertFalse(AiUpstreamUpdate.shouldOffer(source, 2026111701, 2026111701))
        assertTrue(AiUpstreamUpdate.shouldOffer(source, 2026111801, 2026111701))
        assertTrue(AiUpstreamUpdate.shouldOffer(source, 2026111701, 2026111701, force = true))
        assertFalse(AiUpstreamUpdate.shouldOffer(source, 2026111401, 0, force = true))
        assertFalse(AiUpstreamUpdate.shouldOffer(source.copy(kind = "local"), 2026111701, 0))
        assertTrue(AiUpstreamUpdate.shouldOffer(source.copy(officialVersion = null), 2026111701, 0))
        val offer = AiUpstreamOffer(source.id, source.revision, 2026111701, source.officialVersion, "{}", AiConfig(), true)
        AiUpstreamUpdate.requireCurrent(offer, source)
        assertThrows(IllegalArgumentException::class.java) { AiUpstreamUpdate.requireCurrent(offer, source.copy(revision = "changed")) }
        assertThrows(IllegalArgumentException::class.java) { AiUpstreamUpdate.requireCurrent(offer, source.copy(kind = "local")) }
        val cfg = AiUpstreamUpdate.supplementConfig(AiConfig(missingOnly = false, includeMissing = false, includeReview = true, limit = 2))
        assertTrue(cfg.missingOnly && cfg.includeMissing && !cfg.includeReview); assertEquals(2, cfg.limit)
    }

    @Test fun officialVersionSurvivesRestartAndUndoButIsClearedByManualReplacement() {
        val store = TranslationSources(temp.root.toPath().resolve("cache"))
        val first = pack("first.zip", rows(1 to "第一批")); val second = pack("second.zip", rows(1 to "第二批"))
        val source = store.addGenerated(first, "zero", "a".repeat(32), "AI", 2026111401)
        store.addGenerated(second, "zero", "a".repeat(32), "AI", 2026111701)
        assertEquals(2026111701L, TranslationSources(store.cache).read().selected.officialVersion)
        assertEquals(2026111401L, TranslationSources(store.cache).undoGenerated().selected.officialVersion)
        Files.newInputStream(second).use { store.import(it, "replace.zip", source.id) }
        assertNull(store.read().selected.officialVersion); assertEquals("local", store.read().selected.kind)
    }

    @Test fun updateReusesOnlyCommittedUnchangedAiAndHumanTranslationsWinWithoutApiCalls() {
        val cache = temp.root.toPath().resolve("cache"); val localize = temp.root.toPath().resolve("Localize")
        Files.createDirectories(localize.resolve("jp"))
        val jp = localize.resolve("jp/JP_MainUIText.json")
        Files.write(jp, rows(1 to "開始する", 2 to "待機中", 3 to "接続中").toByteArray())
        val store = TranslationSources(cache); val storage = ContainerWorkspaceStorage(localize)
        val config = AiConfig(); val seen = mutableListOf<String>()
        val generated = AiTranslationJob(cache, storage, config, client(config, seen)).run("zero", null)
        val old = store.addGenerated(generated.archive, "zero", generated.identity, "AI", 2026111401)
        assertEquals(3, seen.size); seen.clear()
        // The entire file changes; only row 2's original and committed AI result remain reusable.
        val updatedJp = rows(1 to "開始する", 2 to "待機中", 3 to "接続を確認", 4 to "終了する")
        Files.write(jp, updatedJp.toByteArray())
        val newArchive = pack("official.zip", rows(1 to "正式开始"))
        val newer = Files.newInputStream(newArchive).use { store.import(it, "official.zip") }
        store.select(old.id)
        val reusable = AiTranslationReuse.collect(cache, old, store.prepare(old), storage)
        assertEquals(2, reusable.size) // Row 1 also has proof, but the newer human base will take priority.
        val identity = "c".repeat(32)
        val reuse = AiTranslationJob(cache, storage, config, client(config, seen))
            .run("zero", store.prepare(newer), identity, reusable, reuseOnly = true)
        assertTrue(seen.isEmpty()); assertEquals(1, reuse.progress.reused); assertEquals(2, reuse.remaining)
        assertEquals(mapOf(1 to "正式开始", 2 to "等待中", 3 to null, 4 to null), contents(reuse.archive))
        assertEquals(old.id, store.read().selectedId) // Preparing alone never changes activation.
        val newSource = store.addGenerated(reuse.archive, "zero", identity, "AI", 2026111701)
        assertEquals(1, AiTranslationReuse.collect(cache, newSource, store.prepare(newSource), storage).size)
        val limited = config.copy(limit = 1)
        val supplemented = AiTranslationJob(cache, storage, limited, client(limited, seen))
            .run("zero", store.prepare(newer), identity, reusable)
        assertEquals(listOf("接続を確認"), seen)
        assertEquals(1, supplemented.progress.reused); assertEquals(1, supplemented.progress.completed); assertEquals(1, supplemented.remaining)
        assertEquals("确认连接", contents(supplemented.archive)[3]); assertNull(contents(supplemented.archive)[4])
        assertEquals(updatedJp, String(Files.readAllBytes(jp)))
        store.prepare(old) // The previous package is still available after the update.
    }

    @Test fun staleOrUncommittedCacheCannotReplaceTheSelectedSnapshot() {
        val cache = temp.root.toPath().resolve("cache"); val root = temp.root.toPath().resolve("Localize")
        Files.createDirectories(root.resolve("jp")); Files.write(root.resolve("jp/JP_MainUIText.json"), rows(1 to "開始する").toByteArray())
        val storage = ContainerWorkspaceStorage(root); val store = TranslationSources(cache); val config = AiConfig()
        val generated = AiTranslationJob(cache, storage, config, client(config, mutableListOf())).run("zero", null)
        val selected = store.addGenerated(pack("different.zip", rows(1 to "已撤回的译文")), "zero", generated.identity, "AI")
        assertTrue(AiTranslationReuse.collect(cache, selected, store.prepare(selected), storage).isEmpty())
        val absent = selected.copy(id = "ai-" + "b".repeat(32))
        assertTrue(AiTranslationReuse.collect(cache, absent, store.prepare(selected), storage).isEmpty())
    }
}
