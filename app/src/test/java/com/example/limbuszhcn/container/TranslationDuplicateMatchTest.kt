package com.example.limbuszhcn.container

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranslationDuplicateMatchTest {
    @Test fun sameSpeakerAndIdAreDisambiguatedByDisplayShapeInEveryLanguage() {
        for ((language, line) in listOf("jp" to "私たちも入ろう", "en" to "Let us follow", "kr" to "우리도 따라 들어가자")) {
            val path = "$language/StoryData/${language.uppercase()}_S737B.json"
            val source = """{"dataList":[{"id":172,"place":"???","model":"단테","content":"<...!>"},{"id":172,"model":"단테","content":"$line"}]}"""
            // Translation order is deliberately reversed.
            val target = """{"dataList":[{"id":172,"model":"단테","content":"我们也跟着进去。"},{"id":172,"place":"？？？","model":"단테","content":"<……啊！>"}]}"""
            val merged = TranslationJsonPairer.mergeWithCoverage(path, source, target)
            val rows = (merged.document as JSONObject).getJSONArray("dataList")
            assertEquals("<……啊！>", rows.getJSONObject(0).getString("content"))
            assertEquals("我们也跟着进去。", rows.getJSONObject(1).getString("content"))
            assertEquals("단테", rows.getJSONObject(0).getString("model"))
            assertTrue(merged.contains(rows.getJSONObject(0), "place"))
            assertTrue(merged.contains(rows.getJSONObject(1), "content"))
            assertEquals(3, TranslationJsonPairer.pair(path, source, target).matchedFields)
        }
    }

    @Test fun sameShapeAndMetadataStillCannotChooseBetweenDifferentLines() {
        val source = """[{"id":171,"content":"first"},{"id":171,"content":"second"}]"""
        val target = """[{"id":171,"content":"第一句"},{"id":171,"content":"第二句"}]"""
        assertEquals(0, TranslationJsonPairer.pair("en/StoryData/EN_S737B.json", source, target).matchedFields)
    }

    @Test fun lastAbilityUsesItsUniqueRemainingIdentityAfterOtherEffectsMatch() {
        for (language in listOf("jp", "en", "kr")) {
            val path = "$language/${language.uppercase()}_EGOgift_MirrorDungeon.json"
            val source = """{"dataList":[{"id":9438,"simpleDesc":[{"abilityID":9438,"simpleDesc":"Restore 3 SP"},{"abilityID":9438,"simpleDesc":"Give a random ally 2 attack level next turn"}]}]}"""
            val target = """{"dataList":[{"id":9438,"simpleDesc":[{"abilityID":9438,"simpleDesc":"下回合使1名友方获得2层攻击等级提升"},{"abilityID":9438,"simpleDesc":"恢复3点理智值"}]}]}"""
            val result = TranslationJsonPairer.pair(path, source, target)
            assertEquals(listOf("恢复3点理智值", "下回合使1名友方获得2层攻击等级提升"), result.records.map { it.translation })
            val merged = TranslationJsonPairer.mergeWithCoverage(path, source, target)
            val effects = (merged.document as JSONObject).getJSONArray("dataList").getJSONObject(0).getJSONArray("simpleDesc")
            assertEquals(9438, effects.getJSONObject(1).getInt("abilityID"))
            assertTrue(merged.contains(effects.getJSONObject(0), "simpleDesc"))
            assertTrue(merged.contains(effects.getJSONObject(1), "simpleDesc"))
        }
    }

    @Test fun remainingAbilityNeedsSameCountMetadataAndDisplayStructure() {
        val source = """{"dataList":[{"id":9,"simpleDesc":[{"abilityID":9,"simpleDesc":"3 SP"},{"abilityID":9,"simpleDesc":"2 attack"}]}]}"""
        for (target in listOf(
            """{"dataList":[{"id":9,"simpleDesc":[{"abilityID":9,"simpleDesc":"恢复3点理智"}]}]}""",
            """{"dataList":[{"id":9,"simpleDesc":[{"abilityID":9,"simpleDesc":"恢复3点理智"},{"abilityID":9,"simpleDesc":"1名友方提升2层攻击","condition":2}]}]}""",
            """{"dataList":[{"id":9,"simpleDesc":[{"abilityID":9,"simpleDesc":"恢复3点理智"},{"abilityID":9,"desc":"1名友方提升2层攻击"}]}]}"""
        )) assertEquals(listOf("恢复3点理智"), TranslationJsonPairer.pair("jp/JP_EGOgift_Mirror.json", source, target).records.map { it.translation })
    }

    @Test fun releasedModelCatalogueSeesAllRowsAndRejectsOnlyConflictingFields() {
        val path = "jp/StoryData/JP_S737B.json"
        val target = """{"dataList":[{"id":171,"teller":"但丁","content":"第一句"},{"id":171,"teller":"但丁","content":"第二句"}]}"""
        assertEquals(4, TranslationJsonPairer.releasedModelRecords(path, target).size)
        val models = ReleasedModelFields().apply { add(path, target) }.fields()
        assertEquals("但丁", models["@story:S737B/dialog:171\u001fteller"])
        assertFalse(models.containsKey("@story:S737B/dialog:171\u001fcontent"))
        val gifts = """{"dataList":[{"id":9,"simpleDesc":[{"abilityID":9,"simpleDesc":"第一种效果"},{"abilityID":9,"simpleDesc":"第二种效果"}]}]}"""
        assertEquals(2, TranslationJsonPairer.releasedModelRecords("jp/JP_EGOgift.json", gifts).size)
        assertTrue(ReleasedModelFields().apply { add("jp/JP_EGOgift.json", gifts) }.fields().isEmpty())
    }
}
