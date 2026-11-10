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
