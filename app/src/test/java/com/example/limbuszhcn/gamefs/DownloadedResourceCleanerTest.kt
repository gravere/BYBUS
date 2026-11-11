package com.example.limbuszhcn.gamefs

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class DownloadedResourceCleanerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file(path: Path, text: String = "resource"): Path {
        Files.createDirectories(path.parent)
        return Files.write(path, text.toByteArray())
    }
    private fun workspace() = temporary.newFolder().toPath()

    @Test fun clearsAllDownloadRootsAndPreservesAccountsInstallationAndTranslation() {
        val root = workspace()
        val assets = root.resolve("game/external/files/Assets")
        val roots = linkedMapOf("assets" to assets,
            "external-unity-cache" to root.resolve("game/external/cache/UnityCache/Shared"),
            "external-files-unity-cache" to root.resolve("game/external/files/UnityCache/Shared"),
            "internal-unity-cache" to root.resolve("game/internal/cache/UnityCache/Shared"),
            "internal-files-unity-cache" to root.resolve("game/internal/files/UnityCache/Shared"))
        roots.values.forEach { file(it.resolve("nested/download.bin")) }
        val keep = listOf("game/internal/shared_prefs/player.xml", "game/external/files/settings.json",
            "google/accounts.db", "system/base.apk", "imports/base.apk", "translation-cache/patch-cache/zh.json")
            .map { file(root.resolve(it), "keep") }
        val storage = ContainerWorkspaceStorage(assets.resolve("Resources_moved/Localize"), roots)
        val result = storage.clearDownloadedResources()
        assertEquals(5L, result.files)
        assertEquals(40L, result.bytes)
        roots.values.forEach { assertEquals(0L, Files.list(it).use { paths -> paths.count() }) }
        keep.forEach { assertEquals("keep", it.toFile().readText()) }
        assertEquals(ResourceClearResult(0, 0), storage.clearDownloadedResources())
    }

    @Test fun validatesAllRootsBeforeDeletingAnything() {
        val root = workspace()
        val assets = root.resolve("files/Assets")
        val resource = file(assets.resolve("resource.bin"))
        val account = file(root.resolve("shared_prefs/account.xml"), "keep")
        val storage = ContainerWorkspaceStorage(assets.resolve("Resources_moved/Localize"), linkedMapOf(
            "assets" to assets, "internal-unity-cache" to root))
        assertThrows(IllegalArgumentException::class.java) { storage.clearDownloadedResources() }
        assertTrue(Files.exists(resource))
        assertEquals("keep", account.toFile().readText())
    }

    @Test fun refusesUnknownAliasesAndUnexpectedResourceRoot() {
        val root = workspace()
        val assets = root.resolve("files/Assets")
        val resource = file(assets.resolve("resource.bin"))
        val other = root.resolve("other/Assets")
        file(other.resolve("unrelated.bin"))
        val localize = assets.resolve("Resources_moved/Localize")
        for (roots in listOf(mapOf("accounts" to assets), mapOf("assets" to other), emptyMap())) {
            assertThrows(IllegalArgumentException::class.java) { DownloadedResourceCleaner.clear(localize, roots) }
            assertTrue(Files.exists(resource))
        }
    }

    @Test fun neverFollowsLinksAndRejectsLinkedResourceParents() {
        val root = workspace()
        val assets = root.resolve("files/Assets")
        val protected = file(root.resolve("outside/account.xml"), "keep")
        Files.createDirectories(assets)
        val link = assets.resolve("nested-link")
        val supported = runCatching { Files.createSymbolicLink(link, protected.parent); true }.getOrDefault(false)
        assumeTrue("Filesystem must allow symlinks", supported)
        val localize = assets.resolve("Resources_moved/Localize")
        DownloadedResourceCleaner.clear(localize, mapOf("assets" to assets))
        assertEquals("keep", protected.toFile().readText())
        val resource = file(assets.resolve("resource.bin"))
        val cache = root.resolve("cache/UnityCache")
        Files.createDirectories(cache.parent)
        Files.createSymbolicLink(cache, protected.parent)
        assertThrows(IllegalArgumentException::class.java) {
            DownloadedResourceCleaner.clear(localize, linkedMapOf("assets" to assets,
                "internal-unity-cache" to cache.resolve("Shared")))
        }
        assertTrue(Files.exists(resource))
        assertEquals("keep", protected.toFile().readText())
    }
}
