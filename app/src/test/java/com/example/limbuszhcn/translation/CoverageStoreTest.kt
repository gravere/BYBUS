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
