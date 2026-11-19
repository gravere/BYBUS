package com.example.limbuszhcn.container

import com.example.limbuszhcn.update.PatchFileEntry
import com.example.limbuszhcn.update.PatchManifest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.io.path.readText

class MultilingualTranslationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun write(path: Path, text: String) { Files.createDirectories(path.parent); path.writeText(text) }

    @Test fun everyLocaleKeepsSkillGiftEventAndStoryIdentity() {
        val tables = listOf(
            Triple("Skills_Test", """{"dataList":[{"id":100,"levelList":[{"level":4,"desc":"VALUE","coinlist":[{"coindescs":[{"desc":"VALUE"}]}]}]}]}""",
                listOf("@skill:100/level:4", "@skill:100/level:4/coin:0/text:0")),
            Triple("EGOgift_Mirror", """{"dataList":[{"id":98,"simpleDesc":[{"abilityID":981,"simpleDesc":"VALUE"}]}]}""",
                listOf("@ego-gift:98/ability:981")),
            Triple("ActionEvents_Test", """{"dataList":[{"id":31,"desc":"VALUE"}]}""", listOf("@action-event:31")),
            Triple("Bufs", """{"dataList":[{"id":"Rupture","name":"VALUE"}]}""", listOf("@buf:Rupture")),
            Triple("BattleKeywords", """{"dataList":[{"id":"OnHit","content":"VALUE"}]}""", listOf("@battle-keyword:OnHit")),
            Triple("StoryData/1D101A", """{"dataList":[{"id":1,"dialog":"VALUE","voicefile":"voice_1"}]}""",
                listOf("@story:1D101A/dialog:1"))
        )
        for ((locale, phrase) in mapOf("jp" to "同じ説明", "en" to "The same description", "kr" to "같은 설명")) {
            for ((table, template, expected) in tables) {
                val dir = table.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                val path = "$locale/$dir${locale.uppercase()}_${table.substringAfterLast('/')}.json"
                val rows = TranslationJsonPairer.pair(path, template.replace("VALUE", phrase), template.replace("VALUE", "零协显示文本"))
                assertEquals(path, expected.toSet(), rows.records.map { it.stableId }.toSet())
                assertEquals(expected.size, rows.matchedFields)
                assertTrue(rows.records.all { it.translation == "零协显示文本" })
            }
        }
    }

    @Test fun multilingualTermsRetainGlossaryPriorityAndDoNotDeriveProse() {
        val compiled = TranslationIndexCompiler().apply {
            add(TranslationIndexRecord("en/EN_Bufs.json", "r", "name", "Rupture Count", "破裂层数", "@buf:r"))
            add(TranslationIndexRecord("en/EN_Skills.json", "s", "name", "Rupture Count", "破裂计数", "@skill:1"))
            add(TranslationIndexRecord("kr/KR_Bufs.json", "t", "name", "진동 폭발", "震颤爆发", "@buf:t"))
            add(TranslationIndexRecord("en/EN_BattleKeywords.json", "h", "content", "[On Hit]", "[命中时]", "@battle-keyword:h"))
            add(TranslationIndexRecord("en/EN_UI.json", "p", "content", "This is ordinary prose", "这是一段正文", "p"))
        }.compile("v", "sha")
        assertEquals("破裂层数", compiled.termEntries["Rupture Count"])
        assertEquals("震颤爆发", compiled.termEntries["진동 폭발"])
        assertEquals("命中时", compiled.termEntries["On Hit"])
        assertNull(compiled.termEntries["This is ordinary prose"])
        assertEquals("这是一段正文", compiled.uniqueEntries["This is ordinary prose"])
        assertEquals("破裂计数", compiled.contextualEntries[TranslationIndexCompiler.contextKey("@skill:1", "name", "Rupture Count")])
    }

    @Test fun englishOnlyInstallationAndKoreanResourceChangesUseFullIndex() {
        val root = temporary.root.toPath()
        val cache = root.resolve("cache")
        val patch = cache.resolve("patch-cache/200")
        val localize = root.resolve("Localize")
        fun table(text: String) = """{"dataList":[{"id":"label","content":"$text"}]}"""
        write(patch.resolve("LoginUIText.json"), table("登录说明"))
        write(localize.resolve("en/EN_LoginUIText.json"), table("Login instructions"))
        val manifest = verifiedFixtureManifest(patch, PatchManifest("200", 200, "archive", 1, 1,
            listOf(PatchFileEntry("LoginUIText.json", "LoginUIText.json", "en/EN_LoginUIText.json", 1, "hash"))))
        fun installer() = ContainerPatchInstaller(cache, ContainerWorkspaceStorage(localize))
        assertTrue(installer().activateRedirect(patch, manifest).indexActivated)
        assertTrue(installer().activateRedirect(patch, manifest).reusedIndex)
        write(localize.resolve("kr/KR_LoginUIText.json"), table("로그인 안내"))
        val added = installer().activateRedirect(patch, manifest)
        assertFalse(added.reusedIndex)
        assertTrue(added.indexActivated)
        assertEquals(2, added.sourceRecordCount)
        assertTrue(installer().activateRedirect(patch, manifest).reusedIndex)
        write(localize.resolve("kr/KR_LoginUIText.json"), table("새 로그인 안내"))
        assertFalse(installer().activateRedirect(patch, manifest).reusedIndex)
        assertEquals(table("Login instructions"), localize.resolve("en/EN_LoginUIText.json").readText())
    }
}
