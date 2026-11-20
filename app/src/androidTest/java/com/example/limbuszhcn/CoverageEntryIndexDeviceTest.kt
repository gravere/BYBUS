package com.example.limbuszhcn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.translation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class CoverageEntryIndexDeviceTest {
    @Test fun fullReportsAreQueryableAfterRestartAndFailedReplacementKeepsTheOldIndex() {
        val parent = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath()
        val root = Files.createTempDirectory(parent, "coverage-test-")
        try {
            val folder = Files.createDirectories(root.resolve("Localize/jp/StoryData")).parent
            val data = (1..12).joinToString(",") { """{"id":$it,"content":"原文${it}です"}""" }
            Files.write(folder.resolve("JP_MainUIText.json"), """{"dataList":[$data]}""".toByteArray())
            Files.write(folder.resolve("StoryData/JP_Stage.json"), """{"dataList":[{"key":"story-20","content":"Only 50%_ here"}]}""".toByteArray())
            val storage = ContainerWorkspaceStorage(folder.parent)
            val registry = TranslationSources(root)
            val zip = ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use {
                it.putNextEntry(ZipEntry("MainUIText.json"))
                it.write("""{"dataList":[{"id":2,"content":"待核です"},{"id":5,"content":"另一个です"},{"id":12,"content":"已翻好"}]}""".toByteArray())
                it.closeEntry()
            } }.toByteArray()
            val pack = registry.import(zip.inputStream(), "文本.zip")
            val store = CoverageStore(root)
            val input = store.inputs(pack, storage, verifyContents = true)
            fun inspect() = store.indexedCheck(input, store.beginCheck(input)) { emit ->
                TranslationCoverage.scan(storage, registry.prepare(pack), pack.name, onEntry = emit)
            }
            val first = inspect().report
            assertEquals(10, first.missing); assertEquals(2, first.review)
            val restarted = CoverageStore(root)
            val ref = requireNotNull(restarted.read(input)!!.report.entryIndex)
            assertEquals(first.entryIndex, ref)
            fun find(kind: CoverageKind? = null, category: String? = null, query: String = "", page: Int = 0) =
                restarted.entries(ref, CoverageFilter(kind,category,query), page)
            assertEquals(5, find().rows.size); assertEquals(12, find().total)
            assertEquals(12, (0..2).flatMap { find(page = it).rows }.map { it.number }.distinct().size)
            assertEquals(setOf("2","5"), find(CoverageKind.Review).rows.map { it.recordId }.toSet())
            assertEquals(1, find(query = "待核").total)
            val story = find(CoverageKind.Missing, "剧情与对话", "%_").rows.single()
            assertEquals("story-20", story.recordId)
            assertEquals(story, find(query = "#${story.number}").rows.single())
            assertEquals(0, find(CoverageKind.Review, query = "#${story.number}").total)
            assertEquals(0, find(query = "' OR 1=1 --").total)
            assertEquals(0, find(query = "story-20", page = 9999).page)
            val storyFilter = CoverageFilter(CoverageKind.Missing, "剧情与对话")
            assertEquals(1, CoverageEntryIndex.count(ref.file(root), storyFilter, null))
            val request = CoverageRequest(first, input.fingerprint, storyFilter, count = 1)
            val plan = CoverageEntryIndex.plan(ref.file(root), request, storage, 100)
            assertEquals(1, plan.total)
            assertEquals(setOf(story.resource), plan.selected.keys)
            val reviewRows = find(CoverageKind.Review).rows
            val one = CoverageRequest(first, input.fingerprint, CoverageFilter(CoverageKind.Review), setOf(reviewRows.first().number), 1)
            assertEquals(1, CoverageEntryIndex.plan(ref.file(root), one, storage, 100).total)
            assertTrue(runCatching { CoverageEntryIndex.plan(ref.file(root), request.copy(count = 2), storage, 100) }.isFailure)
            assertTrue(runCatching { CoverageEntryIndex.plan(ref.file(root), request.copy(numbers = setOf(reviewRows.first().number)), storage, 100) }.isFailure)
            val rejected = runCatching { store.indexedCheck(input, store.beginCheck(input)) { error("interrupted") } }
            assertTrue(rejected.isFailure)
            assertEquals(ref, CoverageStore(root).read(input)!!.report.entryIndex)
            assertEquals(story, find(query = "50%_").rows.single())
            val next = requireNotNull(inspect().report.entryIndex)
            assertNotEquals(ref, next)
            assertFalse(Files.exists(ref.file(root)))
            assertEquals(12, store.entries(next, CoverageFilter(), 0).total)
        } finally { AtomicFiles.deleteOwnedTree(parent, root) }
    }

    @Test fun largeIndexCanFindTheLastPageAndAnExactNumberWithoutLoadingAllText() {
        val parent = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath()
        val root = Files.createTempDirectory(parent, "coverage-volume-")
        try {
            val file = root.resolve("volume.db")
            val total = 12003
            CoverageEntryIndex.write(file) { emit ->
                for (number in 1..total) emit(CoverageEntry(number, CoverageKind.Missing, "剧情与对话",
                    "jp/StoryData/JP_Chapter.json", "剧情原文 第 $number 条", null, "ST-$number", "/dataList/$number/content"))
                CoverageReport("本地卷", 0, 1, total, 0, total, 0, total, 0, emptyList())
            }
            val last = CoverageEntryIndex.read(file, CoverageFilter(), Int.MAX_VALUE)
            assertEquals(listOf(12001, 12002, 12003), last.rows.map { it.number })
            assertEquals(2401, last.pages)
            val exact = CoverageEntryIndex.read(file, CoverageFilter(text = "#12003"), 0)
            assertEquals(last.rows.last(), exact.rows.single())
        } finally { AtomicFiles.deleteOwnedTree(parent, root) }
    }
}
