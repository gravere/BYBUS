package com.example.limbuszhcn.update

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class TranslationArchiveExtractorTest {
    @Test
    fun reusesVerifiedFilesButRepairsCorruptionAndMissingFiles() {
        val work = Files.createTempDirectory("verified-extraction")
        val archive = work.resolve("patch.7z")
        val content = """{"content":"罪人"}"""
        createArchive(archive, mapOf("LimbusCompany_Data/Lang/LLC_zh-CN/MainUIText.json" to content))
        val request = ExtractionRequest(archive, work.resolve("cache"), "2026092201")
        val first = TranslationArchiveExtractor().extract(request)
        val file = first.outputDirectory.resolve(first.manifest.entries.single().cachePath)
        val timestamp = Files.getLastModifiedTime(file)
        val second = TranslationArchiveExtractor().extract(request)
        assertTrue(second.fromCache)
        assertEquals(timestamp, Files.getLastModifiedTime(file))
        Files.write(file, content.replace("罪人", "坏字").toByteArray()) // Same byte length, different content.
        assertTrue(!TranslationArchiveExtractor().extract(request).fromCache)
        assertEquals(content, file.readText())
        Files.delete(file)
        assertTrue(!TranslationArchiveExtractor().extract(request).fromCache)
        assertEquals(content, file.readText())
        Files.write(first.outputDirectory.resolve("manifest.json"), "truncated".toByteArray())
        assertTrue(!TranslationArchiveExtractor().extract(request).fromCache)
    }

    @Test
    fun rejectsVersionPathOutsideCache() {
        val work = Files.createTempDirectory("cache-version")
        val archive = work.resolve("patch.7z")
        createArchive(archive, mapOf("LimbusCompany_Data/Lang/LLC_zh-CN/MainUIText.json" to "{}"))
        assertTrue(runCatching {
            TranslationArchiveExtractor().extract(ExtractionRequest(archive, work.resolve("cache"), "../outside"))
        }.isFailure)
    }

    @Test
    fun extractsPatchableJsonFilesAndWritesManifest() {
        val workDir = Files.createTempDirectory("limbus-7z-test")
        val archive = workDir.resolve("LimbusLocalize_2026050702.7z")
        createArchive(
            archive,
            mapOf(
                "LimbusCompany_Data/Lang/LLC_zh-CN/MainUIText.json" to """
                    {"dataList":[{"id":"mainui_main_button1","content":"罪人"}]}
                """.trimIndent(),
                "LimbusCompany_Data/Lang/LLC_zh-CN/StoryData/S001.json" to """
                    {"dataList":[{"id":"1","dialog":"你好"}]}
                """.trimIndent(),
                "LimbusCompany_Data/Lang/LLC_zh-CN/Info/version.json" to """
                    {"version":2026050702,"notice":""}
                """.trimIndent(),
                "LimbusCompany_Data/Lang/LLC_zh-CN/Info/LICENSE" to "ignored"
            )
        )

        val extractor = TranslationArchiveExtractor()
        val result = extractor.extract(
            ExtractionRequest(
                archive = archive,
                cacheRoot = workDir.resolve("cache"),
                version = "2026050702",
                expectedSha256 = null
            )
        )

        assertEquals(2026050702L, result.manifest.archiveVersion)
        assertEquals(4, result.manifest.archiveFileCount)
        assertEquals(2, result.manifest.extractedFileCount)
        assertEquals(
            listOf("en/EN_MainUIText.json", "en/StoryData/EN_S001.json"),
            result.manifest.entries.map { it.targetPath }
        )
        assertTrue(result.outputDirectory.resolve("files/en/EN_MainUIText.json").exists())
        assertTrue(result.outputDirectory.resolve(TranslationArchiveExtractor.MANIFEST_FILE).exists())
        assertTrue(
            result.outputDirectory.resolve(TranslationArchiveExtractor.MANIFEST_FILE)
                .readText()
                .contains("\"archiveVersion\": 2026050702")
        )
    }

    @Test
    fun rejectsUnsafePathsInsideExpectedPrefix() {
        val workDir = Files.createTempDirectory("limbus-7z-test")
        val archive = workDir.resolve("unsafe.7z")
        createArchive(
            archive,
            mapOf(
                "LimbusCompany_Data/Lang/LLC_zh-CN/../evil.json" to "{}"
            )
        )

        try {
            TranslationArchiveExtractor().extract(
                ExtractionRequest(
                    archive = archive,
                    cacheRoot = workDir.resolve("cache"),
                    version = "unsafe"
                )
            )
            fail("Expected unsafe path to be rejected")
        } catch (error: ArchiveValidationException) {
            assertTrue(error.message!!.contains("Unsafe archive path"))
        }
    }

    @Test fun ignoredEntriesStillConsumeTheExpansionBudget() {
        val work = Files.createTempDirectory("archive-budget")
        val archive = work.resolve("patch.7z")
        createArchive(archive, mapOf("ignored.bin" to "x".repeat(64),
            "LimbusCompany_Data/Lang/LLC_zh-CN/MainUIText.json" to "{}"))
        try {
            TranslationArchiveExtractor(ExtractionLimits(maxTotalArchiveBytes = 32)).extract(
                ExtractionRequest(archive, work.resolve("cache"), "100"))
            fail("Ignored data must not bypass expansion limits")
        } catch (error: ArchiveValidationException) {
            assertTrue(error.message!!.contains("expansion budget"))
        }
    }

    @Test fun directoriesStillConsumeTheEntryBudget() {
        val work = Files.createTempDirectory("archive-entry-budget")
        val archive = work.resolve("patch.7z")
        SevenZOutputFile(archive.toFile()).use { output ->
            repeat(3) { i ->
                output.putArchiveEntry(SevenZArchiveEntry().apply { name = "dir$i"; isDirectory = true })
                output.closeArchiveEntry()
            }
        }
        try {
            TranslationArchiveExtractor(ExtractionLimits(maxArchiveFiles = 2)).extract(
                ExtractionRequest(archive, work.resolve("cache"), "100"))
            fail("Directories must not bypass entry limits")
        } catch (error: ArchiveValidationException) {
            assertTrue(error.message!!.contains("Too many archive entries"))
        }
    }

    private fun createArchive(path: Path, entries: Map<String, String>) {
        SevenZOutputFile(path.toFile()).use { archive ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                val entry = SevenZArchiveEntry().apply {
                    this.name = name
                    this.size = bytes.size.toLong()
                }
                archive.putArchiveEntry(entry)
                archive.write(bytes)
                archive.closeArchiveEntry()
            }
        }
    }
}
