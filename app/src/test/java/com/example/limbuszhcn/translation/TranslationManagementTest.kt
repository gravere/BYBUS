package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerPatchInstaller
import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.container.LiveTranslationStore
import com.example.limbuszhcn.update.TranslationRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

class TranslationManagementTest {
    @get:Rule val temp = TemporaryFolder()
    private val cache get() = temp.root.toPath().resolve("cache")
    private fun pack() = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for (name in listOf("MainUIText.json", "RPGSystem/Event.json")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("""{"dataList":[{"id":1,"content":"备份中文"}]}""".toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("plugin.dll")); zip.write("not executable".toByteArray()); zip.closeEntry()
        }
    }.toByteArray()

    @Test fun textBackupsRoundTripForLocalAndAiWithoutPrivateFilesOrPlugins() {
        val sources = TranslationSources(cache)
        val local = sources.import(pack().inputStream(), "文本.zip")
        val file = temp.root.toPath().resolve("generated.zip"); Files.write(file, pack())
        val ai = sources.addGenerated(file, local.id, "1234567890abcdef", "AI 文本")
        cache.resolve("private-key.txt").writeText("private-marker")
        for (source in listOf(local, ai)) {
            val prepared = sources.prepare(source)
            prepared.directory.resolve("account.json").writeText("private-marker")
            val bytes = ByteArrayOutputStream().also { TranslationBackup.write(prepared, it) }.toByteArray()
            val names = mutableListOf<String>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break
                    names += entry.name
                    assertFalse(zip.readBytes().toString(Charsets.UTF_8).contains("private-marker"))
                }
            }
            assertEquals(prepared.manifest.entries.map { it.targetPath }, names)
            val imported = sources.import(bytes.inputStream(), "备份.zip")
            assertEquals(2, sources.prepare(imported).manifest.entries.size)
        }
        assertEquals("自定义__文本.zip", TranslationBackup.fileName(local.copy(name = "自定义:/文本")))
    }

    @Test fun backupRejectsCorruptionAndEscapingTargetsBeforeWriting() {
        val sources = TranslationSources(cache)
        val source = sources.import(pack().inputStream(), "文本.zip")
        val prepared = sources.prepare(source)
        val out = ByteArrayOutputStream()
        val unsafe = prepared.copy(manifest = prepared.manifest.copy(entries = listOf(
            prepared.manifest.entries.first().copy(targetPath = "en/../private.json"))))
        assertThrows(IllegalArgumentException::class.java) { TranslationBackup.write(unsafe, out) }
        assertEquals(0, out.size())
        prepared.directory.resolve(prepared.manifest.entries.first().cachePath).writeText("corrupt")
        assertThrows(IllegalArgumentException::class.java) { TranslationBackup.write(prepared, out) }
        assertEquals(0, out.size())
    }

    @Test fun deletingAnActiveSourceDropsRuntimeAndDerivedFilesButKeepsOtherPacksAndGameData() {
        val sources = TranslationSources(cache)
        val first = sources.import(pack().inputStream(), "一.zip")
        val second = sources.import(pack().inputStream(), "二.zip")
        val game = temp.root.toPath().resolve("game")
        Files.createDirectories(game); game.resolve("account.json").writeText("game-data")
        val installer = ContainerPatchInstaller(cache, ContainerWorkspaceStorage(game))
        val prepared = sources.prepare(second)
        val activation = SourceActivation(cache)
        activation.run(second.id, second.revision) { installer.activateImported(prepared.directory, prepared.manifest) }
        val versions = Files.createDirectories(cache.resolve("translation-index/versions/obsolete"))
        versions.resolve("index.bin").writeText("compiled")
        cache.resolve("translation-index/previous-index.path").writeText("obsolete")
        installer.uninstallLatest(); activation.discardDerived()
        sources.remove(second.id); sources.pruneUnused(); activation.recover()
        assertNull(LiveTranslationStore(cache).read())
        assertFalse(Files.exists(versions))
        assertFalse(Files.exists(cache.resolve("translation-index/previous-index.path")))
        assertEquals("zero", sources.read().selectedId)
        assertEquals(2, sources.prepare(first).manifest.entries.size)
        assertEquals("game-data", game.resolve("account.json").readText())
    }

    @Test fun officialUninstallKeepsSubscriptionAndCustomPacksButRemovesDownloadedStatus() {
        val sources = TranslationSources(cache)
        assertFalse(sources.read().officialDownloaded)
        val downloads = Files.createDirectories(cache.resolve("downloads"))
        downloads.resolve("LimbusLocalize_123.7z").writeText("cached archive")
        assertTrue(sources.read().officialDownloaded) // Also works before a registry has ever been saved.
        val local = sources.import(pack().inputStream(), "保留.zip")
        sources.select("zero")
        Files.createDirectories(cache.resolve("patch-cache/123"))
        TranslationRepository(cache).prune(emptySet())
        assertFalse(sources.read().officialDownloaded)
        assertTrue(sources.read().selected.isOfficial)
        assertFalse(Files.exists(cache.resolve("patch-cache/123")))
        assertEquals(2, sources.prepare(local).manifest.entries.size)
    }
}
