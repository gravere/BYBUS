package com.example.limbuszhcn.update

import com.example.limbuszhcn.selfupdate.UpdateHttpException
import com.example.limbuszhcn.tracking.writeProjectCache
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

internal data class PublicMetadataResponse(val status: Int, val text: String = "", val etag: String? = null,
    val retryMillis: Long = 0)

/** Fixed public endpoints, one request per cache, persistent validators and endpoint-local backoff. */
internal class PublicMetadataCache(private val file: Path, private val url: String,
    private val ttl: Long, private val maxBytes: Int, private val validate: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: (String, String?) -> PublicMetadataResponse = { address, etag -> read(address, etag, maxBytes) }) {
    private var loaded = false
    private var body: String? = null
    private var etag: String? = null
    private var checkedAt = 0L
    private var retryAt = 0L
    private var failureStatus = 0
    private var failures = 0

    @Synchronized fun get(force: Boolean = false): String {
        load()
        val now = clock()
        if (now < retryAt) {
            if (failureStatus in listOf(403, 429)) throw UpdateHttpException(failureStatus, retryAt - now)
            if (!force) throw IOException("更新服务暂时不可用，请稍后重试")
        }
        body?.takeIf { !force && checkedAt > 0 && now - checkedAt in 0 until ttl }?.let {
            try { validate(it); return it } catch (_: Exception) { checkedAt = 0 }
        }
        try {
            val response = fetch(url, etag)
            if (response.status !in listOf(200, 304)) throw UpdateHttpException(response.status, response.retryMillis)
            val text = if (response.status == 304) body ?: throw IOException("更新缓存已失效") else response.text
            require(text.toByteArray(Charsets.UTF_8).size <= maxBytes) { "更新信息过大" }
            validate(text)
            body = text
            if (response.status == 200) etag = response.etag?.takeIf { it.length <= 512 && it.none(Char::isISOControl) }
            checkedAt = now; retryAt = 0; failureStatus = 0; failures = 0
            save()
            return text
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted) throw error
            failures = (failures + 1).coerceAtMost(6)
            val reported = (error as? UpdateHttpException)?.retryAfterMillis ?: 0
            failureStatus = if (reported > 0) (error as UpdateHttpException).status else 0
            retryAt = now + maxOf((60_000L shl (failures - 1)).coerceAtMost(15 * 60_000L), reported)
            save()
            throw error
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            require(Files.size(file) <= maxBytes * 6L + 4096)
            val saved = JSONObject(String(Files.readAllBytes(file), Charsets.UTF_8))
            require(saved.getInt("schema") == 1 && saved.getString("url") == url)
            body = saved.optString("body").takeIf { it.isNotEmpty() && it.toByteArray().size <= maxBytes }
            etag = saved.optString("etag").takeIf { it.length in 1..512 && it.none(Char::isISOControl) && body != null }
            checkedAt = saved.optLong("checkedAt").takeIf { it in 1..clock() } ?: 0
            retryAt = saved.optLong("retryAt").takeIf { it in clock()..clock() + UpdateHttpException.MAX_WAIT } ?: 0
            failureStatus = saved.optInt("failureStatus")
            failures = saved.optInt("failures").coerceIn(0, 6)
        }
    }

    private fun save() { runCatching { writeProjectCache(file, JSONObject().apply {
        put("schema", 1); put("url", url); put("body", body ?: ""); put("etag", etag ?: "")
        put("checkedAt", checkedAt); put("retryAt", retryAt); put("failureStatus", failureStatus); put("failures", failures)
    }.toString()) } }

    companion object {
        private fun allowed(url: String): Boolean = runCatching {
            val uri = java.net.URI(url)
            uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443) && uri.fragment == null &&
                (com.example.limbuszhcn.selfupdate.AppReleases.allowed(url) ||
                 url == com.example.limbuszhcn.tracking.ZeroGitHub.FEED)
        }.getOrDefault(false)

        private fun read(address: String, etag: String?, maxBytes: Int): PublicMetadataResponse {
            var url = address
            repeat(6) {
                require(allowed(url)) { "更新地址无效" }
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.connectTimeout = 5_000; connection.readTimeout = 7_000
                    connection.setRequestProperty("Cache-Control", "no-cache")
                    connection.setRequestProperty("User-Agent", "BYBUS-PublicUpdates/1")
                    connection.setRequestProperty("Accept", "application/json")
                    // Conditional requests use the same validator across GitHub's stable redirect.
                    if (etag != null) connection.setRequestProperty("If-None-Match", etag)
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        url = URL(URL(url), connection.getHeaderField("Location") ?: throw IOException("更新地址失效")).toString()
                        return@repeat
                    }
                    if (status == 304) return PublicMetadataResponse(304)
                    if (status != 200) {
                        val errorBody = connection.errorStream?.use { input ->
                            val bytes = ByteArray(8192)
                            val count = input.read(bytes)
                            if (count > 0) String(bytes, 0, count, Charsets.UTF_8) else ""
                        } ?: ""
                        val error = UpdateHttpException.fromResponse(status, connection.getHeaderField("X-RateLimit-Remaining"),
                            connection.getHeaderField("X-RateLimit-Reset"), connection.getHeaderField("Retry-After"),
                            connection.getHeaderField("Date"), errorBody, System.currentTimeMillis())
                        throw error
                    }
                    require(connection.contentLengthLong <= maxBytes) { "更新信息过大" }
                    val start = System.nanoTime()
                    val output = java.io.ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (Thread.currentThread().isInterrupted || System.nanoTime() - start > 15_000_000_000L)
                                throw IOException("更新查询超时")
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= maxBytes) { "更新信息过大" }
                            output.write(buffer, 0, count)
                        }
                    }
                    return PublicMetadataResponse(200, output.toString("UTF-8"), connection.getHeaderField("ETag"))
                } finally { connection.disconnect() }
            }
            throw IOException("更新地址跳转过多")
        }
    }
}
