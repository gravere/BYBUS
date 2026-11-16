package com.example.limbuszhcn.translation

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject

/** Opt-in only; one bounded request against the same transport shipped in the app. */
class OfficialApiSmokeTest {
    @Test fun officialOpenAiTranslatesAndPreservesGameFormatting() {
        val key = System.getenv("BYBUS_API_SMOKE_KEY").orEmpty()
        assumeTrue("Live API smoke test requires an explicitly provided key", key.isNotBlank())
        val reportPath = System.getenv("BYBUS_API_SMOKE_REPORT")
        val result = AiTranslationClient(AiConfig(), key, AiHttpTransport { url, credential, request ->
            val response = AiTranslationClient.post(url, credential, request)
            // Only fixed synthetic fixtures are used here; never persist authentication or headers.
            reportPath?.let { path ->
                val parsed = JSONObject(response)
                val report = JSONObject().put("model", parsed.optString("model")).put("requests", 1)
                    .put("usage", parsed.optJSONObject("usage"))
                    .put("fixtureResponse", parsed.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content"))
                    .put("formatValidated", false)
                java.nio.file.Files.write(java.nio.file.Paths.get(path), report.toString(2).toByteArray(Charsets.UTF_8))
            }
            response
        }).translate(listOf(
            AiText("status", "接続中…"),
            AiText("skill", "<b>火傷</b>を{0}付与する。\n次のターン、威力が2増加する。")
        ))
        assertTrue(result.translations.getValue("status").any { it.code in 0x3400..0x9fff })
        val skill = result.translations.getValue("skill")
        assertTrue(skill.contains("<b>")); assertTrue(skill.contains("{0}")); assertTrue(skill.contains("2")); assertTrue(skill.contains('\n'))
        val report = JSONObject().put("model", "gpt-4.1-mini").put("requests", 1)
            .put("inputTokens", result.inputTokens).put("outputTokens", result.outputTokens)
            .put("translations", JSONObject(result.translations)).put("formatValidated", true)
        reportPath?.let { path ->
            java.nio.file.Files.write(java.nio.file.Paths.get(path), report.toString(2).toByteArray(Charsets.UTF_8))
        }
    }
}
