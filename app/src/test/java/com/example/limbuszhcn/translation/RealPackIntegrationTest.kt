package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerPatchInstaller
import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.container.LiveTranslationStore
import com.example.limbuszhcn.container.TranslationJsonPairer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Opt-in integration with independently downloaded, hash-checked public archives. */
class RealPackIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun input(name: String): java.nio.file.Path {
        val value = System.getenv(name)
        assumeTrue("External integration fixtures are explicitly supplied", !value.isNullOrBlank())
        return Paths.get(value!!).also { require(Files.isRegularFile(it)) }
    }

    @Test fun importsEveryDisplayResourceFromRealChapterPack() {
        val archive = input("BYBUS_REAL_SOURCE_ZIP")
        val expected = ZipFile(archive.toFile()).use { zip -> zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".json") }
            .filter { TranslationJsonPairer.releasedTexts(zip.getInputStream(it).bufferedReader().readText()).isNotEmpty() }
            .map { entry -> "en/" + entry.name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } +
                "EN_" + entry.name.substringAfterLast('/') }.toSet() }
        assertTrue(expected.any { it.startsWith("en/RPGSystem/") })
        val sources = TranslationSources(temp.root.toPath().resolve("cache"))
        val source = Files.newInputStream(archive).use { sources.import(it, "第十章补充包.zip") }
        val actual = sources.prepare(source).manifest.entries.map { it.targetPath }.toSet()
        assertEquals("Every recognized display file in a supported package must survive import", expected, actual)
        println("real chapter package: ${actual.size} display files retained")
    }

    @Test fun officialPackAndIndependentPackCanSwitchRepairAndReturnWithoutMixing() {
        val zero = input("BYBUS_ZERO_7Z")
        val other = input("BYBUS_REAL_SOURCE_ZIP")
        val originalUi = input("BYBUS_ORIGINAL_JP_UI")
        val cache = temp.root.toPath().resolve("cache")
        val storageRoot = temp.root.toPath().resolve("Localize")
        Files.createDirectories(storageRoot.resolve("jp"))
        Files.copy(originalUi, storageRoot.resolve("jp/JP_MainUIText.json"))
        val sources = TranslationSources(cache)
        val one = Files.newInputStream(zero).use { sources.import(it, "零协本地包.7z") }
        val two = Files.newInputStream(other).use { sources.import(it, "第十章补充包.zip") }
        val installer = ContainerPatchInstaller(cache, ContainerWorkspaceStorage(storageRoot))
        val activation = SourceActivation(cache)
        fun apply(source: TranslationSource, repair: Boolean = false) {
            sources.select(source.id)
            val data = sources.prepare(source)
            activation.run(source.id) { installer.activateImported(data.directory, data.manifest, repair) }
            assertEquals(source.id, activation.owner())
            assertEquals(data.manifest.entries.size, requireNotNull(LiveTranslationStore(cache).read()).entries.size)
        }
        apply(one)
        val original = originalUi.readText()
        val expected = requireNotNull(LiveTranslationStore(cache).open()).translate("JP_MainUIText", original)
        assertNotNull("Real Japanese UI resource must pair with the official Chinese package", expected)
        assertNotEquals(original, expected)
        apply(two)
        assertNull("Partial source must not retain MainUIText from the previous source",
            requireNotNull(LiveTranslationStore(cache).open()).translate("JP_MainUIText", original))
        val damaged = sources.prepare(two).let { it.directory.resolve(it.manifest.entries.first().cachePath) }
        damaged.writeText("broken")
        apply(two, repair = true)
        assertNotEquals("broken", damaged.readText())
        apply(one)
        assertEquals(expected, requireNotNull(LiveTranslationStore(cache).open()).translate("JP_MainUIText", original))
        assertEquals(original, storageRoot.resolve("jp/JP_MainUIText.json").readText())
        println("real archives: source switching, selected-source repair, original preservation and return passed")
    }
}
