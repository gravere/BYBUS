package com.example.limbuszhcn.container

import com.example.limbuszhcn.update.PatchFileEntry
import com.example.limbuszhcn.update.PatchManifest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StartupTranslationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root.toPath()
    private val cache get() = root.resolve("cache")
    private val index get() = cache.resolve("translation-index")
    private val localize get() = root.resolve("Localize")
    private fun write(path: Path, text: String) { Files.createDirectories(path.parent); path.writeText(text) }
    private fun source(i: Int) = """{"dataList":[{"id":1,"content":"ログインの説明$i"}]}"""
    private fun translated(i: Int) = """{"dataList":[{"id":1,"content":"登录说明$i"}]}"""
    private fun inputs(): Pair<Path, PatchManifest> {
        val patch = cache.resolve("patch-cache/200")
        val entries = (0 until 10).map { i ->
            val name = if (i == 0) "LoginUIText" else "File$i"
            write(patch.resolve("$name.json"), translated(i))
            PatchFileEntry("$name.json", "$name.json", "en/EN_$name.json", 1, "hash")
        }
        return patch to PatchManifest("200", 200, "archive", 10, 10, entries)
    }
    private fun apk(): Path {
        val path = root.resolve("official-assets.apk")
        ZipOutputStream(Files.newOutputStream(path)).use {
            it.putNextEntry(ZipEntry("assets/bin/Data/" + "1".repeat(32)))
            it.write(BundledBattleHintsTest().fixture(source(0), "JP_LoginUIText"))
            it.closeEntry()
        }
        return path
    }

    @Test fun firstRunUsesBundledLoginWithoutClaimingFullResourceReadiness() {
        val (patch, manifest) = inputs()
        val apk = apk()
        val installer = ContainerPatchInstaller(cache, ContainerWorkspaceStorage(localize), { listOf(apk.toString()) })
        val summary = installer.activateRedirect(patch, manifest)
        assertFalse(summary.indexActivated)
        assertTrue(summary.startupIndexAvailable)
        assertEquals(0, summary.availableSourceFiles)
        assertNull(installer.activeInstallSummary())
        assertEquals(com.example.limbuszhcn.TranslationLaunchMode.NeedsResources,
            com.example.limbuszhcn.translationLaunchMode(installer.activeInstallSummary(), false))
        val store = TranslationIndexStore(index)
        assertEquals("startup", store.selectRuntimeIndex())
        val runtime = index.resolve(TranslationIndexStore.RUNTIME_POINTER).readText().trim()
        assertTrue(runtime.contains("bootstrap-"))
        assertTrue(Files.isRegularFile(Path.of(runtime)))
        // A startup generation cannot masquerade as a complete one, even with a copied pointer.
        index.resolve(TranslationIndexStore.ACTIVE_POINTER).writeText(runtime)
        assertNull(installer.activeInstallSummary())
        assertEquals("startup", store.selectRuntimeIndex())
        Files.delete(index.resolve(TranslationIndexStore.ACTIVE_POINTER))
        assertEquals(setOf("200"), store.pruneAndRetainedVersions())
        assertTrue(Files.isRegularFile(Path.of(runtime)))

        manifest.entries.forEachIndexed { i, e -> write(localize.resolve("jp/JP_" + e.cachePath), source(i)) }
        // Model the next launcher process after the game downloaded its resources.
        val nextLaunch = ContainerPatchInstaller(cache, ContainerWorkspaceStorage(localize), { listOf(apk.toString()) })
        val complete = nextLaunch.activateRedirect(patch, manifest)
        assertTrue(complete.indexActivated)
        assertEquals(com.example.limbuszhcn.TranslationLaunchMode.Localized,
            com.example.limbuszhcn.translationLaunchMode(nextLaunch.activeInstallSummary(), false))
        assertEquals("full", store.selectRuntimeIndex())
        assertFalse(Files.exists(index.resolve(TranslationIndexStore.BOOTSTRAP_POINTER)))
        assertFalse(index.resolve(TranslationIndexStore.RUNTIME_POINTER).readText().contains("bootstrap-"))
        assertTrue(nextLaunch.activateRedirect(patch, manifest).reusedIndex)
        assertEquals(source(0), localize.resolve("jp/JP_LoginUIText.json").readText())
    }

    @Test fun partialDownloadedUiIsUsefulButNeverReplacesAFullIndex() {
        val (patch, manifest) = inputs()
        write(localize.resolve("jp/JP_LoginUIText.json"), source(0))
        val installer = ContainerPatchInstaller(cache, ContainerWorkspaceStorage(localize))
        assertTrue(installer.activateRedirect(patch, manifest).startupIndexAvailable)
        val store = TranslationIndexStore(index)
        store.activate(CompiledTranslationIndex("100", "old", "old", mapOf("以前の文" to "旧译文"),
            emptyMap(), 50, 0, 0, 10, 10))
        val summary = installer.activateRedirect(patch, manifest)
        assertFalse(summary.indexActivated)
        assertTrue(summary.previousIndexPreserved)
        assertEquals("full", store.selectRuntimeIndex())
        assertEquals("100", installer.activeInstallSummary()!!.version)
    }

    @Test fun partiallyPairedBundledTableKeepsProvenRowsWithoutClaimingFullReadiness() {
        val (patch, manifest) = inputs()
        fun table(count: Int, text: String) = (1..count).joinToString(",", "{\"dataList\":[", "]}") {
            "{\"id\":$it,\"content\":\"$text$it\"}"
        }
        write(patch.resolve("LoginUIText.json"), table(8, "已核对提示"))
        val apk = root.resolve("partial-assets.apk")
        ZipOutputStream(Files.newOutputStream(apk)).use {
            it.putNextEntry(ZipEntry("assets/bin/Data/" + "1".repeat(32)))
            it.write(BundledBattleHintsTest().fixture(table(10, "ログインの説明"), "JP_LoginUIText"))
            it.closeEntry()
        }
        val summary = ContainerPatchInstaller(cache, ContainerWorkspaceStorage(localize), { listOf(apk.toString()) })
            .activateRedirect(patch, manifest)
        assertFalse(summary.indexActivated)
        assertTrue(summary.startupIndexAvailable)
        assertEquals(0, summary.availableSourceFiles)
        val store = TranslationIndexStore(index)
        assertEquals("8", store.bootstrapManifest()!!.getProperty("sourceRecordCount"))
        assertNull(store.activeManifest())
        assertEquals("startup", store.selectRuntimeIndex())
    }

    @Test fun corruptOrStaleBootstrapIsNotSelectedAndDisableClearsEveryPointer() {
        val (patch, manifest) = inputs()
        write(localize.resolve("jp/JP_LoginUIText.json"), source(0))
        val installer = ContainerPatchInstaller(cache, ContainerWorkspaceStorage(localize))
        installer.activateRedirect(patch, manifest)
        val store = TranslationIndexStore(index)
        assertEquals("startup", store.selectRuntimeIndex())
        val binary = Path.of(index.resolve(TranslationIndexStore.RUNTIME_POINTER).readText().trim())
        binary.writeText("corrupt")
        assertEquals("none", store.selectRuntimeIndex())
        assertFalse(Files.exists(index.resolve(TranslationIndexStore.RUNTIME_POINTER)))
        installer.activateRedirect(patch, manifest)
        assertEquals("startup", store.selectRuntimeIndex())
        write(localize.resolve("jp/JP_LoginUIText.json"), "{")
        assertFalse(installer.activateRedirect(patch, manifest).startupIndexAvailable)
        assertEquals("none", store.selectRuntimeIndex())
        write(localize.resolve("jp/JP_LoginUIText.json"), source(0))
        installer.activateRedirect(patch, manifest)
        store.selectRuntimeIndex()
        installer.uninstallLatest()
        assertFalse(Files.exists(index.resolve(TranslationIndexStore.BOOTSTRAP_POINTER)))
        assertFalse(Files.exists(index.resolve(TranslationIndexStore.RUNTIME_POINTER)))
        assertEquals(source(0), localize.resolve("jp/JP_LoginUIText.json").readText())
    }
}
