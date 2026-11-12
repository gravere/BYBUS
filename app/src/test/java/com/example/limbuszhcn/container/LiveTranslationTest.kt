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
import kotlin.io.path.readText

class LiveTranslationTest {
    @Test fun typedModelFieldsDoNotDependOnPageSourceOrResourceOrder() {
        val a = """{"dataList":[{"id":"Burn","name":"烧伤","desc":"获得[Burn]。","icon":"internal"}]}"""
        val b = a.replace("烧伤", "技能标签")
        val first = ReleasedModelFields().apply {
            add("jp/JP_BattleKeywords.json", a); add("jp/JP_SkillTag.json", b)
            add("jp/JP_BattleKeywords-extra.json", a)
        }.fields()
        val second = ReleasedModelFields().apply {
            add("jp/JP_BattleKeywords-extra.json", a); add("jp/JP_SkillTag.json", b)
            add("jp/JP_BattleKeywords.json", a)
        }.fields()
        assertEquals(first, second)
        assertEquals("烧伤", first["@battle-keyword:Burn\u001fname"])
        assertEquals("技能标签", first["@skill-tag:Burn\u001fname"])
        assertEquals("获得[Burn]。", first["@battle-keyword:Burn\u001fdesc"])
        assertFalse(first.keys.any { it.endsWith("icon") })
        for (rows in listOf(listOf(a, b, a), listOf(b, a, a))) {
            val conflicts = ReleasedModelFields()
            rows.forEachIndexed { i, row -> conflicts.add("jp/JP_BattleKeywords-$i.json", row) }
            assertFalse(conflicts.fields().containsKey("@battle-keyword:Burn\u001fname"))
        }
        assertTrue(ReleasedModelFields().apply { add("jp/JP_Unknown.json", a) }.fields().isEmpty())
        assertTrue(ReleasedModelFields().apply {
            add("jp/JP_BattleKeywords.json", """{"files":["BattleKeywords.json"]}""")
        }.fields().isEmpty())
    }

    @get:Rule val temporary = TemporaryFolder()
    private val cache get() = temporary.root.toPath()
    private val source = """{"dataList":[{"id":2,"content":"次の文","voicefile":"v2"},{"id":1,"content":"開始","model":"source","cost":17}]}"""
    private val translated = """{"dataList":[{"id":1,"content":"开始","model":"wrong","cost":999},{"id":2,"content":"下一句","voicefile":"wrong"}]}"""
    private fun prepare(names: List<String> = listOf("JP_UI.json"), text: String = translated): LiveTranslationStore {
        val directory = cache.resolve("patch-cache/200")
        Files.createDirectories(directory)
        val entries = names.mapIndexed { i, key ->
            val path = directory.resolve("$i.json"); path.writeText(text)
            PatchFileEntry("$i.json", "$i.json", "jp/$key", Files.size(path), path.sha256())
        }
        val manifest = PatchManifest("200", 200, "a".repeat(64), entries.size, entries.size, entries)
        return LiveTranslationStore(cache).also { it.prepare(directory, manifest) { key -> key } }
    }

    @Test fun releasedMixedScriptNamesKeepFontProvenanceAndOldCataloguesRebuild() {
        val mixed = translated.replace("开始", "し协会 南部5科")
        val store = prepare(text = mixed)
        val fonts = cache.resolve("translation-index/live-fonts.bin")
        assertTrue(Files.readAllBytes(fonts).toString(Charsets.UTF_8).contains("し协会 南部5科"))
        assertTrue(store.open()!!.translate("JP_UI", source)!!.contains("し协会 南部5科"))
        val descriptor = cache.resolve("translation-index/live-resources.json")
        val json = JSONObject(descriptor.readText()).put("schema", 2)
        descriptor.writeText(json.toString())
        assertNull(store.read())
        assertNotNull(prepare(text = mixed).open())
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
        assertTrue(TranslationJsonPairer.releasedTexts("""{"files":["UI.json"]}""").isEmpty())
        val original = """{"dataList":[{"id":1,"content":"甲"},{"id":1,"content":"乙"},{"id":3,"content":"未翻訳"}]}"""
        assertNull(TranslationJsonPairer.merge("jp/JP_UI.json", original, translated))
        val target = """{"dataList":[{"id":2,"content":""},{"id":1,"content":"开始"}]}"""
        val out = JSONObject(TranslationJsonPairer.merge("jp/JP_UI.json", source, target)!!).getJSONArray("dataList")
        assertEquals("次の文", out.getJSONObject(0).getString("content"))
        assertEquals("开始", out.getJSONObject(1).getString("content"))
    }

    @Test fun allReleasedDisplayFieldsKeepFontCoverageWithoutRelaxingTranslationPairing() {
        val official = """{"dataList":[
            {"id":1,"name":"雪原岔道","model":"内部模型","simpleDesc":[
                {"abilityID":3,"simpleDesc":"增加强度"},
                {"abilityID":3,"simpleDesc":"增加层数"}]},
            {"id":1,"name":"雪原十字路口","result":["事件结果"],
             "files":["内部文件"],"desc":"坏字\ufffd"}]}"""
        assertEquals(setOf("雪原岔道", "雪原十字路口", "增加强度", "增加层数", "事件结果"),
            TranslationJsonPairer.releasedTexts(official).toSet())
        val source = """{"dataList":[{"id":1,"name":"原文一"},{"id":1,"name":"原文二"}]}"""
        assertNull(TranslationJsonPairer.merge("jp/JP_UI.json", source, official))
        val store = prepare(text = official)
        val catalogue = Files.readAllBytes(cache.resolve("translation-index/live-fonts.bin")).toString(Charsets.UTF_8)
        assertTrue(catalogue.contains("雪原岔道") && catalogue.contains("增加层数"))
        assertFalse(catalogue.contains("内部模型") || catalogue.contains("内部文件"))
        assertNotNull(store.open())
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
