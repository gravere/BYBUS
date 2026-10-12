package com.example.limbuszhcn.container

import com.example.limbuszhcn.gamefs.GameStorageInstaller
import com.example.limbuszhcn.update.PatchFileEntry
import com.example.limbuszhcn.update.PatchManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ContainerWorkspaceStorageTest {
    @Test
    fun probesContainerLocalizeTree() {
        val root = Files.createTempDirectory("limbus-container-test")
        val localize = root.resolve("Localize")
        localize.resolve("en").toFile().mkdirs()
        localize.resolve("RemoteLocalizeFileList.json").writeText("{}")
        localize.resolve("en/EN_LoginUIText.json").writeText("{}")

        val probe = ContainerWorkspaceStorage(localize).probe()

        assertTrue(probe.exists)
        assertEquals(1, probe.jsonFileCount)
        assertTrue(probe.remoteListPresent)
    }

    @Test
    fun installsPatchToExistingPrefixedFile() {
        val root = Files.createTempDirectory("limbus-container-test")
        val localize = root.resolve("Localize")
        localize.resolve("en").toFile().mkdirs()
        localize.resolve("en/EN_LoginUIText.json").writeText("""{"old":true}""")
        val patchRoot = root.resolve("patch")
        patchRoot.resolve("files/en").toFile().mkdirs()
        patchRoot.resolve("files/en/LoginUIText.json").writeText("""{"content":"Google登录"}""")
        val storage = ContainerWorkspaceStorage(localize)

        val summary = GameStorageInstaller(storage).install(patchRoot, manifest("LoginUIText.json"))

        assertEquals(1, summary.writtenFiles)
        assertEquals("""{"content":"Google登录"}""", localize.resolve("en/EN_LoginUIText.json").readText())
        assertFalse(localize.resolve("en/LoginUIText.json").exists())
    }

    @Test
    fun restoresExistingFileFromBaseline() {
        val root = Files.createTempDirectory("limbus-container-test")
        val localize = root.resolve("Localize")
        localize.resolve("en").toFile().mkdirs()
        localize.resolve("en/EN_MainUIText.json").writeText("""{"content":"Sinners"}""")
        val patchRoot = root.resolve("translation-cache/patch-cache/2026050702")
        patchRoot.resolve("files/en").toFile().mkdirs()
        patchRoot.resolve("files/en/MainUIText.json").writeText("""{"content":"罪人"}""")
        val manifest = manifest("MainUIText.json")
        patchRoot.resolve("manifest.json").writeText(manifest.toJson())
        val installer = ContainerPatchInstaller(
            cacheRoot = root.resolve("translation-cache"),
            storage = ContainerWorkspaceStorage(localize)
        )

        installer.installPrepared(patchRoot, manifest)
        val summary = installer.restorePrepared(patchRoot, manifest)

        assertEquals("2026050702", summary.version)
        assertEquals(1, summary.deletedFiles)
        assertEquals("""{"content":"Sinners"}""", localize.resolve("en/EN_MainUIText.json").readText())
    }

    @Test
    fun removesNewFileWhenRestoringMissingBaseline() {
        val root = Files.createTempDirectory("limbus-container-test")
        val localize = root.resolve("Localize")
        localize.resolve("en").toFile().mkdirs()
        val patchRoot = root.resolve("translation-cache/patch-cache/2026050702")
        patchRoot.resolve("files/en").toFile().mkdirs()
        patchRoot.resolve("files/en/NewUIText.json").writeText("""{"content":"新增"}""")
        val manifest = manifest("NewUIText.json")
        patchRoot.resolve("manifest.json").writeText(manifest.toJson())
        val installer = ContainerPatchInstaller(
            cacheRoot = root.resolve("translation-cache"),
            storage = ContainerWorkspaceStorage(localize)
        )

        installer.installPrepared(patchRoot, manifest)
        val summary = installer.restorePrepared(patchRoot, manifest)

        assertEquals(1, summary.deletedFiles)
        assertFalse(localize.resolve("en/NewUIText.json").exists())
    }

    @Test
    fun rejectsPathTraversalOutsideLocalizeRoot() {
        val root = Files.createTempDirectory("limbus-container-test")
        val storage = ContainerWorkspaceStorage(root.resolve("Localize"))

        val deleted = runCatching { storage.delete("${storage.rootPath()}/../escape.json") }

        assertTrue(deleted.isFailure)
    }

    @Test
    fun importsSplitApksIntoWorkspace() {
        val root = Files.createTempDirectory("limbus-container-test")
        val split = root.resolve("base.apk")
        split.writeText("apk")
        val runtime = LocalContainerRuntime(root.resolve("workspace"))

        val result = runtime.importGame(
            GameImportRequest(
                splitApkPaths = listOf(split.toString()),
                versionCode = 2026051501L,
                source = "installed"
            )
        )
        val status = runtime.status()

        assertEquals(LocalContainerRuntime.GAME_PACKAGE, result.packageName)
        assertEquals(1, result.importedSplits)
        assertEquals(2026051501L, result.versionCode)
        assertEquals("installed", result.source)
        assertTrue(status.gameImported)
        assertEquals(1, status.importedSplits)
        assertEquals(2026051501L, status.importedVersionCode)
        assertEquals("installed", status.importSource)
        assertTrue(java.nio.file.Path.of(runtime.importedSplitApkPaths().single()).exists())
        assertTrue(runtime.storage().probe().exists)
    }

    @Test
    fun replacesOldWorkspaceSplitsOnImport() {
        val root = Files.createTempDirectory("limbus-container-test")
        val first = root.resolve("split_old.apk")
        val second = root.resolve("base.apk")
        first.writeText("old")
        second.writeText("new")
        val runtime = LocalContainerRuntime(root.resolve("workspace"))

        runtime.importGame(GameImportRequest(splitApkPaths = listOf(first.toString()), source = "installed"))
        runtime.importGame(GameImportRequest(splitApkPaths = listOf(second.toString()), source = "installed"))

        assertEquals(1, runtime.importedSplitApkPaths().size)
        assertEquals("new", java.nio.file.Path.of(runtime.importedSplitApkPaths().single()).readText())
    }

    private fun manifest(fileName: String): PatchManifest =
        PatchManifest(
            requestedVersion = "2026050702",
            archiveVersion = 2026050702,
            archiveSha256 = "archive",
            archiveFileCount = 1,
            extractedFileCount = 1,
            entries = listOf(
                PatchFileEntry(
                    sourcePath = "LimbusCompany_Data/Lang/LLC_zh-CN/$fileName",
                    cachePath = "files/en/$fileName",
                    targetPath = "en/$fileName",
                    size = 1,
                    sha256 = "file"
                )
            )
        )
}
