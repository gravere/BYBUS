package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class CoverageStoreTest {
    @get:Rule val temp = TemporaryFolder()
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
        val saved = SavedCoverage("old", CoverageReport("零协", 1, 1, 1, 0, 1, 0, 1, 0, emptyList(), independentEligible = 1))
        val store = CoverageStore(cache); store.save(saved)
        val interrupted = store.beginCheck()
        val previous = CoverageStore(cache).read()!!
        assertTrue(previous.needsCheck); assertEquals(saved.report, previous.report)
        assertFalse(CoverageState(report = previous.report, fingerprint = previous.fingerprint, stale = previous.needsCheck).canGenerate(AiConfig()))
        val retry = store.beginCheck()
        assertThrows(IllegalStateException::class.java) { store.save(saved, interrupted) }
        store.save(saved, retry); assertFalse(CoverageStore(cache).read()!!.needsCheck)
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
        store.save(SavedCoverage(fingerprint, report))
        assertEquals(SavedCoverage(fingerprint, report), CoverageStore(cache).read())
        assertEquals(fingerprint, store.fingerprint(source.copy(name = "新名称", icon = "new-icon"), storage))
        assertNotEquals(fingerprint, store.fingerprint(source.copy(revision = "ai-or-local-update"), storage))
        assertNotEquals(fingerprint, store.fingerprint(source.copy(id = "local-8765432187654321"), storage))
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 1000))
        assertEquals(fingerprint, store.fingerprint(source, storage))
        Files.write(file, "{\"text\":\"changed\"}".toByteArray())
        assertNotEquals(fingerprint, store.fingerprint(source, storage))
        assertEquals(report, store.read()!!.report) // A stale report remains available for comparison.
    }
}
