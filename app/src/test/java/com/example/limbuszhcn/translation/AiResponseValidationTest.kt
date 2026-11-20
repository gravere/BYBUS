package com.example.limbuszhcn.translation

import com.example.limbuszhcn.userMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiResponseValidationTest {
    private fun client(rows: JSONArray, finish: String = "stop", refusal: Any? = null) =
        AiTranslationClient(AiConfig(), "non-secret-fixture", AiHttpTransport { _, _, _ ->
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
                .put("message", JSONObject().put("refusal", refusal).put("content",
                    JSONObject().put("translations", rows).toString()))))
                .put("usage", JSONObject().put("prompt_tokens", 40).put("completion_tokens", 20)).toString()
        })
    private fun row(id: String, text: Any?) = JSONObject().put("id", id).put("text", text ?: JSONObject.NULL)

    @Test fun modelWhitespaceIsNormalizedWithoutChangingOriginalParagraphsOrData() {
        for (word in listOf("Burn", "火傷", "화상")) {
            val source = "<b>$word</b> {0} 12%\r\n$word\\nNext\tLine"
            val protector = ProtectedTranslation(source)
            val translation = protector.masked.replace(word, "烧伤")
            for (extra in listOf("\n", "\r\n", "\t", "\\n", "\\r\\n", "\\t")) {
                val restored = protector.restore(translation.replace("烧伤", "烧伤${extra}效果"))
                val expected = source.replace(word, "烧伤 效果")
                assertEquals(expected, restored)
                assertEquals(expected, protector.restore(protector.maskTranslated(restored)))
            }
        }
    }

    @Test fun eachFailureHasASpecificReasonAndNoModelTextIsInTheError() {
        val p = ProtectedTranslation("<b>Burn</b> {0}\n2")
        val good = p.masked.replace("Burn", "烧伤")
        val cases = listOf(
            good.replace("⟦P2⟧", "") to AiValidationReason.Tokens,
            good + "⟦P2⟧" to AiValidationReason.Tokens,
            good.replace("⟦P0⟧", "⟦P99999999999999⟧") to AiValidationReason.Tokens,
            good.replace("⟦P0⟧", "⟦P1⟧").replaceFirst("⟦P1⟧烧伤⟦P1⟧", "⟦P1⟧烧伤⟦P0⟧") to AiValidationReason.Order,
            good + "999" to AiValidationReason.AddedNumber,
            good + "[Bleed]" to AiValidationReason.AddedFormat,
            good + "%s" to AiValidationReason.AddedFormat,
            good + "<b>" to AiValidationReason.AddedFormat,
            good + "⟦Pbad⟧" to AiValidationReason.AddedFormat,
            good + "\u0085" to AiValidationReason.InvalidText,
            good + "\uD800" to AiValidationReason.InvalidText,
            good + "\uDC00" to AiValidationReason.InvalidText,
            good + "\uFFFD" to AiValidationReason.InvalidText)
        for ((text, reason) in cases) {
            val error = assertThrows(AiTextValidationException::class.java) { p.restore(text) }
            assertEquals(reason, error.reason); assertNull(error.cause)
            assertFalse(error.userMessage().contains(text))
        }
        assertTrue(p.restore(good + "😀").endsWith("😀"))
    }

    @Test fun tokenNumberingStaysCompatibleWithAlreadySavedAnswers() {
        val p = ProtectedTranslation("２回 { unfinished <b>Burn</b> {0} 12%\nNext")
        val saved = "２回 { unfinished ⟦P0⟧烧伤⟦P1⟧ ⟦P2⟧ ⟦P3⟧⟦P4⟧下一行"
        val value = "２回 { unfinished <b>烧伤</b> {0} 12%\n下一行"
        assertEquals(value, p.restore(saved))
        assertEquals(saved, p.maskTranslated(value))
    }

    @Test fun validSiblingsSurviveMissingEmptyNumericAndTerminologyFailures() {
        val inputs = (1..6).map { AiText("$it", "Confirm", terms = if (it == 6)
            AiTerms(mapOf("Confirm" to "确认"), setOf("Confirm")) else AiTerms(emptyMap(), emptySet())) }
        val rows = JSONArray().put(row("5", "正常译文")).put(row("1", "确认\n完成"))
            .put(row("2", "新增2点")).put(row("3", null)).put(row("6", "其他译名"))
        var usage = AiUsage()
        val result = client(rows).translate(inputs, allowPartial = true) { usage = it }
        assertEquals(mapOf("1" to "确认 完成", "5" to "正常译文"), result.translations)
        assertEquals(listOf(2, 3, 4, 6), result.rejected.map { it.position })
        assertEquals(listOf(AiValidationReason.AddedNumber, AiValidationReason.InvalidText,
            AiValidationReason.Missing, AiValidationReason.Terminology), result.rejected.map { it.reason })
        assertEquals(result.translations.keys, result.protectedTranslations.keys)
        assertEquals(AiUsage(40, 20, 0, true), usage)
        val error = assertThrows(AiBatchValidationException::class.java) { client(rows).translate(inputs) }
        assertFalse(error.message!!.contains("已保存")) // A connection test doesn't save translations.
    }

    @Test fun unknownOrDuplicateIdsRejectTheEnvelopeEvenInPartialMode() {
        for (rows in listOf(JSONArray().put(row("a", "确认")).put(row("a", "另一个结果")),
            JSONArray().put(row("a", "确认")).put(row("wrong", "未知结果")))) {
            val error = assertThrows(AiTranslationException::class.java) {
                client(rows).translate(listOf(AiText("a", "Confirm"), AiText("b", "Wait")), allowPartial = true)
            }
            assertTrue(error.message!!.contains("编号")); assertNull(error.cause)
        }
    }

    @Test fun mergedNumbersFailBeforeCacheWriteInsteadOfLosingTheBatchLater() {
        val rows = JSONArray().put(row("a", "⟦P0⟧⟦P1⟧")).put(row("b", "正常译文"))
        val result = client(rows).translate(listOf(AiText("a", "1 and 2"), AiText("b", "Confirm")), allowPartial = true)
        assertEquals(mapOf("b" to "正常译文"), result.translations)
        assertEquals(AiValidationReason.Cache, result.rejected.single().reason)
    }

    @Test fun truncatedRefusedOrFilteredAnswersKeepUsageAndNeverBecomePartialSuccess() {
        for ((finish, refusal) in listOf("length" to null, "content_filter" to null,
            "tool_calls" to null, "stop" to "private-provider-refusal")) {
            var usage = AiUsage()
            val error = assertThrows(AiTranslationException::class.java) {
                client(JSONArray().put(row("a", "确认")), finish, refusal)
                    .translate(listOf(AiText("a", "Confirm")), allowPartial = true) { usage = it }
            }
            assertEquals(AiUsage(40, 20, 0, true), usage)
            assertFalse(error.userMessage().contains("private-provider-refusal"))
        }
        for ((finish, label) in listOf("length" to "长度上限", "content_filter" to "拦截")) {
            val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish))).toString()
            val error = assertThrows(AiTranslationException::class.java) {
                AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _, _, _ -> response })
                    .translate(listOf(AiText("a", "Confirm")), allowPartial = true)
            }
            assertTrue(error.message!!.contains(label))
        }
    }
}
