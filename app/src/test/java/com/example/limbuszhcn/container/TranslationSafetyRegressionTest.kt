package com.example.limbuszhcn.container

import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.update.PatchFileEntry
import com.example.limbuszhcn.update.PatchManifest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class TranslationSafetyRegressionTest {
    @Test fun rpgKeysSurviveReorderingAndNewDisplayFieldsAreIncluded() {
        val source = """{"dataList":[{"key":"a","displayName":"名前甲","speaker":"話者甲","goalDescription1":"周囲を調べる","statText":"効果甲"},{"key":"b","displayName":"名前乙"}]}"""
        val target = """{"dataList":[{"key":"b","displayName":"乙名"},{"key":"a","displayName":"甲名","speaker":"甲说话人","goalDescription1":"检查周围","statText":"甲效果"}]}"""
        val paired = TranslationJsonPairer.pair("RPGSystem/NPC.json", "\uFEFF" + source, target)
        assertTrue(paired.sufficientlyPaired)
        assertEquals(5, paired.records.size)
        assertEquals("甲名", paired.records.single { it.source == "名前甲" }.translation)
        assertEquals("a", paired.records.single { it.source == "名前甲" }.stableId)
        assertTrue(paired.records.none { it.field == "key" })
        assertTrue(TranslationJsonPairer.pair("RPGSystem/NPC.json", source,
            target.replace("\"key\":\"b\"", "\"key\":\"a\"")).records.isEmpty())
    }

    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root.toPath()
    private val cache get() = root.resolve("cache")
    private val localize get() = root.resolve("Resources/Localize")
    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent); path.writeText(text); return path
    }
    private fun manifest(version: String, count: Int) = PatchManifest(version, version.toLong(), "archive", count, count,
        (0 until count).map { PatchFileEntry("File$it.json", "File$it.json", "en/File$it.json", 1, "hash") })
    private fun seed(version: String = "100", records: Int = 1) {
        TranslationIndexStore(cache.resolve("translation-index")).activate(CompiledTranslationIndex(
            version, "old-archive", "old-input", (0 until records).associate { "以前の文章$it" to "以前的译文$it" },
            emptyMap(), records, 0, 0, 10, 10))
    }
    private fun prepared(count: Int, valid: Int): Pair<Path, PatchManifest> {
        val patch = cache.resolve("patch-cache/200")
        val manifest = manifest("200", count)
        write(patch.resolve("manifest.json"), manifest.toJson())
        repeat(count) { i ->
            write(patch.resolve("File$i.json"), """{"dataList":[{"id":1,"name":"新译文$i"}]}""")
            write(localize.resolve("jp/JP_File$i.json"), if (i < valid) """{"dataList":[{"id":1,"name":"新しい文章$i"}]}""" else "{")
        }
        return patch to manifest
    }
    private fun installer(storage: GameStorage = ContainerWorkspaceStorage(localize)) = ContainerPatchInstaller(cache, storage)

    @Test fun disablingActiveVersionNeverDeletesGameFilesEvenWithNewerPendingCache() {
        seed()
        write(localize.resolve("en/EN_File0.json"), "official")
        write(localize.resolve("jp/JP_File0.json"), "official-jp")
        write(root.resolve("Resources/Lang/config.json"), "not-owned")
        write(cache.resolve("patch-cache/200/manifest.json"), manifest("200", 1).toJson())
        val installer = installer()
        assertEquals("100", installer.uninstallLatest().version)
        assertEquals(0, installer.uninstallLatest().deletedFiles)
        assertNull(installer.activeInstallSummary())
        assertEquals("official", localize.resolve("en/EN_File0.json").readText())
        assertEquals("official-jp", localize.resolve("jp/JP_File0.json").readText())
        assertEquals("not-owned", root.resolve("Resources/Lang/config.json").readText())
    }

    @Test fun corruptJsonDoesNotCountAsReadyOrReplacePreviousIndex() {
        seed(); val (patch, manifest) = prepared(10, 1)
        val installer = installer()
        val result = installer.activateRedirect(patch, manifest)
        assertFalse(result.indexActivated)
        assertEquals(1, result.availableSourceFiles)
        assertTrue(result.previousIndexPreserved)
        assertEquals("100", installer.activeInstallSummary()!!.version)
    }

    @Test fun validIndexActivatesAndCanBeReusedWithoutChangingOriginalResources() {
        val (patch, manifest) = prepared(2, 2)
        val before = localize.resolve("jp/JP_File0.json").readText()
        val installer = installer()
        assertTrue(installer.activateRedirect(patch, manifest).indexActivated)
        assertTrue(installer.activateRedirect(patch, manifest).reusedIndex)
        assertEquals(before, localize.resolve("jp/JP_File0.json").readText())
    }

    @Test fun missingTranslationRemainsInReadinessDenominator() {
        seed(); val (patch, manifest) = prepared(10, 10)
        repeat(9) { Files.delete(patch.resolve("File$it.json")) }
        val result = installer().activateRedirect(patch, manifest)
        assertEquals(10, result.candidateSourceFiles)
        assertFalse(result.indexActivated)
    }

    @Test fun largeRecordCollapsePreservesTheOldIndex() {
        seed(records = 100); val (patch, manifest) = prepared(1, 1)
        val result = installer().activateRedirect(patch, manifest)
        assertFalse(result.indexActivated)
        assertTrue(result.pendingReason!!.contains("异常减少"))
    }

    @Test fun partialTableKeepsProvenRowsWithoutCountingAsReady() {
        val (patch, manifest) = prepared(10, 10)
        write(patch.resolve("File9.json"), """{"dataList":[{"key":"one","text":"第一处"}]}""")
        write(localize.resolve("jp/JP_File9.json"), """{"dataList":[{"key":"one","text":"第一の場所"},{"key":"two","text":"未翻訳"}]}""")
        val result = installer().activateRedirect(patch, manifest)
        assertTrue(result.indexActivated)
        assertEquals(9, result.availableSourceFiles)
        assertEquals(10, result.sourceRecordCount)
    }

    @Test fun changingSourceDuringCompilationCannotCommit() {
        seed(); val (patch, manifest) = prepared(1, 1)
        val base = ContainerWorkspaceStorage(localize)
        var reads = 0
        val storage = object : GameStorage by base {
            override fun read(path: String): ByteArray? {
                reads++
                return if (reads < 3) base.read(path) else """{"dataList":[{"id":1,"name":"別の文章"}]}""".toByteArray()
            }
        }
        assertFalse(installer(storage).activateRedirect(patch, manifest).indexActivated)
        assertEquals("100", installer().activeInstallSummary()!!.version)
    }

    @Test fun missingIdentityNeverFallsBackToAnotherItem() {
        val result = TranslationJsonPairer.pair("UI.json",
            """{"dataList":[{"id":1,"name":"甲の名前"},{"id":2,"name":"乙の名前"}]}""",
            """{"dataList":[{"id":2,"name":"乙的译名"}]}""")
        assertEquals(listOf("乙の名前"), result.records.map { it.source })
        assertFalse(result.sufficientlyPaired)
    }

    @Test fun reorderedIdentitiesPairCorrectlyAndDuplicateIdentitiesAreSkipped() {
        val original = """[{"id":1,"name":"甲"},{"id":2,"name":"乙"}]"""
        val reordered = TranslationJsonPairer.pair("UI", original, """[{"id":2,"name":"第二"},{"id":1,"name":"第一"}]""")
        assertEquals(listOf("第一", "第二"), reordered.records.map { it.translation })
        val duplicate = TranslationJsonPairer.pair("UI", original, """[{"id":1,"name":"第一"},{"id":1,"name":"错误"}]""")
        assertTrue(duplicate.records.isEmpty())
    }

    @Test fun duplicateStoryIdentityNeedsUniqueMatchingMetadataAndNeverUsesPosition() {
        val source = """[{"id":11,"model":"speaker-a","content":"甲の話"},{"id":11,"model":"speaker-b","content":"乙の話"}]"""
        val target = """[{"id":12,"model":"speaker-b","content":"乙的台词"},{"id":11,"model":"speaker-a","content":"甲的台词"}]"""
        val result = TranslationJsonPairer.pair("jp/StoryData/JP_E715B.json", source, target)
        assertEquals(listOf("甲的台词"), result.records.map { it.translation })
        assertEquals(listOf("11"), result.records.map { it.stableId })
        assertEquals(listOf("content"), result.records.map { it.field })
        // Repeated matching models offer no additional evidence and must be rejected.
        assertTrue(TranslationJsonPairer.pair("story", source.replace("speaker-b", "speaker-a"), target).records.isEmpty())
    }

    @Test fun emptyKnownTablesAreAllowedButWrongRootAndMetadataAreNotPaired() {
        assertTrue(TranslationJsonPairer.pair("UI", """{"dataList":[]}""", """{"dataList":[]}""").sufficientlyPaired)
        assertThrows(IllegalArgumentException::class.java) { TranslationJsonPairer.pair("UI", "42", "42") }
        assertThrows(IllegalArgumentException::class.java) { TranslationJsonPairer.pair("UI", "{}", "{}") }
        val result = TranslationJsonPairer.pair("UI", """{"id":1,"name":"名前","model":"original"}""",
            """{"id":1,"name":"译名","model":"changed"}""")
        assertEquals(listOf("name"), result.records.map { it.field })
    }

    @Test fun missingSplitLeavesTheEntireCommittedSnapshotIntact() {
        val base = write(root.resolve("input/base.apk"), "old-base")
        val split = write(root.resolve("input/split.apk"), "old-split")
        val runtime = LocalContainerRuntime(root.resolve("workspace"))
        runtime.importGame(GameImportRequest(listOf(base.toString(), split.toString()), 1L, "installed"))
        base.writeText("new-base")
        assertThrows(IllegalArgumentException::class.java) {
            runtime.importGame(GameImportRequest(listOf(base.toString(), root.resolve("missing.apk").toString()), 2L, "installed"))
        }
        assertEquals(1L, runtime.status().importedVersionCode)
        assertEquals(listOf("old-base", "old-split"), runtime.importedSplitApkPaths().map { Path.of(it).readText() })
    }

    @Test fun legacyRestorationValidatesEveryBaselineBeforeChangingGameFiles() {
        val (patch, manifest) = prepared(2, 2)
        val originals = (0..1).map { write(localize.resolve("en/File$it.json"), "original$it") }
        val installer = installer()
        installer.installPrepared(patch, manifest)
        val patched = originals.map { it.readText() }
        val baseline = patch.resolve("baseline")
        val oneBackup = Files.list(baseline).use { paths ->
            paths.filter { it.fileName.toString() != "ownership.properties" }.findFirst().get()
        }
        oneBackup.writeText("corrupt")
        assertThrows(IllegalArgumentException::class.java) { installer.restorePrepared(patch, manifest) }
        assertEquals(patched, originals.map { it.readText() })
    }

    @Test fun aGameUpdateCannotBeOverwrittenByLegacyRestoration() {
        val (patch, manifest) = prepared(1, 1)
        val original = write(localize.resolve("en/File0.json"), "original")
        val installer = installer()
        installer.installPrepared(patch, manifest)
        original.writeText("updated-by-game")
        assertThrows(IllegalArgumentException::class.java) { installer.restorePrepared(patch, manifest) }
        assertEquals("updated-by-game", original.readText())
    }

    @Test fun externalInstallationFailureRequiresRepairAndNeverCommitsNewVersion() {
        val base = write(root.resolve("input/base.apk"), "old-base")
        val workspace = root.resolve("workspace")
        val runtime = LocalContainerRuntime(workspace)
        runtime.importGame(GameImportRequest(listOf(base.toString()), 1L, "installed"))
        base.writeText("new-base")
        assertThrows(java.io.IOException::class.java) {
            runtime.importGame(GameImportRequest(listOf(base.toString()), 2L, "installed")) { throw java.io.IOException("disk full") }
        }
        val restarted = LocalContainerRuntime(workspace)
        assertEquals(1L, restarted.status().importedVersionCode)
        assertTrue(restarted.status().requiresInstalledGameResync)
        assertEquals("old-base", Path.of(restarted.importedSplitApkPaths().single()).readText())
        restarted.importGame(GameImportRequest(listOf(base.toString()), 2L, "installed"))
        assertFalse(restarted.status().requiresInstalledGameResync)
        assertEquals(2L, restarted.status().importedVersionCode)
    }
}
