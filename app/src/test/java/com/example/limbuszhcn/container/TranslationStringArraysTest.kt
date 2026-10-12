package com.example.limbuszhcn.container

import org.junit.Assert.*
import org.junit.Test

class TranslationStringArraysTest {
    @Test fun eventArraysFollowTheIdentifiedParentWhenRowsAreReordered() {
        val paired = TranslationJsonPairer.pair("JP_AbEvents.json",
            """{"dataList":[{"id":1,"successDesc":["成功した","次へ"]},{"id":2,"failureDesc":["失敗した"]}]}""",
            """{"dataList":[{"id":2,"failureDesc":["失败了"]},{"id":1,"successDesc":["成功了","继续"]}]}""")
        assertEquals(3, paired.sourceFields)
        assertEquals(3, paired.matchedFields)
        assertTrue(paired.sufficientlyPaired)
        assertEquals(listOf("成功了", "继续", "失败了"), paired.records.map { it.translation })
        assertEquals(listOf("1", "1", "2"), paired.records.map { it.stableId })
    }

    @Test fun differentLengthsAndMixedElementTypesCannotGuessAnOutcome() {
        val source = """{"dataList":[{"id":1,"result":["一","二"]}]}"""
        for (target in listOf(
            """{"dataList":[{"id":1,"result":["结果"]}]}""",
            """{"dataList":[{"id":1,"result":["结果",{"text":"对象"}]}]}""",
            """{"dataList":[{"id":2,"result":["结果一","结果二"]}]}""")) {
            val paired = TranslationJsonPairer.pair("JP_ActionEvents.json", source, target)
            assertEquals(2, paired.sourceFields)
            assertEquals(0, paired.matchedFields)
            assertFalse(paired.sufficientlyPaired)
        }
    }

    @Test fun metadataArraysNeverBecomeDisplayText() {
        val paired = TranslationJsonPairer.pair("JP_Event.json",
            """{"dataList":[{"id":1,"title":"結果","voicefile":["音声"]}]}""",
            """{"dataList":[{"id":1,"title":"结果","voicefile":["别改语音"]}]}""")
        assertEquals(1, paired.sourceFields)
        assertEquals(listOf("title"), paired.records.map { it.field })
    }

    @Test fun malformedSourceArrayCannotFallThroughToObjectPairing() {
        val paired = TranslationJsonPairer.pair("JP_Event.json",
            """{"dataList":[{"id":1,"result":["成功",{"title":"日本語"}]}]}""",
            """{"dataList":[{"id":1,"result":["通过",{"title":"中文"}]}]}""")
        assertEquals(2, paired.sourceFields)
        assertEquals(0, paired.matchedFields)
        assertFalse(paired.sufficientlyPaired)
    }

    @Test fun arrayTranslationsEnterTheSameContextAndFontPipeline() {
        val paired = TranslationJsonPairer.pair("JP_Event.json",
            """{"dataList":[{"id":7,"result":["罪人[{0}]は成功した。","特性"]}]}""",
            """{"dataList":[{"id":7,"result":["罪人[{0}]成功了。","特性"]}]}""")
        val compiled = TranslationIndexCompiler().apply { paired.records.forEach(::add) }.compile("v", "sha")
        assertEquals("罪人[{0}]成功了。", compiled.uniqueEntries["罪人[{0}]は成功した。"])
        assertEquals("特性", compiled.uniqueEntries["特性"])
    }
}
