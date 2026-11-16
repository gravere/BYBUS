package com.example.limbuszhcn.translation

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

class AiModelsClientTest {
    @Test fun anonymousDiscoveryKeepsRealIdsAndDoesNotRequireAChosenModel() {
        val client = AiModelsClient { url, key ->
            assertEquals("https://models.example/v1/models", url)
            assertEquals("", key)
            """{"data":[{"id":"vendor/model:free"},{"id":"gpt-4.1-mini"},{"id":"vendor/model:free"},{"id":""},{"id":"invalid value"},{"id":123}]}"""
        }
        assertEquals(listOf("gpt-4.1-mini", "vendor/model:free"), client.list(" https://models.example/v1/ "))
    }

    @Test fun credentialsStayWithTheExactSavedServiceAndExplicitReplacementWins() {
        var reads = 0
        val saved = { reads++; "saved-credential" }
        assertEquals("saved-credential", aiModelsKey("https://api.example/v1/", "https://api.example/v1", "", saved))
        assertEquals("", aiModelsKey("https://other.example/v1", "https://api.example/v1", "", saved))
        assertEquals("", aiModelsKey("https://api.example/another", "https://api.example/v1", "", saved))
        assertEquals("new-credential", aiModelsKey("https://other.example/v1", "https://api.example/v1", "new-credential", saved))
        assertEquals(1, reads)
        assertThrows(IllegalArgumentException::class.java) {
            aiModelsKey("https://api.example/v1", "https://api.example/v1", "bad\r\nheader", saved)
        }
    }

    @Test fun invalidAddressesEmptyListsAndMalformedResponsesFailWithoutLeakingResponseText() {
        val never = AiModelsClient { _, _ -> error("must not send") }
        for (url in listOf("http://example.test/v1", "https://user:secret@example.test/v1", "https://example.test/v1?key=x")) {
            assertThrows(IllegalArgumentException::class.java) { never.list(url) }
        }
        for (raw in listOf("{broken-sensitive-body", "{\"data\":[]}", "{\"error\":\"sensitive-body\"}")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                AiModelsClient { _, _ -> raw }.list("https://example.test/v1")
            }
            assertFalse(error.message.orEmpty().contains("sensitive-body"))
        }
    }

    @Test fun productionGetOmitsAnonymousAuthorizationAndNeverFollowsRedirects() {
        val anonymous = Connection(200, "{\"data\":[]}")
        assertEquals("{\"data\":[]}", AiModelsClient.get("https://example.test/v1/models", "") { anonymous })
        assertNull(anonymous.getRequestProperty("Authorization"))
        assertEquals("GET", anonymous.requestMethod)
        assertFalse(anonymous.instanceFollowRedirects)
        assertTrue(anonymous.disconnected)
        val authorized = Connection(200, "{}")
        AiModelsClient.get("https://example.test/v1/models", "local-test-credential") { authorized }
        assertEquals("Bearer local-test-credential", authorized.getRequestProperty("Authorization"))
        val redirect = Connection(302, "sensitive-body")
        assertThrows(IllegalArgumentException::class.java) {
            AiModelsClient.get("https://example.test/v1/models", "local-test-credential") { redirect }
        }
        assertFalse(redirect.bodyRead); assertTrue(redirect.disconnected)
    }

    @Test fun authorizationFailuresAndOversizedBodiesAreBoundedAndActionable() {
        val denied = Connection(401, "sensitive-body")
        val error = assertThrows(IllegalArgumentException::class.java) {
            AiModelsClient.get("https://example.test/v1/models", "") { denied }
        }
        assertTrue(error.message.orEmpty().contains("需要 API Key")); assertFalse(denied.bodyRead)
        val oversized = Connection(200, " ".repeat(4 * 1024 * 1024 + 1))
        assertThrows(IllegalArgumentException::class.java) { AiModelsClient.get("https://example.test/v1/models", "") { oversized } }
        assertTrue(oversized.disconnected)
    }

    @Test fun cancelledDiscoveryDoesNotSendTheRequest() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) {
                AiModelsClient { _, _ -> error("must not send") }.list("https://example.test/v1")
            }
        } finally { Thread.interrupted() }
    }

    private class Connection(private val status: Int, private val body: String) : HttpURLConnection(URL("https://example.test/v1/models")) {
        var disconnected = false
        var bodyRead = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray()).also { bodyRead = true }
        override fun disconnect() { disconnected = true }
        override fun connect() = Unit
        override fun usingProxy() = false
    }
}
