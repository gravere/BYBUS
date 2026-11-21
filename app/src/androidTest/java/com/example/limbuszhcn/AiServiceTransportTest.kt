package com.example.limbuszhcn

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.limbuszhcn.translation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Android HttpURLConnection and org.json, with a private in-process HTTP fixture only. */
@RunWith(AndroidJUnit4::class)
class AiServiceTransportTest {
    private fun response(headers: String, body: String, hold: Boolean = false, verify: (String) -> Unit) {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val complete = executor.submit {
            server.accept().use { socket ->
                calls.incrementAndGet(); socket.soTimeout = 10_000
                val input = socket.getInputStream().buffered()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) {
                    val byte = input.read(); check(byte >= 0 && header.length < 16384)
                    header.append(byte.toChar())
                }
                val length = header.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                repeat(length) { check(input.read() >= 0) } // Content-Length counts UTF-8 bytes, not characters.
                socket.getOutputStream().write((headers + "\r\n\r\n" + body).toByteArray())
                socket.getOutputStream().flush()
                if (hold) { check(input.read() == -1) } // Client deadline must close this response.
            }
        }
        try {
            verify("http://127.0.0.1:${server.localPort}/v1/chat/completions")
            complete.get(12, TimeUnit.SECONDS); assertEquals(1, calls.get())
        } finally { server.close(); executor.shutdownNow() }
    }

    @Test fun successfulNullableErrorStreamPassesTheRealAndroidTransportAndValidator() {
        val body = ": PING\n\ndata: {\"error\":null,\"choices\":[{\"index\":0,\"delta\":{\"content\":\"{\\\"translations\\\":[{\\\"id\\\":\\\"x\\\",\\\"text\\\":\\\"连接中\\\"}]}\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        response("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close", body) { url ->
            val client = AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _, key, data -> AiTranslationClient.post(url, key, data) })
            assertEquals("连接中", client.translate(listOf(AiText("x", "接続中"))).translations["x"])
        }
    }

    @Test fun streamedErrorsAndHttpErrorsHaveTheSameSafeActionableResult() {
        val error = "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"private-provider-value\"}}"
        for (stream in listOf(true, false)) {
            val headers = if (stream) "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream" else "HTTP/1.1 429 Limited\r\nContent-Type: application/json"
            response(headers + "\r\nConnection: close", if (stream) "data: $error\n\n" else error) { url ->
                val failure = assertThrows(AiServiceException::class.java) { AiTranslationClient.post(url, "fixture", "{}") }
                assertEquals(AiServiceReason.Quota, failure.reason)
                assertEquals(if (stream) 200 else 429, failure.httpStatus)
                assertFalse(failure.toString().contains("private-provider-value")); assertNull(failure.cause)
            }
        }
    }

    @Test fun aStalledErrorBodyDoesNotKeepTheTaskWaitingForTheNormalTranslationTimeout() {
        response("HTTP/1.1 503 Unavailable\r\nContent-Type: application/json\r\nContent-Length: 2048\r\nConnection: close", "{", hold = true) { url ->
            val started = android.os.SystemClock.elapsedRealtime()
            val failure = assertThrows(AiHttpException::class.java) { AiTranslationClient.post(url, "fixture", "{}") }
            assertEquals(503, failure.status)
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            assertTrue("error read took $elapsed ms", elapsed < 8000)
        }
    }

    @Test fun waitingAndHeartbeatOnlyStreamsHaveABoundedDeadlineOnAndroid() {
        for (body in listOf("", ": heartbeat\n\n")) {
            response("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close", body, hold = true) { url ->
                val started = android.os.SystemClock.elapsedRealtime()
                val error = assertThrows(AiRequestTimeout::class.java) {
                    AiTranslationClient.post(url, "fixture", "{}", idleMillis = 500, totalMillis = 3000,
                        clock = android.os.SystemClock::elapsedRealtime)
                }
                assertFalse(error.total)
                assertTrue(android.os.SystemClock.elapsedRealtime() - started < 2500)
            }
        }
    }

    @Test fun explicitPauseStillWinsOverTheRequestDeadline() {
        response("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close", ": heartbeat\n\n", hold = true) { url ->
            val control = AiRequestControl()
            val error = assertThrows(AiRequestCancelled::class.java) {
                AiTranslationClient.post(url, "fixture", "{}", control, progress = {
                    if (it.heartbeats > 0) control.stop()
                }, idleMillis = 2000)
            }
            assertTrue(error.sent)
        }
    }

    @Test fun androidFileChangeProofIsDurableAndInvalidatesChangedFrozenInputs() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val root = java.nio.file.Files.createTempDirectory(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.cacheDir.toPath(), "ai-proof-")
        try {
            val file = root.resolve("frozen.json")
            java.nio.file.Files.write(file, "before".toByteArray())
            assertNotNull(aiPrivateFileStamp(file))
            val hash = AiTranslationJob.digest("before")
            AiSnapshotVerification(root).apply { verify("frozen.json", file, 6, hash); save() }
            val marker = root.resolve("verified-files.json")
            val originalMarker = java.nio.file.Files.readAllBytes(marker)
            AiSnapshotVerification(root).apply { verify("frozen.json", file, 6, hash); save() }
            assertArrayEquals(originalMarker, java.nio.file.Files.readAllBytes(marker))
            val mtime = java.nio.file.Files.getLastModifiedTime(file)
            Thread.sleep(20)
            java.nio.file.Files.write(file, "after!".toByteArray())
            java.nio.file.Files.setLastModifiedTime(file, mtime)
            assertThrows(IllegalArgumentException::class.java) {
                AiSnapshotVerification(root).verify("frozen.json", file, 6, hash)
            }
        } finally { com.example.limbuszhcn.storage.AtomicFiles.deleteOwnedTree(root.parent, root) }
    }
}
