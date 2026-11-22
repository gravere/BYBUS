package com.example.limbuszhcn.update

import com.example.limbuszhcn.selfupdate.UpdateHttpException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PublicMetadataCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val url = "https://raw.githubusercontent.com/gravere/BYBUS/updates/zero-release.json"

    @Test fun manualRetryBypassesPersistedNetworkFailureAndSuccessTtlButKeepsEtag() {
        val file = folder.root.toPath().resolve("cache.json")
        var calls = 0
        fun cache() = PublicMetadataCache(file, url, 30_000, 1024, {}, { 1_000_000 }) { _, tag ->
            calls++
            when (calls) {
                1 -> throw java.net.SocketTimeoutException()
                2 -> PublicMetadataResponse(200, "ok", "v2")
                else -> { assertEquals("v2", tag); PublicMetadataResponse(304) }
            }
        }
        assertThrows(java.io.IOException::class.java) { cache().get() }
        assertThrows(java.io.IOException::class.java) { cache().get() }
        assertEquals(1, calls)
        assertEquals("ok", cache().get(force = true))
        assertEquals("ok", cache().get(force = true))
        assertEquals(3, calls)
    }

    @Test fun concurrentReadersAndRestartReuseOneValidatedResponseThenRevalidateWithEtag() {
        var now = 1_000_000L
        var calls = 0
        val file = folder.root.toPath().resolve("cache.json")
        val fetch: (String, String?) -> PublicMetadataResponse = { address, tag ->
            assertEquals(url, address)
            calls++
            if (calls == 1) { assertNull(tag); PublicMetadataResponse(200, "good", "\"v1\"") }
            else { assertEquals("\"v1\"", tag); PublicMetadataResponse(304) }
        }
        fun cache() = PublicMetadataCache(file, url, 30_000, 1024, { require(it == "good") }, { now }, fetch)
        val shared = cache()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..32).map { pool.submit<String> { shared.get() } }
            results.forEach { assertEquals("good", it.get(5, TimeUnit.SECONDS)) }
        } finally { pool.shutdownNow() }
        assertEquals(1, calls)
        assertEquals("good", cache().get()); assertEquals(1, calls)
        now += 30_001
        assertEquals("good", cache().get()); assertEquals(2, calls)
    }

    @Test fun corruptResponseCannotReplaceGoodBodyAndBackoffSurvivesRestart() {
        var now = 1_000_000L
        var calls = 0
        val file = folder.root.toPath().resolve("cache.json")
        fun cache() = PublicMetadataCache(file, url, 10, 1024, { require(it == "good") }, { now }, { _, _ ->
            calls++; PublicMetadataResponse(200, if (calls == 1) "good" else "invalid")
        })
        cache().get(); now += 20
        assertThrows(Exception::class.java) { cache().get() }
        assertThrows(Exception::class.java) { cache().get() }
        assertEquals(2, calls)
        val saved = org.json.JSONObject(java.nio.file.Files.readAllBytes(file).toString(Charsets.UTF_8))
        assertEquals("good", saved.getString("body"))
    }

    @Test fun cacheDoesNotTreatAnotherEndpointQuotaAsItsOwn() {
        val file = folder.root.toPath().resolve("cache.json")
        var calls = 0
        val blocked = PublicMetadataCache(file, "https://api.github.com/old", 30_000, 1024, {}, { 1_000_000 }) { _, _ ->
            calls++; PublicMetadataResponse(429, retryMillis = 3_600_000)
        }
        assertThrows(UpdateHttpException::class.java) { blocked.get() }
        val replacement = PublicMetadataCache(file, url, 30_000, 1024, {}, { 1_000_000 }) { _, _ ->
            calls++; PublicMetadataResponse(200, "new")
        }
        assertEquals("new", replacement.get()); assertEquals(2, calls)
    }

    @Test fun serverDeadlineIsRespectedAndEndsWithoutAnExtraRestart() {
        val file = folder.root.toPath().resolve("cache.json")
        var now = 1_000_000L
        var calls = 0
        fun cache() = PublicMetadataCache(file, url, 30_000, 1024, {}, { now }) { _, _ ->
            calls++; if (calls == 1) PublicMetadataResponse(429, retryMillis = 60_000) else PublicMetadataResponse(200, "ok")
        }
        assertThrows(UpdateHttpException::class.java) { cache().get() }
        now += 59_999
        assertThrows(UpdateHttpException::class.java) { cache().get(force = true) }; assertEquals(1, calls)
        now++
        assertEquals("ok", cache().get()); assertEquals(2, calls)
    }
}
