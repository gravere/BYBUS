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
import java.util.zip.ZipOutputStream

class AiScopeUndoTest {
    @get:Rule val temp = TemporaryFolder()
    private fun pack(name: String, files: Map<String, String>): Path = temp.root.toPath().resolve(name).also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip -> files.forEach { (key, text) ->
            zip.putNextEntry(ZipEntry(key)); zip.write(text.toByteArray());zip.closeEntry()
        } }
    }
    private fun json(value: String) = """{"dataList":[{"id":1,"content":"$value"}]}"""
    @Test fun restoringDefaultKeepsAiArchiveAndDeletingInactiveAiPreservesOtherSources() {
        val cache = temp.root.toPath().resolve("cache"); val store = TranslationSources(cache)
        val archive = pack("generated.zip", mapOf("MainUIText.json" to json("译文")))
        val first = store.addGenerated(archive, "zero", "a".repeat(32), "第一份 AI")
        val second = store.addGenerated(archive, "zero", "b".repeat(32), "第二份 AI")
        val firstJob = cache.resolve("ai-jobs/" + "a".repeat(32))
        val secondJob = cache.resolve("ai-jobs/" + "b".repeat(32))
        listOf(firstJob, secondJob).forEach { Files.createDirectories(it); Files.write(it.resolve("generated.zip"), byteArrayOf(1)) }
        store.select("zero")
        assertEquals("zero", TranslationSources(cache).read().selectedId)
        store.prepare(first); store.prepare(second)
        assertTrue(Files.exists(firstJob))
        store.remove(first.id); store.pruneUnused()
        val remaining = TranslationSources(cache).read()
        assertEquals("zero", remaining.selectedId)
        assertEquals(listOf("zero", second.id), remaining.sources.map { it.id })
        assertEquals(second.id, remaining.undo?.sourceId)
        assertFalse(Files.exists(firstJob)); assertTrue(Files.exists(secondJob))
        store.prepare(second)
        store.select(second.id); store.remove(second.id); store.pruneUnused()
        assertEquals("zero", store.read().selectedId); assertNull(store.read().undo)
        assertFalse(Files.exists(secondJob))
        assertFalse(Files.exists(cache.resolve("sources/archives/" + second.archive)))
    }

    @Test fun newerHumanTranslationTakesPrecedenceOverSavedAiWhenGeneratingFromUpdatedBase() {
        val cache = temp.root.toPath().resolve("cache"); val root = temp.root.toPath().resolve("Localize")
        Files.createDirectories(root.resolve("jp"))
        Files.write(root.resolve("jp/JP_MainUIText.json"), json("開始する").toByteArray())
        val store = TranslationSources(cache)
        val old = pack("old.zip", mapOf("MainUIText.json" to """{"dataList":[{"id":2,"content":"其他译文"}]}"""))
        val source = Files.newInputStream(old).use { store.import(it, "base.zip") }
        val config = AiConfig(includeMissing = true, includeReview = false)
        var calls = 0
        val client = AiTranslationClient(config, "fixture", AiHttpTransport { _, _, body ->
            calls++
            val row = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content")).getJSONObject(0)
            val translated = JSONObject().put("translations", JSONArray().put(JSONObject().put("id", row.getString("id")).put("text", "AI 开始")))
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", translated.toString())))).toString()
        })
        val job = AiTranslationJob(cache, ContainerWorkspaceStorage(root), config, client)
        job.run("zero", store.prepare(source))
        assertEquals(1, calls)
        val updated = pack("updated.zip", mapOf("MainUIText.json" to json("正式开始")))
        val newBase = Files.newInputStream(updated).use { store.import(it, "base.zip", source.id) }
        val result = job.run("zero", store.prepare(newBase))
        assertEquals(1, calls); assertEquals(0, result.progress.reused)
        java.util.zip.ZipFile(result.archive.toFile()).use { zip ->
            val rows = JSONObject(zip.getInputStream(zip.getEntry("jp/JP_MainUIText.json")).bufferedReader().readText()).getJSONArray("dataList")
            assertEquals("正式开始", rows.getJSONObject(0).getString("content"))
        }
    }

    @Test fun firstGenerationCanBeUndoneAfterRestartWithoutRemovingOriginalPack() {
        val cache = temp.root.toPath().resolve("cache"); val store = TranslationSources(cache)
        val archive = pack("base.zip", mapOf("MainUIText.json" to json("原译文")))
        val base = Files.newInputStream(archive).use { store.import(it, "base.zip") }
        val generated = pack("new.zip", mapOf("MainUIText.json" to json("新译文")))
        val ai = store.addGenerated(generated, base.id, "a".repeat(32), "AI 补充")
        assertEquals(ai.id, store.read().selectedId)
        val restored = TranslationSources(cache).undoGenerated()
        assertEquals(base.id, restored.selectedId);assertNull(restored.undo)
        assertFalse(restored.sources.any { it.id == ai.id });store.prepare(base)
    }
    @Test fun overwriteRetainsOneUndoArchiveAndDoesNotUndoLaterRenaming() {
        val cache = temp.root.toPath().resolve("cache");val store = TranslationSources(cache)
        val a = pack("a.zip", mapOf("MainUIText.json" to json("第一批")))
        val first = store.addGenerated(a, "zero", "a".repeat(32), "AI")
        val b = pack("b.zip", mapOf("MainUIText.json" to json("第二批")))
        store.addGenerated(b, "zero", "a".repeat(32), "AI");store.rename(first.id, "我的汉化")
        store.pruneUnused()
        val restored = TranslationSources(cache).undoGenerated()
        assertEquals(first.revision, restored.selected.revision);assertEquals("我的汉化", restored.selected.name)
        store.prepare(restored.selected);assertNull(restored.undo)
        assertThrows(IllegalArgumentException::class.java) { store.undoGenerated() }
    }
    @Test fun failedGenerationKeepsUndoPointAndManualReplacementInvalidatesIt() {
        val store = TranslationSources(temp.root.toPath().resolve("cache"))
        val archive = pack("a.zip", mapOf("MainUIText.json" to json("第一批")))
        val first = store.addGenerated(archive, "zero", "a".repeat(32), "AI")
        val point = store.read().undo
        val bad = temp.root.toPath().resolve("bad.zip");Files.write(bad, byteArrayOf(1,2,3))
        assertThrows(Exception::class.java) { store.addGenerated(bad, "zero", "a".repeat(32), "AI") }
        assertEquals(point, store.read().undo)
        Files.newInputStream(archive).use { store.import(it, "替换.zip", first.id) }
        assertNull(store.read().undo)
    }
    @Test fun reviewAndMissingScopesUseTheSameCoverageAndPreserveUnselectedTranslations() {
        val cache = temp.root.toPath().resolve("cache"); val root = temp.root.toPath().resolve("Localize")
        Files.createDirectories(root.resolve("jp"))
        val original = """{"dataList":[{"id":1,"content":"開始する"},{"id":2,"content":"確認する"},{"id":3,"content":"待機中"}]}"""
        Files.write(root.resolve("jp/JP_MainUIText.json"), original.toByteArray())
        val source = pack("base.zip", mapOf("MainUIText.json" to """{"dataList":[{"id":1,"content":"开始"},{"id":2,"content":"確認する"}]}"""))
        val store = TranslationSources(cache); val base = Files.newInputStream(source).use { store.import(it,"base.zip") }
        for (scope in listOf(AiScope.Review, AiScope.Missing, AiScope.Both)) {
            val expected = when(scope) { AiScope.Review -> setOf("確認する"); AiScope.Missing -> setOf("待機中"); else -> setOf("確認する","待機中") }
            val seen = mutableSetOf<String>();val config = AiConfig(includeMissing = scope.missing, includeReview = scope.review)
            val client = AiTranslationClient(config,"fixture",AiHttpTransport { _, _, body ->
                val inputs = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
                val rows = JSONArray();for (i in 0 until inputs.length()) { val row = inputs.getJSONObject(i);seen += row.getString("text")
                    rows.put(JSONObject().put("id",row.getString("id")).put("text",if(row.getString("text")=="確認する") "确认" else "等待")) }
                JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",
                    JSONObject().put("content",JSONObject().put("translations",rows).toString())))).toString()
            })
            val result = AiTranslationJob(cache.resolve(scope.name),ContainerWorkspaceStorage(root),config,client).run(base.id,store.prepare(base))
            assertEquals(expected.size, result.changes!!.total)
            assertTrue(result.changes.examples.none { it.before == "开始" })
            assertEquals(expected,seen)
            java.util.zip.ZipFile(result.archive.toFile()).use { zip ->
                val rows = JSONObject(zip.getInputStream(zip.getEntry("jp/JP_MainUIText.json")).bufferedReader().readText()).getJSONArray("dataList")
                assertEquals("开始",rows.getJSONObject(0).getString("content"))
                assertEquals(if(scope.review) "确认" else "確認する",rows.getJSONObject(1).getString("content"))
                assertEquals(scope.missing,rows.getJSONObject(2).has("content"))
            }
        }
        assertEquals(original,String(Files.readAllBytes(root.resolve("jp/JP_MainUIText.json")),Charsets.UTF_8))
    }
    @Test fun internalNotesUnusedRowsAndRetainedNamesAreNotMissingTranslations() {
        val root = temp.root.toPath().resolve("Localize");Files.createDirectories(root.resolve("jp"))
        val jp = """{"dataList":[{"id":1,"desc":"말풍선 특수 대사","dlg":"開始する"},{"id":2,"desc":"내부 메모","dlg":"[未使用] 何番？"},{"id":3,"dlg":"し協会"}]}"""
        Files.write(root.resolve("jp/JP_BattleSpeechBubbleDlg.json"),jp.toByteArray())
        val archive = pack("base.zip", mapOf("BattleSpeechBubbleDlg.json" to """{"dataList":[{"id":1,"desc":"말풍선 특수 대사","dlg":"开始"},{"id":3,"dlg":"し协会"}]}"""))
        val store = TranslationSources(temp.root.toPath().resolve("cache"));val source = Files.newInputStream(archive).use { store.import(it,"base.zip") }
        val result = TranslationCoverage.scan(ContainerWorkspaceStorage(root),store.prepare(source),"fixture")
        assertEquals(2,result.total);assertEquals(3,result.excluded);assertEquals(0,result.review);assertEquals(0,result.missing)
        assertFalse(TranslationFields.hasResidualLanguage("<style=한글>已翻译</style>하나协会"))
        val notes = """{"dataList":[{"id":-1,"model":"//효과음","content":"쾅 쾅 쾅"},{"id":-1,"model":"사자","content":"終わりだよ"}]}"""
        val fields = TranslationFields.collect(TranslationFields.parse(notes),TranslationFields.parse(notes),"jp/StoryData/JP_S001A.json",null)
        assertNotNull(fields.first().excludedReason)
        assertNull(fields.last().excludedReason) // A negative ID alone does not prove a production note.
    }
    @Test fun sharedTranslationUsesProductionConflictRules() {
        val root = temp.root.toPath().resolve("Localize");Files.createDirectories(root.resolve("jp"))
        Files.write(root.resolve("jp/JP_MainUIText.json"),json("開始する").toByteArray())
        Files.write(root.resolve("jp/JP_Other.json"),json("開始する").toByteArray())
        val archive = pack("base.zip",mapOf("MainUIText.json" to json("开始")))
        val store=TranslationSources(temp.root.toPath().resolve("cache"));val source=Files.newInputStream(archive).use { store.import(it,"base.zip") }
        val report=TranslationCoverage.scan(ContainerWorkspaceStorage(root),store.prepare(source),"fixture")
        assertEquals(0,report.missing);assertEquals(1,report.shared)
        Files.write(root.resolve("jp/JP_MainUIText.json"),"""{"dataList":[{"id":1,"content":"開始する"},{"id":2,"content":"開始する"}]}""".toByteArray())
        val conflicting=pack("conflict.zip",mapOf("MainUIText.json" to """{"dataList":[{"id":1,"content":"开始"},{"id":2,"content":"出发"}]}"""))
        val replacement=Files.newInputStream(conflicting).use { store.import(it,"base.zip",source.id) }
        val unresolved=TranslationCoverage.scan(ContainerWorkspaceStorage(root),store.prepare(replacement),"fixture")
        assertEquals(1,unresolved.missing);assertEquals(0,unresolved.shared)
    }
}
