package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.*
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

class AiCoverageRoundTripTest {
    @get:Rule val temp = TemporaryFolder()
    private val raw = """{"dataList":[{"id":7,"content":"開始する"},{"id":7,"content":"待機中"}]}"""
    private val translated = """{"dataList":[{"id":7,"content":"开始"},{"id":7,"content":"等待中"}]}"""
    private fun writePack(path: Path, content: String): Path = path.also {
        ZipOutputStream(Files.newOutputStream(it)).use { zip ->
            zip.putNextEntry(ZipEntry("jp/JP_MainUIText.json")); zip.write(content.toByteArray()); zip.closeEntry()
        }
    }
    private fun storage(): ContainerWorkspaceStorage {
        val root = temp.root.toPath().resolve("Localize"); Files.createDirectories(root.resolve("jp"))
        Files.write(root.resolve("jp/JP_MainUIText.json"), raw.toByteArray())
        return ContainerWorkspaceStorage(root)
    }
    private fun client(config: AiConfig, call: () -> Unit) = AiTranslationClient(config, "fixture", AiHttpTransport { _, _, body ->
        call()
        val rows = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
        val values = JSONArray()
        for (i in 0 until rows.length()) values.put(JSONObject().put("id", rows.getJSONObject(i).getString("id"))
            .put("text", if (rows.getJSONObject(i).getString("text") == "開始する") "开始" else "等待中"))
        JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message",
            JSONObject().put("content", JSONObject().put("translations", values).toString())))).toString()
    })
    @Test fun duplicateRowsRoundTripThroughImportCoverageAndRuntimeAndNeverRebill() {
        val cache = temp.root.toPath().resolve("cache"); val disk = storage(); val config = AiConfig()
        var calls = 0
        val generated = AiTranslationJob(cache, disk, config, client(config) { calls++ }).run("zero", null)
        val store = TranslationSources(cache)
        val source = store.addGenerated(generated.archive, "zero", generated.identity, "AI")
        val base = store.prepare(source)
        val report = TranslationCoverage.scan(disk, base, "AI")
        assertEquals(1, calls); assertEquals(0, report.missing); assertEquals(0, report.review)
        val text = String(Files.readAllBytes(base.directory.resolve(base.manifest.entries.single().cachePath)))
        val records = TranslationJsonPairer.pair("Localize/jp/JP_MainUIText.json", raw, text).records
        assertEquals(setOf("开始", "等待中"), records.map { it.translation }.toSet())
        val compiled = TranslationIndexCompiler().apply { records.forEach(::add) }.compile("test", "0".repeat(64))
        assertEquals("开始", compiled.uniqueEntries["開始する"])
        assertEquals("等待中", compiled.uniqueEntries["待機中"])
        val again = AiTranslationJob(cache, disk, config, client(config) { error("already covered must not call API") }).run("zero", base)
        assertEquals(0, again.progress.completed); assertEquals(0, again.remaining)
    }
    @Test fun legacyCommittedDuplicateTranslationsAreReusedWithoutAnotherRequest() {
        val cache = temp.root.toPath().resolve("cache"); val disk = storage(); val config = AiConfig()
        val first = AiTranslationJob(cache, disk, config, client(config) {}).run("zero", null)
        // The old release emitted the same data without a source binding, but retained its validated responses.
        val oldArchive = writePack(temp.root.toPath().resolve("legacy.zip"), translated)
        val store = TranslationSources(cache); val old = store.addGenerated(oldArchive, "zero", first.identity, "AI")
        val previous = store.prepare(old)
        assertEquals(2, TranslationCoverage.scan(disk, previous, "old").missing)
        val reusable = AiTranslationReuse.collect(cache, old, previous, disk)
        assertEquals(2, reusable.size)
        val session = AiTaskStore(cache); session.create(config, old, previous, previous)
        val fixed = AiTranslationJob(cache, disk, config, client(config) { error("legacy paid response must be reused") })
            .run("zero", previous, first.identity, reuse = reusable, session = session)
        assertEquals(2, fixed.progress.reused)
        val updated = store.addGenerated(fixed.archive, "zero", first.identity, "AI")
        assertEquals(0, TranslationCoverage.scan(disk, store.prepare(updated), "new").missing)
    }
    @Test fun sourceChangeOrMetadataChangeCannotAuthorizePositionalPairing() {
        val encoded = TranslationSnapshot.encode(raw, JSONObject(translated))
        assertEquals(2, TranslationJsonPairer.pair("jp/JP_MainUIText.json", raw, encoded).matchedFields)
        assertEquals(0, TranslationJsonPairer.pair("jp/JP_MainUIText.json", raw.replace("開始する", "再開する"), encoded).matchedFields)
        assertEquals(0, TranslationJsonPairer.pair("jp/JP_MainUIText.json", raw, translated).matchedFields)
        val changed = JSONObject(encoded).apply { getJSONArray("dataList").getJSONObject(0).put("power", 99) }.toString()
        assertEquals(0, TranslationJsonPairer.pair("jp/JP_MainUIText.json", raw, changed).matchedFields)
        val arrayRaw = JSONObject(raw).getJSONArray("dataList").toString()
        val array = TranslationSnapshot.encode(arrayRaw, JSONObject(translated).getJSONArray("dataList"))
        assertEquals(2, TranslationJsonPairer.pair("jp/JP_MainUIText.json", arrayRaw, array).matchedFields)
    }
    @Test fun giftAbilityDuplicatesUseTheSameVerifiedSnapshotReader() {
        val rawGift = """{"dataList":[{"id":2,"simpleDesc":[{"abilityID":0,"simpleDesc":"反撃は強くなる"},{"abilityID":0,"simpleDesc":"防御が増加する"}]}]}"""
        val gift = JSONObject(rawGift.replace("反撃は強くなる", "反击增强").replace("防御が増加する", "防御增加"))
        assertEquals(0, TranslationJsonPairer.pair("jp/JP_EGOgift.json", rawGift, gift.toString()).matchedFields)
        assertEquals(2, TranslationJsonPairer.pair("jp/JP_EGOgift.json", rawGift, TranslationSnapshot.encode(rawGift, gift)).matchedFields)
    }
    @Test fun generationRequiresCurrentReportAndNonemptySelectedScope() {
        val report = CoverageReport("source", 1, 1, 10, 10, 0, 2, 0, 0, emptyList(), reviewEligible = 2)
        val config = AiConfig(includeMissing = false, includeReview = true)
        assertFalse(CoverageState().canGenerate(config))
        val current = CoverageState(report = report, fingerprint = "current")
        assertTrue(current.canGenerate(config))
        assertFalse(current.canGenerate(AiConfig()))
        assertFalse(current.copy(stale = true).canGenerate(config))
        assertFalse(current.copy(loading = true).canGenerate(config))
        assertFalse(current.copy(fingerprint = null).canGenerate(config))
    }
}
