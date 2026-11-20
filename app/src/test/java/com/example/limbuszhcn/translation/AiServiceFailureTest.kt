package com.example.limbuszhcn.translation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class AiServiceFailureTest {
    private fun stream(data: String, prefix: String = "") = readAiHttpResponse(
        ByteArrayInputStream((prefix + "data: $data\n\n").toByteArray()), "text/event-stream")
    private fun error(code: Any, message: String = "private-provider-message") = JSONObject().put("error",
        JSONObject().put("code", code).put("message", message))

    @Test fun nullableErrorFieldsCannotDiscardAValidPaidAnswer() {
        for (empty in listOf(JSONObject.NULL, false, "")) {
            val answer = JSONObject().put("translations", JSONArray().put(JSONObject().put("id", "x").put("text", "连接中")))
            val chunk = JSONObject().put("error", empty).put("choices", JSONArray().put(JSONObject().put("index", 0)
                .put("delta", JSONObject().put("content", answer.toString())).put("finish_reason", "stop")))
            var calls = 0
            val client = AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _, _, _ ->
                calls++
                readAiHttpResponse(ByteArrayInputStream(("data: $chunk\n\ndata: [DONE]\n\n").toByteArray()), "text/event-stream")
            })
            assertEquals("连接中", client.translate(listOf(AiText("x", "接続中"))).translations["x"])
            assertEquals(1, calls)
        }
    }

    @Test fun streamAndOrdinaryJsonErrorsHaveSafeActionableCategories() {
        val cases = mapOf("insufficient_quota" to AiServiceReason.Quota, "invalid_api_key" to AiServiceReason.Authorization,
            "rate_limit_exceeded" to AiServiceReason.RateLimit, "model_not_found" to AiServiceReason.Model,
            "context_length_exceeded" to AiServiceReason.Context, "unsupported_parameter" to AiServiceReason.Parameters,
            "content_policy_violation" to AiServiceReason.Content, "deadline_exceeded" to AiServiceReason.Timeout,
            "overloaded_error" to AiServiceReason.Busy, "unknown-private-code" to AiServiceReason.Unknown)
        for ((code, reason) in cases) {
            val data = error(code).toString()
            for (streaming in listOf(false, true)) {
                var requests = 0; var unknown = 0
                val client = AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _, _, _ ->
                    requests++
                    if (streaming) stream(data) else data
                })
                val failure = assertThrows(AiServiceException::class.java) {
                    client.translate(listOf(AiText("x", "接続中")), onUsage = { if (!it.known) unknown++ })
                }
                assertEquals(reason, failure.reason)
                assertEquals(200, failure.httpStatus)
                assertEquals(1, requests); assertEquals(1, unknown)
                assertFalse(failure.toString().contains("private")); assertNull(failure.cause)
            }
        }
    }

    @Test fun http200ErrorEventsAndNestedGatewayErrorsPreserveTheirActualStatus() {
        val inner = error(503, "service unavailable: private-account").toString()
        val failure = assertThrows(AiServiceException::class.java) {
            stream(JSONObject().put("error", JSONObject().put("message", inner)).toString(), ": keepalive\n\n")
        }
        assertEquals(AiServiceReason.Busy, failure.reason); assertEquals(503, failure.providerStatus)
        assertEquals(200, failure.httpStatus); assertFalse(failure.message!!.contains("private-account"))
        val named = assertThrows(AiServiceException::class.java) {
            stream(JSONObject().put("code", 429).put("message", "too many requests").toString(), "event: error\n")
        }
        assertEquals(AiServiceReason.RateLimit, named.reason); assertEquals(429, named.providerStatus)
    }

    @Test fun badErrorBodiesKeepTheHttpFailureAndNeverLeakOrRetry() {
        val variants = listOf("<html>private-provider-message</html>", "{", "x".repeat(20000), "")
        for (body in variants) {
            val failure = readAiHttpFailure(524, ByteArrayInputStream(body.toByteArray()))
            assertEquals(524, failure.status); assertEquals(AiServiceReason.Timeout, failure.reason)
            assertNull(failure.cause); assertFalse(failure.message!!.contains("private"))
        }
        val failure = readAiHttpFailure(400, ByteArrayInputStream(error("unsupported_parameter").toString().toByteArray()))
        assertEquals(AiServiceReason.Parameters, failure.reason)
        var consumed = 0
        val endless = object : InputStream() { override fun read(): Int { consumed++; return 'x'.code } }
        assertEquals(AiServiceReason.Busy, readAiHttpFailure(503, endless).reason)
        assertEquals(16 * 1024 + 1, consumed)
    }

    @Test fun heartbeatOnlyErrorNeverLooksLikeReceivedOrCompletedTranslation() {
        val states = mutableListOf<AiResponseProgress>()
        val raw = ": PING\n\n: PING\n\ndata: ${error(429)}\n\n"
        assertThrows(AiServiceException::class.java) {
            readAiHttpResponse(ByteArrayInputStream(raw.toByteArray()), "text/event-stream") { states += it }
        }
        assertEquals(2, states.last().heartbeats)
        assertTrue(states.all { it.events == 0 && it.stage == AiResponseStage.Waiting })
    }
}
