package com.example.limbuszhcn.translation

import com.example.limbuszhcn.userMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiResponseParserTest {
    private fun envelope(body: String) = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", body))))
        .put("usage", JSONObject().put("prompt_tokens", 20).put("completion_tokens", 10)).toString()

    private fun body(id: String = "a", text: Any = "连接中") = JSONObject().put("translations",
        JSONArray().put(JSONObject().put("id", id).put("text", text))).toString()

    private fun translate(body: String, source: String = "接続中") =
        AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _, _, _ -> envelope(body) })
            .translate(listOf(AiText("a", source)))

    @Test fun bareAndSingleFencedJsonReturnTheSameValidatedTranslation() {
        val json = body()
        for (text in listOf(json, " \uFEFF$json\n", "```json\n$json\n```", "```\n$json\n```",
            " \n```JSON\r\n$json\r\n```\n ")) {
            val result = translate(text)
            assertEquals("连接中", result.translations["a"])
            assertEquals(20L, result.inputTokens)
            assertEquals(10L, result.outputTokens)
        }
    }

    @Test fun proseTruncationMultipleObjectsAndUnexpectedRootAreNotGuessed() {
        val json = body()
        for (text in listOf("解释\n$json", "$json\n解释", "$json$json", "[]", "null",
            "```json\n$json", "```javascript\n$json\n```", "```json\n$json\n```\n$json")) {
            val error = assertThrows(AiResponseFormatException::class.java) { translate(text) }
            assertEquals("翻译服务返回的格式无法识别，未保存本批译文，请重试。", error.userMessage())
            assertNull(error.cause)
            assertFalse(error.toString().contains(text))
        }
    }

    @Test fun wrappedResponseStillRejectsChangedIdentityAndGameData() {
        assertThrows(IllegalArgumentException::class.java) { translate("```json\n${body("wrong")}\n```") }
        assertThrows(IllegalArgumentException::class.java) { translate("```json\n${body(text = "增加3点")}\n```", "増加2") }
        val correct = "```json\n${body(text = "增加⟦P0⟧点")}\n```"
        assertEquals("增加2点", translate(correct, "増加2").translations["a"])
    }

    @Test fun wrongEnvelopeAndFieldTypesDoNotLeakProviderContentInErrors() {
        for (response in listOf("private-provider-body", "{\"choices\":\"private-provider-body\"}",
            envelope("{\"translations\":\"private-provider-body\"}"), envelope(body(text = 12)))) {
            val client = AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _, _, _ -> response })
            val error = assertThrows(AiResponseFormatException::class.java) { client.translate(listOf(AiText("a", "接続中"))) }
            assertFalse(error.toString().contains("private-provider-body"))
            assertNull(error.cause)
        }
    }
}
