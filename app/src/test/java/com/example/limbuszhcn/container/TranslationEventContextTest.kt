package com.example.limbuszhcn.container

import org.junit.Assert.*
import org.junit.Test

class TranslationEventContextTest {
    private fun exact(records: List<TranslationIndexRecord>) {
        val index = TranslationIndexCompiler().apply { records.forEach(::add) }.compile("v", "sha")
        for (row in records) {
            val key = requireNotNull(TranslationIndexCompiler.contextKey(row.stableId, row.field, row.source))
            assertEquals(row.translation, index.contextualEntries[key] ?: index.uniqueEntries[row.source])
        }
    }

    @Test fun optionsWithTheSameOriginalTextKeepTheirEventAndPosition() {
        val result = TranslationJsonPairer.pair("jp/JP_ActionEvents_Mirror.json",
            """{"dataList":[{"id":1,"options":[{"message":"拒否する。","result":["去る。"]},{"message":"拒否する。","result":["去る。"]}]},{"id":2,"options":[{"message":"拒否する。"}]}]}""",
            """{"dataList":[{"id":1,"options":[{"message":"摇头。","result":["离开。"]},{"message":"拒绝。","result":["转身离开。"]}]},{"id":2,"options":[{"message":"不接受。"}]}]}""")
        assertEquals(5, result.records.size)
        assertEquals(setOf("@action-event:1/option:0", "@action-event:1/option:1", "@action-event:2/option:0"),
            result.records.map { it.stableId }.toSet())
        exact(result.records)
    }

    @Test fun mismatchedOptionsAreNotGuessed() {
        val source = """{"dataList":[{"id":1,"options":[{"message":"進む。","condition":2}]}]}"""
        for (target in listOf(
            """{"dataList":[{"id":1,"options":[{"message":"前进。","condition":3}]}]}""",
            """{"dataList":[{"id":1,"options":[{"message":"前进。","condition":2},{"message":"离开。"}]}]}"""
        )) assertTrue(TranslationJsonPairer.pair("jp/JP_ActionEvents.json", source, target).records.isEmpty())
    }

    @Test fun observationLevelsCanBeReorderedWithoutSharingAnotherCreaturesText() {
        val result = TranslationJsonPairer.pair("jp/JP_AbnormalityGuides.json",
            """{"dataList":[{"id":1,"storyList":[{"level":1,"story":"記録。"},{"level":3,"story":"記録。"}]},{"id":2,"storyList":[{"level":1,"story":"記録。"}]}]}""",
            """{"dataList":[{"id":1,"storyList":[{"level":3,"story":"第三次观察。"},{"level":1,"story":"第一次观察。"}]},{"id":2,"storyList":[{"level":1,"story":"另一份观察。"}]}]}""")
        assertEquals(listOf("@abnormality-guide:1/level:1", "@abnormality-guide:1/level:3", "@abnormality-guide:2/level:1"),
            result.records.map { it.stableId })
        exact(result.records)
    }

    @Test fun duplicateOrMissingObservationLevelsAreRejected() {
        val source = """{"dataList":[{"id":1,"storyList":[{"level":1,"story":"記録。"}]}]}"""
        for (target in listOf(
            """{"dataList":[{"id":1,"storyList":[{"level":1,"story":"观察一。"},{"level":1,"story":"观察二。"}]}]}""",
            """{"dataList":[{"id":1,"storyList":[{"story":"观察。"}]}]}"""
        )) assertTrue(TranslationJsonPairer.pair("jp/JP_AbnormalityGuides.json", source, target).records.isEmpty())
    }

    @Test fun rpgDialogueUsesTheParentKeyAndLineIndexAfterReordering() {
        val result = TranslationJsonPairer.pair("jp/JP_rpg-loc-dialogue-ch10.json",
            """{"dataList":[{"key":"chapter10_01","texts":[{"index":0,"text":"行こう。","speaker":"案内人"},{"index":2,"text":"行こう。"}]},{"key":"chapter10_02","texts":[{"index":0,"text":"行こう。","speaker":"案内人"}]}]}""",
            """{"dataList":[{"key":"chapter10_02","texts":[{"index":0,"text":"走吧。","speaker":"引路人"}]},{"key":"chapter10_01","texts":[{"index":2,"text":"出发吧。"},{"index":0,"text":"我们走。","speaker":"向导"}]}]}""")
        assertEquals(5, result.records.size)
        assertEquals(setOf("@rpg-dialogue:chapter10_01/text:0", "@rpg-dialogue:chapter10_01/text:2", "@rpg-dialogue:chapter10_02/text:0"),
            result.records.map { it.stableId }.toSet())
        exact(result.records)
    }

    @Test fun rpgDialogueRejectsMissingDuplicateAndChangedLineIdentities() {
        val source = """{"dataList":[{"key":"chapter10_01","texts":[{"index":0,"text":"行こう。","voicefile":"voice_1"}]}]}"""
        for (target in listOf(
            """{"dataList":[{"key":"chapter10_01","texts":[{"text":"走吧。","voicefile":"voice_1"}]}]}""",
            """{"dataList":[{"key":"chapter10_01","texts":[{"index":0,"text":"走吧。","voicefile":"voice_1"},{"index":0,"text":"出发吧。","voicefile":"voice_1"}]}]}""",
            """{"dataList":[{"key":"chapter10_01","texts":[{"index":0,"text":"走吧。","voicefile":"voice_2"}]}]}"""
        )) assertTrue(TranslationJsonPairer.pair("jp/JP_rpg-loc-dialogue-ch10.json", source, target).records.isEmpty())
    }
}
