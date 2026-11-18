package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.update.ExtractionRequest
import com.example.limbuszhcn.update.TranslationArchiveExtractor
import com.example.limbuszhcn.update.TranslationRepository
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

internal fun cachedOfficialFixture(cache: Path, version: String, json: String): String {
    val archive = Files.createDirectories(cache.resolve("downloads")).resolve("LimbusLocalize_$version.7z")
    SevenZOutputFile(archive.toFile()).use { zip ->
        zip.putArchiveEntry(SevenZArchiveEntry().apply { name = "LimbusCompany_Data/Lang/LLC_zh-CN/MainUIText.json" })
        zip.write(json.toByteArray()); zip.closeArchiveEntry()
    }
    return TranslationArchiveExtractor().extract(ExtractionRequest(archive, cache.resolve("patch-cache"), version)).manifest.archiveSha256
}

class CoverageStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun CoverageStore.fingerprint(source: TranslationSource, storage: ContainerWorkspaceStorage,
        verifyContents: Boolean = false, verifiedOfficial: String? = null) =
        inputs(source, storage, verifyContents, verifiedOfficial = verifiedOfficial).fingerprint
    @Test fun officialReportsFollowTheVerifiedArchiveAcrossFallbackRestartAndRepair() {
        val cache = temp.root.toPath()
        val old = cachedOfficialFixture(cache, "101", """{"dataList":[]}""")
        val latest = cachedOfficialFixture(cache, "102", """{"dataList":[{"id":1,"name":"新译文"}]}""")
        val archive = cache.resolve("downloads/LimbusLocalize_102.7z")
        val bytes = Files.readAllBytes(archive)
        val stamp = Files.getLastModifiedTime(archive)
        Files.write(archive, ByteArray(bytes.size)); Files.setLastModifiedTime(archive, stamp)
        val repository = TranslationRepository(cache)
        val fallback = repository.verifiedForLaunch(null).manifest.archiveSha256
        assertEquals(old, fallback)
        val source = TranslationSources.OFFICIAL
        val storage = ContainerWorkspaceStorage(Files.createDirectories(cache.resolve("Localize")))
        val first = CoverageStore(cache).fingerprint(source, storage, verifiedOfficial = fallback)
        assertEquals(first, CoverageStore(cache).fingerprint(source, storage))
        Files.write(archive, bytes); Files.setLastModifiedTime(archive, stamp)
        val repaired = repository.verifiedForLaunch(null).manifest.archiveSha256
        assertEquals(latest, repaired)
        val next = CoverageStore(cache).fingerprint(source, storage, verifiedOfficial = repaired)
        assertNotEquals(first, next)
        assertEquals(next, CoverageStore(cache).fingerprint(source, storage))
        Files.write(cache.resolve("patch-cache/102/manifest.json"), "{".toByteArray())
        assertEquals(first, CoverageStore(cache).fingerprint(source, storage))
    }

    @Test fun explicitCheckReadsBytesWhenFileIdentityLengthAndTimeStayUnchanged() {
        val cache = temp.root.toPath().resolve("cache")
        val jp = Files.createDirectories(temp.root.toPath().resolve("Localize/jp"))
        val file = jp.resolve("JP_Test.json")
        val source = TranslationSources.OFFICIAL; val storage = ContainerWorkspaceStorage(jp.parent)
        val store = CoverageStore(cache)
        Files.write(file, "始めます".toByteArray()); val time = Files.getLastModifiedTime(file)
        val old = store.fingerprint(source, storage)
        Files.write(file, "止めます".toByteArray()); Files.setLastModifiedTime(file, time)
        val fresh = store.fingerprint(source, storage, verifyContents = true)
        assertNotEquals(old, fresh); assertEquals(fresh, store.fingerprint(source, storage))
        Files.write(file, "始めます".toByteArray()); Files.setLastModifiedTime(file, time)
        store.resourcesReplaced(); assertEquals(old, store.fingerprint(source, storage))
    }

    @Test fun anInterruptedOrFailedCheckRetainsHistoryButCannotReopenThePaymentGateAfterRestart() {
        val cache = temp.root.toPath().resolve("cache")
        val saved = SavedCoverage("old", CoverageReport(TranslationSources.OFFICIAL.name, 1, 1, 1, 0, 1, 0, 1, 0, emptyList(), independentEligible = 1))
        val store = CoverageStore(cache)
        val input = CoverageInputs(TranslationSources.OFFICIAL, "revision", "game", null)
        store.save(input, saved.report, store.beginCheck(input))
        val interrupted = store.beginCheck(input)
        val previous = CoverageStore(cache).read(input)!!
        assertTrue(previous.needsCheck); assertEquals(saved.report, previous.report)
        assertFalse(CoverageState(report = previous.report, fingerprint = previous.fingerprint, stale = previous.needsCheck).canGenerate(AiConfig()))
        val retry = store.beginCheck(input)
        assertThrows(IllegalStateException::class.java) { store.save(input, saved.report, interrupted) }
        store.save(input, saved.report, retry); assertFalse(CoverageStore(cache).read(input)!!.needsCheck)
    }
    @Test fun resultAndExamplesSurviveRestartAndOnlyRelevantInputsChangeFingerprint() {
        val cache = temp.root.toPath().resolve("cache")
        val root = temp.root.toPath().resolve("Localize"); Files.createDirectories(root.resolve("jp"))
        val file = root.resolve("jp/JP_Test.json"); Files.write(file, "{}".toByteArray())
        val storage = ContainerWorkspaceStorage(root)
        val source = TranslationSource("local-1234567812345678", "本地汉化", revision = "first")
        val store = CoverageStore(cache)
        val fingerprint = store.fingerprint(source, storage)
        val report = CoverageReport(source.name, 17L, 1, 12, 10, 2, 1, 2, 0,
            listOf(CoverageFile("jp/JP_Test.json", "其他文本", 2, 1, listOf(CoverageSample("原文", true, "译文", "需核对")))))
        val input = store.inputs(source, storage)
        store.save(input, report, store.beginCheck(input))
        assertEquals(SavedCoverage(fingerprint, report), CoverageStore(cache).read(input))
        assertEquals(fingerprint, store.fingerprint(source.copy(name = "新名称", icon = "new-icon"), storage))
        assertNotEquals(fingerprint, store.fingerprint(source.copy(revision = "ai-or-local-update"), storage))
        assertNotEquals(fingerprint, store.fingerprint(source.copy(id = "local-8765432187654321"), storage))
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 1000))
        assertEquals(fingerprint, store.fingerprint(source, storage))
        Files.write(file, "{\"text\":\"changed\"}".toByteArray())
        assertNotEquals(fingerprint, store.fingerprint(source, storage))
        val changed = store.read(store.inputs(source, storage))!!
        assertEquals(report, changed.report); assertTrue(changed.needsCheck)
    }

    @Test fun eachPackageRestoresItsOwnReportAcrossSwitchesRestartsRenamesAndUndo() {
        val cache = temp.root.toPath()
        val store = CoverageStore(cache)
        val a = TranslationSource("local-one", "甲", revision = "aaa")
        val b = TranslationSource("local-two", "乙", revision = "bbb")
        fun input(source: TranslationSource, game: String = "game", prior: TranslationSource? = null) =
            CoverageInputs(source, source.revision, game, prior)
        fun report(name: String, at: Long) = CoverageReport(name, at, 1, 9, 7, 2, 0, 2, 0, emptyList(), independentEligible = 9)
        val first = report(a.name, 10).copy(entryIndex = CoverageIndexRef(input(a).pack, java.util.UUID.randomUUID().toString()))
        val second = report(b.name, 20).copy(entryIndex = CoverageIndexRef(input(b).pack, java.util.UUID.randomUUID().toString()))
        store.save(input(a), first, store.beginCheck(a))
        assertNull(store.read(input(b)))
        store.save(input(b), second, store.beginCheck(b))
        val restarted = CoverageStore(cache)
        val before = restarted.beginCheck(b)
        assertEquals(first, restarted.read(input(a)) { error("switching must not rescan") }!!.report)
        assertFalse(restarted.read(input(a))!!.needsCheck)
        restarted.abandon(before); assertFalse(restarted.read(input(b))!!.needsCheck)
        assertEquals(second, restarted.read(input(b))!!.report)
        val rename = a.copy(name = "改名", icon = "new")
        assertEquals(first.copy(sourceName = "改名"), restarted.read(input(rename))!!.report)
        val duplicate = a.copy(id = "another-id", name = "同文件")
        assertFalse(restarted.read(input(duplicate))!!.needsCheck)
        val replaced = a.copy(revision = "new")
        assertTrue(restarted.read(input(replaced))!!.needsCheck)
        assertEquals(first.entryIndex, restarted.read(input(replaced))!!.report.entryIndex)
        val initial = restarted.beginCheck(a)
        val revisionCheck = restarted.verified(initial, input(replaced))
        assertFalse(restarted.read(input(a))!!.needsCheck)
        restarted.save(input(replaced), report("甲", 30), revisionCheck)
        restarted.beginCheck(replaced)
        assertFalse(restarted.read(input(a))!!.needsCheck)
        assertEquals(10L, restarted.read(input(a))!!.report.checkedAt)
        assertTrue(restarted.read(input(a, game = "updated game"))!!.needsCheck)
        val companion = b.copy(kind = "ai")
        val differentProgress = input(a, prior = companion)
        val adjusted = restarted.read(differentProgress) { 3 }!!
        assertFalse(adjusted.needsCheck); assertEquals(first.checkedAt, adjusted.report.checkedAt)
        assertEquals(2, adjusted.report.missing); assertEquals(3, adjusted.report.independentEligible)
        assertEquals(adjusted, CoverageStore(cache).read(differentProgress) { error("cached count") })
    }

    @Test fun oldSingleReportIsAdoptedOnlyByItsOriginalInputsAndKeepsAFailedCheck() {
        val cache = temp.root.toPath()
        val store = CoverageStore(cache)
        val a = TranslationSource("local-a", "同名", revision = "a")
        val b = TranslationSource("local-b", "同名", revision = "b")
        val input = CoverageInputs(a, a.revision, "game", null)
        val path = Files.createDirectories(cache.resolve("coverage")).resolve("result.json")
        val old = org.json.JSONObject().put("schema",1).put("fingerprint",input.fingerprint).put("sourceName",a.name)
            .put("checkedAt",12L).put("details",org.json.JSONArray())
        for (field in listOf("files","total","covered","missing","review","aiEligible","unreadable","excluded","shared","reviewEligible","independentEligible")) old.put(field, 0)
        Files.write(path, old.toString().toByteArray()); Files.write(path.resolveSibling("needs-check"), byteArrayOf(1))
        assertNull(store.read(CoverageInputs(b, b.revision, "game", null)))
        assertTrue(Files.exists(path))
        val restored = store.read(input)!!
        assertEquals(12L, restored.report.checkedAt); assertTrue(restored.needsCheck)
        assertFalse(Files.exists(path)); assertTrue(CoverageStore(cache).read(input)!!.needsCheck)
    }
}
