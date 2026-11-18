package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TranslationCoverageTest {
    @get:Rule val temp = TemporaryFolder()
    private fun source(json: String): PreparedTranslation {
        val archive = temp.root.toPath().resolve("base.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("MainUIText.json"));zip.write(json.toByteArray());zip.closeEntry()
        }
        val sources = TranslationSources(temp.root.toPath().resolve("cache"))
        val source = Files.newInputStream(archive).use { sources.import(it, "base.zip") }
        return sources.prepare(source)
    }
    @Test fun reportUsesIdentityMatchingAndKeepsOriginalAndSameTextNames() {
        val root = temp.root.toPath().resolve("Localize");Files.createDirectories(root.resolve("jp"))
        val original = """{"dataList":[{"id":1,"name":"石","content":"開始する"},{"id":2,"content":"待機中"},{"id":3,"content":"確認する"}]}"""
        Files.write(root.resolve("jp/JP_MainUIText.json"), original.toByteArray())
        val base = source("""{"dataList":[{"id":3,"content":"確認する"},{"id":1,"name":"石"},{"id":2,"content":"等待中"}]}""")
        val report = TranslationCoverage.scan(ContainerWorkspaceStorage(root), base, "fixture")
        assertEquals(4, report.total);assertEquals(3, report.covered)
        assertEquals(1, report.missing);assertEquals(1, report.aiEligible);assertEquals(1, report.review)
        assertTrue(report.details.single().examples.any { it.text == "開始する" && !it.review })
        assertEquals(original, String(Files.readAllBytes(root.resolve("jp/JP_MainUIText.json"))))
    }
    @Test fun invalidAndUndownloadedResourcesAreNotReportedAsComplete() {
        val root = temp.root.toPath().resolve("Localize");Files.createDirectories(root.resolve("jp"))
        val base = source("""{"dataList":[{"id":1,"content":"你好"}]}""")
        assertThrows(IllegalArgumentException::class.java) { TranslationCoverage.scan(ContainerWorkspaceStorage(root), base, "fixture") }
        Files.write(root.resolve("jp/JP_MainUIText.json"), "{invalid".toByteArray())
        val result = TranslationCoverage.scan(ContainerWorkspaceStorage(root), base, "fixture")
        assertEquals(1, result.unreadable);assertEquals(0, result.files);assertEquals(0, result.total)
    }
    @Test fun categoriesPutGiftSkillsAndStoryInDifferentGroups() {
        assertEquals("饰品与特性", TranslationCoverage.category("jp/JP_EGOGift.json"))
        assertEquals("战斗与技能", TranslationCoverage.category("jp/JP_Skills.json"))
        assertEquals("剧情与对话", TranslationCoverage.category("jp/StoryData/JP_10.json"))
    }

    @Test fun everyIssueGetsOneNumberAndItsActualLocationEvenAfterFiltering() {
        val root = temp.root.toPath().resolve("Localize")
        Files.createDirectories(root.resolve("jp"))
        val original = """{"dataList":[{"id":77,"name":"確認する","nested":{"desc":"続けます"}},
            {"id":77,"name":"同じ記録"},{"key":"stage-A","successDesc":["台詞一","台詞二"]},{"desc":"番号なし"}]}"""
        Files.write(root.resolve("jp/JP_MainUIText.json"), original.toByteArray())
        val base = source("""{"dataList":[{"key":"unused","name":"其他内容"}]}""")
        val entries = mutableListOf<CoverageEntry>()
        val report = TranslationCoverage.scan(ContainerWorkspaceStorage(root), base, "本地包", onEntry = entries::add)
        assertEquals(6, report.missing); assertEquals(0, report.review)
        assertEquals((1..6).toList(), entries.map { it.number })
        assertEquals(listOf("77","77","77","stage-A","stage-A",null), entries.map { it.recordId })
        assertEquals(6, entries.map { it.pointer }.distinct().size)
        assertEquals("/dataList/3/desc", entries.last().pointer)
        assertEquals(listOf(4,5), entries.filteredPage(CoverageFilter(text = "stage-A"), 0).rows.map { it.number })
        assertEquals(6, entries.filteredPage(CoverageFilter(text = "#6"), 0).rows.single().number)
        assertTrue(report.details.single().examples.isEmpty())
    }
}
