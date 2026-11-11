package com.example.limbuszhcn.container

import com.example.limbuszhcn.update.PatchFileEntry
import com.example.limbuszhcn.update.PatchManifest
import com.example.limbuszhcn.update.sha256
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.io.path.writeText

class LiveTranslationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cache get() = temporary.root.toPath()
    private val source = """{"dataList":[{"id":2,"content":"次の文","voicefile":"v2"},{"id":1,"content":"開始","model":"source","cost":17}]}"""
    private val translated = """{"dataList":[{"id":1,"content":"开始","model":"wrong","cost":999},{"id":2,"content":"下一句","voicefile":"wrong"}]}"""
    private fun prepare(names: List<String> = listOf("JP_UI.json")): LiveTranslationStore {
        val directory = cache.resolve("patch-cache/200")
        Files.createDirectories(directory)
        val entries = names.mapIndexed { i, key ->
            val path = directory.resolve("$i.json"); path.writeText(translated)
            PatchFileEntry("$i.json", "$i.json", "jp/$key", Files.size(path), path.sha256())
        }
        val manifest = PatchManifest("200", 200, "a".repeat(64), entries.size, entries.size, entries)
        return LiveTranslationStore(cache).also { it.prepare(directory, manifest) { key -> key } }
    }

    @Test fun firstSessionTranslatesResourcesAsTheyArriveWithoutOriginalFilesOrRestart() {
        val store = prepare(listOf("JP_UI.json", "JP_Story.json"))
        assertFalse(Files.exists(cache.resolve("Localize")))
        val running = requireNotNull(store.open())
        val ui = JSONObject(requireNotNull(running.translate("JP_UI", source))).getJSONArray("dataList")
        assertEquals("下一句", ui.getJSONObject(0).getString("content"))
        assertEquals("v2", ui.getJSONObject(0).getString("voicefile"))
        assertEquals("开始", ui.getJSONObject(1).getString("content"))
        assertEquals("source", ui.getJSONObject(1).getString("model"))
        assertEquals(17, ui.getJSONObject(1).getInt("cost"))
        // A later read in this same instance models text downloaded after login.
        assertNotNull(running.translate("Localize/jp/JP_Story.json", source))
        val changed = source.replace("次の文", "更新後の文")
        assertNotNull(running.translate("JP_Story", changed))
        assertNull(running.translate("JP_Story", """{"dataList":[{"id":9,"content":"新章"}]}"""))
        assertNotNull(store.open()!!.translate("JP_UI", source))
        val installer = ContainerPatchInstaller(cache, ContainerWorkspaceStorage(cache.resolve("Localize")))
        assertEquals("200", installer.uninstallLatest().version)
        assertNull(store.open())
    }

    @Test fun resourcesAreScopedAndAmbiguousNamesAreNotGuessed() {
        val runtime = prepare(listOf("a/JP_UI.json", "b/JP_UI.json")).open()!!
        assertNull(runtime.translate("JP_UI", source))
        assertNotNull(runtime.translate("jp/a/JP_UI.json", source))
        assertNull(runtime.translate("en/a/JP_UI.json", source))
        assertNull(runtime.translate("jp/a/../b/JP_UI.json", source))
        assertNull(runtime.translate("/other/JP_UI.json", source))
        assertNull(runtime.translate("JP_Unknown", source))
    }

    @Test fun duplicateIdsAndUnmatchedLeavesRetainTheOriginal() {
        assertTrue(TranslationJsonPairer.releasedTexts("jp/JP_Index.json", """{"files":["UI.json"]}""").isEmpty())
        val original = """{"dataList":[{"id":1,"content":"甲"},{"id":1,"content":"乙"},{"id":3,"content":"未翻訳"}]}"""
        assertNull(TranslationJsonPairer.merge("jp/JP_UI.json", original, translated))
        val target = """{"dataList":[{"id":2,"content":""},{"id":1,"content":"开始"}]}"""
        val out = JSONObject(TranslationJsonPairer.merge("jp/JP_UI.json", source, target)!!).getJSONArray("dataList")
        assertEquals("次の文", out.getJSONObject(0).getString("content"))
        assertEquals("开始", out.getJSONObject(1).getString("content"))
    }

    @Test fun nestedSkillDescriptionsUseTheExistingPairingPolicyAndKeepNumbers() {
        val original = """{"dataList":[{"id":11,"name":"技","levelList":[{"level":1,"coinlist":[{"coindescs":[{"desc":"命中時"}]}],"power":7}]}]}"""
        val target = original.replace("技", "技能").replace("命中時", "命中时").replace("\"power\":7", "\"power\":99")
        val merged = TranslationJsonPairer.merge("jp/JP_Skills.json", original, target)!!
        assertTrue(merged.contains("命中时")); assertTrue(merged.contains("\"power\":7"))
        assertFalse(merged.contains("99"))
    }

    @Test fun corruptSnapshotOrResourceCannotBeUsedAndDisableRemovesTheLivePath() {
        val store = prepare(); val running = store.open()!!
        cache.resolve("patch-cache/200/0.json").writeText(translated.replace("开始", "错误"))
        assertThrows(IllegalArgumentException::class.java) { running.translate("JP_UI", source) }
        cache.resolve("translation-index/live-fonts.bin").writeText("corrupt")
        assertNull(store.open())
        store.deactivate(); assertNull(store.read())
    }
}
