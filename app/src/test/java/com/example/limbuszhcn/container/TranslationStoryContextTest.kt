package com.example.limbuszhcn.container

import org.junit.Assert.*
import org.junit.Test

class TranslationStoryContextTest {
    @Test fun missingLineIdsUseOnlyUnambiguousTextInTheSameScenario() {
        val compiler = TranslationIndexCompiler()
        val result = TranslationJsonPairer.pair("jp/StoryData/JP_A.json",
            """{"dataList":[{"content":"はい。"},{"content":"はい。"},{"content":"来た。"}]}""",
            """{"dataList":[{"content":"是。"},{"content":"好的。"},{"content":"来了。"}]}""")
        result.records.forEach(compiler::add)
        compiler.add(TranslationIndexRecord("jp/StoryData/JP_B.json", "row", "content", "来た。", "到了。", "@story:B"))
        val index = compiler.compile("v", "sha")
        assertTrue(result.records.all { it.stableId == "@story:A" })
        assertNull(index.contextualEntries[TranslationIndexCompiler.contextKey("@story:A", "content", "はい。")])
        assertEquals("来了。", index.contextualEntries[TranslationIndexCompiler.contextKey("@story:A", "content", "来た。")])
        assertEquals("到了。", index.contextualEntries[TranslationIndexCompiler.contextKey("@story:B", "content", "来た。")])
    }

    @Test fun identicalLineIdsAndOriginalsRemainSpecificToTheScenario() {
        val compiler = TranslationIndexCompiler()
        val records = listOf("P10612" to "驯鹿队", "P10914" to "驯鹿部队").flatMap { (file, title) ->
            TranslationJsonPairer.pair("jp/StoryData/JP_$file.json",
                """{"dataList":[{"id":8,"title":"トナカイチーム","model":"model_1"}]}""",
                """{"dataList":[{"id":8,"title":"$title","model":"model_1"}]}""").records
        }
        records.forEach(compiler::add)
        val index = compiler.compile("v", "sha")
        for (row in records) assertEquals(row.translation, index.contextualEntries[
            TranslationIndexCompiler.contextKey(row.stableId, row.field, row.source)])
        assertNull(index.contextualEntries[TranslationIndexCompiler.contextKey("8", "title", "トナカイチーム")])
        assertEquals(setOf("@story:P10612/dialog:8", "@story:P10914/dialog:8"), records.map { it.stableId }.toSet())
    }

    @Test fun unambiguousOldLineContextRemainsAvailableWithoutAScenarioHook() {
        val compiler = TranslationIndexCompiler()
        for ((file, id, title) in listOf(Triple("A", 8, "队长"), Triple("B", 8, "队长"), Triple("C", 9, "首领"))) {
            TranslationJsonPairer.pair("jp/StoryData/JP_$file.json",
                """{"dataList":[{"id":$id,"title":"リーダー"}]}""",
                """{"dataList":[{"id":$id,"title":"$title"}]}""").records.forEach(compiler::add)
        }
        val index = compiler.compile("v", "sha")
        assertEquals("队长", index.contextualEntries[TranslationIndexCompiler.contextKey("8", "title", "リーダー")])
        assertEquals("首领", index.contextualEntries[TranslationIndexCompiler.contextKey("9", "title", "リーダー")])
    }

    @Test fun anotherTableCannotInheritTheStoryLineFallback() {
        val compiler = TranslationIndexCompiler()
        TranslationJsonPairer.pair("jp/StoryData/JP_A.json",
            """{"dataList":[{"id":8,"content":"いい。"}]}""",
            """{"dataList":[{"id":8,"content":"好的。"}]}""").records.forEach(compiler::add)
        compiler.add(TranslationIndexRecord("jp/JP_Other.json", "row", "content", "いい。", "不错。", "8"))
        val index = compiler.compile("v", "sha")
        assertEquals("好的。", index.contextualEntries[TranslationIndexCompiler.contextKey("@story:A/dialog:8", "content", "いい。")])
        assertNull(index.contextualEntries[TranslationIndexCompiler.contextKey("8", "content", "いい。")])
    }

    @Test fun scenarioFallbackCannotOverrideTwoDifferentTranslationsInsideTheScenario() {
        val compiler = TranslationIndexCompiler()
        TranslationJsonPairer.pair("jp/StoryData/JP_A.json",
            """{"dataList":[{"id":1,"content":"はい。"},{"id":2,"content":"はい。"}]}""",
            """{"dataList":[{"id":1,"content":"是。"},{"id":2,"content":"好的。"}]}""").records.forEach(compiler::add)
        val index = compiler.compile("v", "sha")
        assertNull(index.contextualEntries[TranslationIndexCompiler.contextKey("@story:A", "content", "はい。")])
        assertEquals("是。", index.contextualEntries[TranslationIndexCompiler.contextKey("@story:A/dialog:1", "content", "はい。")])
        assertEquals("好的。", index.contextualEntries[TranslationIndexCompiler.contextKey("@story:A/dialog:2", "content", "はい。")])
    }
}
