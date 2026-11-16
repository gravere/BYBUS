package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerPatchInstaller
import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.container.LiveTranslationStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

class TranslationSourcesTest {
    @get:Rule val temp = TemporaryFolder()
    private val cache get() = temp.root.toPath().resolve("cache")
    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> files.forEach { (path, text) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()
    private fun text(value: String) = """{"dataList":[{"id":1,"content":"$value"}]}"""

    @Test fun importRepairsDerivedFilesAndReplacementKeepsIdentityNameAndIcon() {
        val sources = TranslationSources(cache)
        val first = sources.import(ByteArrayInputStream(zip("MainUIText.json" to text("中文"))), "自定义.zip")
        val prepared = sources.prepare(first)
        val entry = prepared.manifest.entries.single()
        prepared.directory.resolve(entry.cachePath).writeText("broken")
        assertTrue(sources.prepare(first).directory.resolve(entry.cachePath).readText().contains("中文"))
        sources.rename(first.id, "自己的汉化")
        sources.setIcon(first.id, byteArrayOf(1, 2, 3))
        val replaced = sources.import(ByteArrayInputStream(zip("jp/JP_MainUIText.json" to text("新中文"))), "new.zip", first.id)
        assertEquals(first.id, replaced.id); assertEquals("自己的汉化", replaced.name)
        assertNotNull(replaced.icon); assertNotEquals(first.revision, replaced.revision)
        assertEquals(replaced.id, sources.read().selectedId)
    }

    @Test fun maliciousPathsDuplicateTargetsAndNonTranslationPackagesDoNotReplaceCurrentSource() {
        val sources = TranslationSources(cache)
        val first = sources.import(ByteArrayInputStream(zip("MainUIText.json" to text("原有汉化"))), "valid.zip")
        val bad = listOf(
            zip("../../MainUIText.json" to text("中文")),
            zip("MainUIText.json" to text("中文"), "jp/JP_MainUIText.json" to text("冲突")),
            zip("plugin.dll" to "executable"),
            zip("MainUIText.json" to "not-json")
        )
        bad.forEach { bytes -> assertThrows(Exception::class.java) { sources.import(ByteArrayInputStream(bytes), "bad.zip", first.id) } }
        assertEquals(first.revision, sources.read().selected.revision)
        assertEquals(first.id, sources.read().selectedId)
    }

    @Test fun replacingOrSelectingSourcePreservesListPosition() {
        val sources = TranslationSources(cache)
        val one = sources.import(ByteArrayInputStream(zip("MainUIText.json" to text("甲"))), "one.zip")
        sources.import(ByteArrayInputStream(zip("MainUIText.json" to text("乙"))), "two.zip")
        val ids = sources.read().sources.map { it.id }
        sources.select(one.id)
        assertEquals(ids, sources.read().sources.map { it.id })
        sources.import(ByteArrayInputStream(zip("MainUIText.json" to text("新甲"))), "new.zip", one.id)
        assertEquals(ids, sources.read().sources.map { it.id })
    }

    @Test fun oldImporterCacheRebuildsFromArchiveToRecoverSkippedFolders() {
        val sources = TranslationSources(cache)
        val source = sources.import(ByteArrayInputStream(zip("MainUIText.json" to text("中文"),
            "RPGSystem/Quest.json" to text("事件中文"), "EGOVoiceDig/EGO.json" to text("语音中文"))), "full.zip")
        val first = sources.prepare(source)
        val path = first.directory.resolve("manifest.json")
        val old = org.json.JSONObject(path.readText()).apply { remove("bybusImportSchema") }
        val entries = old.getJSONArray("entries")
        for (i in entries.length() - 1 downTo 0) {
            if (entries.getJSONObject(i).getString("targetPath") != "en/EN_MainUIText.json") entries.remove(i)
        }
        path.writeText(old.toString())
        assertEquals(3, sources.prepare(source).manifest.entries.size)
    }

    @Test fun replacingSameSourceWithPartialPackClearsOldOnlyFallbackAndRollsBackOnFailure() {
        val sources = TranslationSources(cache)
        val first = sources.import(ByteArrayInputStream(zip("MainUIText.json" to text("第一包"))), "one.zip")
        val root = temp.root.toPath().resolve("Localize")
        Files.createDirectories(root.resolve("jp")); root.resolve("jp/JP_MainUIText.json").writeText(text("日本語"))
        val installer = ContainerPatchInstaller(cache, ContainerWorkspaceStorage(root))
        val activation = SourceActivation(cache)
        fun activate(source: TranslationSource) {
            val prepared = sources.prepare(source)
            activation.run(source.id, source.revision) { installer.activateImported(prepared.directory, prepared.manifest) }
        }
        activate(first)
        val second = sources.import(ByteArrayInputStream(zip("RPGSystem/Quest.json" to text("第二包"))), "two.zip", first.id)
        assertThrows(IllegalStateException::class.java) { activation.run(second.id, second.revision) { error("failed") } }
        assertNotNull(requireNotNull(LiveTranslationStore(cache).open()).translate("JP_MainUIText", text("日本語")))
        activate(second)
        assertNull(requireNotNull(LiveTranslationStore(cache).open()).translate("JP_MainUIText", text("日本語")))
        assertFalse(Files.exists(cache.resolve("translation-index/active-index.path")))
    }

    @Test fun removingActiveSourceReturnsToOfficialAndDoesNotTouchOtherSources() {
        val sources = TranslationSources(cache)
        val first = sources.import(ByteArrayInputStream(zip("MainUIText.json" to text("甲"))), "first.zip")
        val second = sources.import(ByteArrayInputStream(zip("MainUIText.json" to text("乙"))), "second.zip")
        sources.remove(second.id)
        assertEquals("zero", sources.read().selectedId)
        assertTrue(sources.prepare(first).manifest.entries.isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { sources.remove("zero") }
    }

    @Test fun sevenZipTextPackagesUseTheSameVerifiedImportPipeline() {
        val file = temp.root.toPath().resolve("source.7z")
        val content = text("七字包汉化").toByteArray()
        org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(file.toFile()).use { output ->
            output.putArchiveEntry(org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry().apply {
                name = "LimbusCompany_Data/Lang/LLC_zh-CN/MainUIText.json"; size = content.size.toLong()
            })
            output.write(content); output.closeArchiveEntry()
        }
        val sources = TranslationSources(cache)
        val source = Files.newInputStream(file).use { sources.import(it, "文本.7z") }
        val prepared = sources.prepare(source)
        assertEquals("en/EN_MainUIText.json", prepared.manifest.entries.single().targetPath)
        assertTrue(prepared.directory.resolve(prepared.manifest.entries.single().cachePath).readText().contains("七字包汉化"))
    }

    @Test fun cleanupRetainsLiveSourceUntilItsReplacementIsSuccessfullyActivated() {
        val sources = TranslationSources(cache)
        val old = sources.import(ByteArrayInputStream(zip("MainUIText.json" to text("旧版本"))), "one.zip")
        val installer = ContainerPatchInstaller(cache, ContainerWorkspaceStorage(temp.root.toPath().resolve("Localize")))
        val activation = SourceActivation(cache)
        fun activate(source: TranslationSource) {
            val prepared = sources.prepare(source)
            activation.run(source.id) { installer.activateImported(prepared.directory, prepared.manifest) }
        }
        activate(old)
        val replacement = sources.import(ByteArrayInputStream(zip("MainUIText.json" to text("新版本"))), "two.zip", old.id)
        sources.pruneUnused()
        assertTrue(Files.exists(cache.resolve("patch-cache/local-${old.revision}")))
        activate(replacement); sources.pruneUnused()
        assertFalse(Files.exists(cache.resolve("patch-cache/local-${old.revision}")))
        assertFalse(Files.exists(cache.resolve("sources/archives/${old.archive}")))
        assertTrue(sources.prepare(replacement).manifest.entries.isNotEmpty())
    }

    @Test fun crossSourceActivationNeverMixesOldFallbackTranslations() {
        val sources = TranslationSources(cache)
        val one = sources.import(ByteArrayInputStream(zip("MainUIText.json" to text("第一包"))), "one.zip")
        val two = sources.import(ByteArrayInputStream(zip("MainUIText.json" to text("第二包"))), "two.zip")
        val root = temp.root.toPath().resolve("Localize")
        Files.createDirectories(root.resolve("jp")); root.resolve("jp/JP_MainUIText.json").writeText(text("日本語"))
        val installer = ContainerPatchInstaller(cache, ContainerWorkspaceStorage(root))
        val activation = SourceActivation(cache)
        for (source in listOf(one, two, one)) {
            val prepared = sources.prepare(source)
            activation.run(source.id) { installer.activateImported(prepared.directory, prepared.manifest) }
            assertEquals(source.id, activation.owner())
            val result = requireNotNull(LiveTranslationStore(cache).open()).translate("JP_MainUIText", text("日本語"))
            assertTrue(requireNotNull(result).contains(if (source.id == one.id) "第一包" else "第二包"))
        }
    }

    @Test fun failedAndInterruptedSourceSwitchRestoresPreviousFiles() {
        val root = cache.resolve("translation-index"); Files.createDirectories(root)
        root.resolve("live-resources.json").writeText("old manifest")
        root.resolve("live-fonts.bin").writeText("old font")
        val activation = SourceActivation(cache)
        assertThrows(IllegalStateException::class.java) {
            activation.run("local-1234567890abcdef") {
                root.resolve("live-resources.json").writeText("new manifest")
                error("interrupted")
            }
        }
        assertEquals("old manifest", root.resolve("live-resources.json").readText())
        assertEquals("old font", root.resolve("live-fonts.bin").readText())
        val backup = cache.resolve(".source-switch"); Files.createDirectories(backup)
        backup.resolve("live-fonts.bin").writeText("recovered")
        backup.resolve("ready").writeText("rollback")
        activation.recover()
        assertEquals("recovered", root.resolve("live-fonts.bin").readText())
        assertFalse(Files.exists(root.resolve("live-resources.json")))
    }
}
