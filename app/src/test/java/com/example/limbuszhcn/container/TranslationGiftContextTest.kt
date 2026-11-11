package com.example.limbuszhcn.container

import org.junit.Assert.*
import org.junit.Test

class TranslationGiftContextTest {
    private fun pair(source: String, target: String) = TranslationJsonPairer.pair("jp/JP_EGOgift_Mirror.json", source, target)

    @Test fun reorderedAbilitiesKeepTheirOwnIdentityAndDisplayText() {
        val result = pair(
            """{"dataList":[{"id":98,"simpleDesc":[{"abilityID":981,"simpleDesc":"回復"},{"abilityID":982,"simpleDesc":"攻撃"}]}]}""",
            """{"dataList":[{"id":98,"simpleDesc":[{"abilityID":982,"simpleDesc":"攻击"},{"abilityID":981,"simpleDesc":"恢复"}]}]}""")
        assertEquals(listOf("恢复", "攻击"), result.records.map { it.translation })
        assertEquals(listOf("@ego-gift:98/ability:981", "@ego-gift:98/ability:982"), result.records.map { it.stableId })
        assertEquals(2, result.sourceFields)
        assertTrue(result.sufficientlyPaired)
    }

    @Test fun identicalRepeatedRecordsAreSafeButConflictingDuplicatesAreNotGuessed() {
        val source = """{"dataList":[{"id":98,"simpleDesc":[{"abilityID":1,"simpleDesc":"回復"},{"abilityID":1,"simpleDesc":"回復"}]}]}"""
        val same = """{"dataList":[{"id":98,"simpleDesc":[{"abilityID":1,"simpleDesc":"恢复"},{"abilityID":1,"simpleDesc":"恢复"}]}]}"""
        assertEquals(2, pair(source, same).matchedFields)
        val ambiguous = """{"dataList":[{"id":98,"simpleDesc":[{"abilityID":1,"simpleDesc":"恢复"},{"abilityID":1,"simpleDesc":"治疗"}]}]}"""
        assertEquals(0, pair(source, ambiguous).matchedFields)
    }

    @Test fun changedIdsMetadataAndMissingIdentityNeverFallBackToPosition() {
        val source = """{"dataList":[{"id":98,"simpleDesc":[{"abilityID":1,"simpleDesc":"回復","condition":2}]}]}"""
        for (target in listOf(
            """{"dataList":[{"id":98,"simpleDesc":[{"abilityID":2,"simpleDesc":"恢复","condition":2}]}]}""",
            """{"dataList":[{"id":98,"simpleDesc":[{"abilityID":1,"simpleDesc":"恢复","condition":3}]}]}""",
            """{"dataList":[{"id":98,"simpleDesc":[{"simpleDesc":"恢复","condition":2}]}]}"""
        )) assertEquals(0, pair(source, target).matchedFields)
    }

    @Test fun distinctRepeatedEffectsUseTheirNumbersAndStatusTokensEvenWhenReordered() {
        // Released Mirror Dungeon data repeats the SAME abilityID for these effects.
        val source = """{"dataList":[{"id":9089,"simpleDesc":[{"abilityID":9089,"simpleDesc":"[Laceration]威力2を付与"},{"abilityID":9089,"simpleDesc":"[Laceration]威力4を付与"}]}]}"""
        val target = """{"dataList":[{"id":9089,"simpleDesc":[{"abilityID":9089,"simpleDesc":"增加4级[Laceration]强度"},{"abilityID":9089,"simpleDesc":"增加2级[Laceration]强度"}]}]}"""
        val result = pair(source, target)
        assertEquals(listOf("增加2级[Laceration]强度", "增加4级[Laceration]强度"), result.records.map { it.translation })
        assertEquals(2, result.matchedFields)
        val merged = org.json.JSONObject(requireNotNull(TranslationJsonPairer.merge("jp/JP_EGOgift_Mirror.json", source, target)))
            .getJSONArray("dataList").getJSONObject(0).getJSONArray("simpleDesc")
        assertEquals("增加2级[Laceration]强度", merged.getJSONObject(0).getString("simpleDesc"))
        assertEquals(9089, merged.getJSONObject(1).getInt("abilityID"))
    }

    @Test fun duplicateEffectsWithSameNumbersRemainAmbiguousAndMetadataStillMatters() {
        val source = """{"dataList":[{"id":9,"simpleDesc":[{"abilityID":1,"simpleDesc":"体力3回復","condition":1},{"abilityID":1,"simpleDesc":"精神力3回復","condition":1}]}]}"""
        val target = """{"dataList":[{"id":9,"simpleDesc":[{"abilityID":1,"simpleDesc":"恢复3点体力","condition":1},{"abilityID":1,"simpleDesc":"恢复3点理智","condition":1}]}]}"""
        assertEquals(0, pair(source, target).matchedFields)
        assertNull(TranslationJsonPairer.merge("jp/JP_EGOgift_Mirror.json", source, target))
        val distinctSource = source.replace("精神力3", "精神力4")
        val changedTarget = target.replace("3点理智", "4点理智").replace("\"condition\":1", "\"condition\":2")
        assertEquals(0, pair(distinctSource, changedTarget).matchedFields)
    }

    @Test fun sameAbilityIdInDifferentGiftsRetainsTheSourcesChosenWording() {
        val records = pair(
            """{"dataList":[{"id":1,"simpleDesc":[{"abilityID":7,"simpleDesc":"回復"}]},{"id":2,"simpleDesc":[{"abilityID":7,"simpleDesc":"回復"}]}]}""",
            """{"dataList":[{"id":1,"simpleDesc":[{"abilityID":7,"simpleDesc":"恢复体力"}]},{"id":2,"simpleDesc":[{"abilityID":7,"simpleDesc":"治疗"}]}]}"""
        ).records
        val index = TranslationIndexCompiler().apply { records.forEach(::add) }.compile("v", "sha")
        for (record in records) {
            val key = requireNotNull(TranslationIndexCompiler.contextKey(record.stableId, record.field, record.source))
            assertEquals(record.translation, index.contextualEntries[key] ?: index.uniqueEntries[record.source])
        }
        assertTrue(records.all { it.field == "simpleDesc" })
    }
}
