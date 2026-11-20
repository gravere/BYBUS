package com.example.limbuszhcn.translation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AiStreamingResponseTest {
    private fun chunk(content: String? = null, finish: String? = null, thinking: Boolean = false): String =
        "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
            .put("delta", JSONObject().apply { content?.let { put("content", it) }; if (thinking) put("reasoning_content", "private") })
            .put("finish_reason", finish ?: JSONObject.NULL))) + "\n\n"
    private val translated = """{"translations":[{"id":"x","text":"连接中"}]}"""
    private val complete get() = ": keepalive\n\n" + chunk(thinking = true) + chunk(translated.take(16)) + chunk(translated.drop(16)) + chunk(finish = "stop") +
        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":10}}\n\ndata: [DONE]\n\n"
    private fun read(raw: String, type: String? = "text/event-stream", progress: (AiResponseProgress) -> Unit = {}) =
        readAiHttpResponse(ByteArrayInputStream(raw.toByteArray()), type, progress)

    @Test fun heartbeatsAndEmptyRoleChunksDoNotPretendTheTranslationHasArrived() {
        val states = mutableListOf<AiResponseProgress>()
        read(": ping\n\n" + chunk("") + ": keep-alive\n\n" + chunk(translated) + chunk(finish = "stop") + "data: [DONE]\n\n") { states += it }
        assertTrue(states.filter { it.events <= 1 }.all { it.stage == AiResponseStage.Waiting })
        assertTrue(states.any { it.events >= 2 && it.stage == AiResponseStage.Receiving })
        assertTrue(states.any { it.bytes > 0 && it.stage == AiResponseStage.Waiting })
    }

    @Test fun streamedChunksPassExistingValidationAndUsageWithEveryLineEnding() {
        for (ending in listOf("\n", "\r\n", "\r")) {
            val stages = mutableListOf<AiResponseStage>()
            var usage: AiUsage? = null
            val client = AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _, _, request ->
                assertTrue(JSONObject(request).getBoolean("stream"))
                assertTrue(JSONObject(request).getJSONObject("stream_options").getBoolean("include_usage"))
                read(complete.replace("\n", ending)) { stages += it.stage }
            })
            val result = client.translate(listOf(AiText("x", "接続中")), onUsage = { usage = it })
            assertEquals("连接中", result.translations["x"])
            assertEquals(20L, usage!!.input); assertEquals(10L, usage!!.output); assertTrue(usage!!.known)
            assertTrue(stages.containsAll(listOf(AiResponseStage.Thinking, AiResponseStage.Receiving)))
        }
    }
    @Test fun truncatedValidJsonAndDoneWithoutFinishNeverBecomeTranslations() {
        for (raw in listOf(chunk(translated), chunk(translated) + chunk(finish = "stop"), chunk(translated) + "data: [DONE]\n\n")) {
            var calls = 0; var unknown = 0
            val client = AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _, _, _ -> calls++; read(raw) })
            assertThrows(AiTranslationException::class.java) { client.translate(listOf(AiText("x", "接続中")), onUsage = { if (!it.known) unknown++ }) }
            assertEquals(1, calls); assertEquals(1, unknown)
        }
    }
    @Test fun normalJsonFallbackAndOptOutUseOnlyOneRequest() {
        val normal = read(complete)
        var calls = 0
        val client = AiTranslationClient(AiConfig(stream = false), "fixture", AiHttpTransport { _, _, body ->
            calls++; assertFalse(JSONObject(body).has("stream")); read(normal, "application/json")
        })
        assertEquals("连接中", client.translate(listOf(AiText("x", "接続中"))).translations["x"])
        assertEquals(1, calls)
        assertEquals(translated, JSONObject(read(complete, null)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
    }
    @Test fun providerErrorsAndOversizedEventsNeverExposeProviderBody() {
        for (raw in listOf("data: {\"error\":{\"message\":\"secret-provider-body\"}}\n\n", "data: " + "x".repeat(270000) + "\n\n")) {
            val error = assertThrows(AiTranslationException::class.java) { read(raw) }
            assertFalse(error.toString().contains("secret-provider-body"))
        }
        assertThrows(AiResponseFormatException::class.java) {
            readAiHttpResponse(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)), "application/json")
        }
    }
    @Test fun gatewayTimeoutIsDistinctFromBadConfigurationAndNeverRetries() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val response = executor.submit {
            server.accept().use { socket ->
                calls.incrementAndGet(); socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader()
                var length = 0
                while (true) {
                    val line = input.readLine() ?: error("Request ended early")
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                repeat(length) { check(input.read() >= 0) }
                socket.getOutputStream().write("HTTP/1.1 524 Timeout\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
        }
        try {
            val error = assertThrows(AiHttpException::class.java) {
                AiTranslationClient.post("http://127.0.0.1:${server.localPort}/chat/completions", "fixture", "{}")
            }
            response.get(5, TimeUnit.SECONDS)
            assertEquals(524, error.status); assertTrue(error.message!!.contains("服务响应超时"))
            assertFalse(error.message!!.contains("地址")); assertEquals(1, calls.get())
        } finally { server.close(); executor.shutdownNow() }
    }
    @Test fun streamTransportDoesNotAlterTaskIdentityOrInvalidateGoodTranslations() {
        assertEquals(AiTaskStore.identityFor("base", AiConfig()), AiTaskStore.identityFor("base", AiConfig(stream = false)))
        assertNotEquals(AiTestResult.fingerprint(AiConfig(), "fixture"), AiTestResult.fingerprint(AiConfig(stream = false), "fixture"))
    }
}
