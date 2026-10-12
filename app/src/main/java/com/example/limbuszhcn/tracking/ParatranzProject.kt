package com.example.limbuszhcn.tracking

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

internal object ZeroProject {
    const val ID = 6860
    const val URL = "https://paratranz.cn/projects/6860"
    const val SNAPSHOT_URL = "https://paratranz.cn/projects/20115"
    const val API = "https://paratranz.cn/api/projects/6860"
}

internal data class ProjectSnapshot(
    val name: String,
    val total: Long,
    val translated: Long,
    val checked: Long?,
    val reviewed: Long?,
    val modifiedAt: String?,
    val fetchedAtMillis: Long
) {
    val translatedFraction: Float? get() = total.takeIf { it > 0 }?.let { translated.toFloat() / it }
}

internal enum class ProjectIssue {
    Unavailable, Restricted, RateLimited, Network, InvalidData
}

internal data class ProjectTrackerState(
    val snapshot: ProjectSnapshot? = null,
    val loading: Boolean = false,
    val issue: ProjectIssue? = null
)

/** Retain only public aggregate data; names, avatars, member records and descriptions are never cached. */
internal object ProjectSnapshotCodec {
    fun parseResponse(text: String, fetchedAtMillis: Long): ProjectSnapshot {
        require(text.length <= MAX_BYTES && fetchedAtMillis > 0)
        val root = JSONObject(text)
        require(count(root, "id") == ZeroProject.ID.toLong()) { "Unexpected project" }
        require(root.getString("dest").equals("zh-cn", ignoreCase = true)) { "Unexpected language" }
        val name = root.getString("name").trim()
        require(name.isNotEmpty() && name.length <= 160 && name.none { it.isISOControl() })
        val stats = root.getJSONObject("stats")
        if (stats.has("id")) require(count(stats, "id") == ZeroProject.ID.toLong()) { "Unexpected statistics project" }
        val total = count(stats, "total")
        fun metric(key: String): Long = count(stats, key).also { require(it <= total) }
        val translated = metric("translated")
        val checked = if (stats.has("checked") && !stats.isNull("checked")) metric("checked") else null
        val reviewed = if (stats.has("reviewed") && !stats.isNull("reviewed")) metric("reviewed") else null
        // Project updatedAt can mean a metadata edit. Only stats.modifiedAt describes text changes.
        val modifiedAt = stats.optString("modifiedAt").takeIf { value ->
            value.length <= 40 && runCatching { Instant.parse(value).toEpochMilli() }.isSuccess
        }
        return ProjectSnapshot(name, total, translated, checked, reviewed, modifiedAt, fetchedAtMillis)
    }

    fun encode(snapshot: ProjectSnapshot): String = JSONObject().apply {
        put("schema", 1)
        put("fetchedAt", snapshot.fetchedAtMillis)
        put("project", JSONObject().apply {
            put("id", ZeroProject.ID); put("dest", "zh-cn"); put("name", snapshot.name)
            put("stats", JSONObject().apply {
                put("total", snapshot.total); put("translated", snapshot.translated)
                snapshot.checked?.let { put("checked", it) }
                snapshot.reviewed?.let { put("reviewed", it) }
                snapshot.modifiedAt?.let { put("modifiedAt", it) }
            })
        })
    }.toString()

    fun decodeCache(text: String, nowMillis: Long): ProjectSnapshot {
        require(text.length <= MAX_BYTES)
        val root = JSONObject(text)
        require(root.getInt("schema") == 1)
        val fetchedAt = root.getLong("fetchedAt")
        require(fetchedAt in 1..(nowMillis + 5 * 60_000)) { "Invalid cache time" }
        return parseResponse(root.getJSONObject("project").toString(), fetchedAt)
    }

    private fun count(root: JSONObject, key: String): Long {
        val value = root.get(key)
        // Reject strings, fractional values, overflow and negative counters instead of coercing them.
        require(value is Int || value is Long) { "Invalid $key" }
        return (value as Number).toLong().also { require(it in 0..2_000_000_000L) }
    }

    const val MAX_BYTES = 256 * 1024
}

internal class ProjectFetchException(val issue: ProjectIssue, val retryAtMillis: Long? = null) : IOException(issue.name)

/** Only documented rate-limit signals count; a normal forbidden response is not a quota failure. */
internal fun projectHttpFailure(status: Int, remaining: String?, reset: String?, retryAfter: String?,
    now: Long): ProjectFetchException {
    val limited = status == 429 || status == 403 && (remaining == "0" || retryAfter != null)
    if (!limited) return ProjectFetchException(when (status) {
        401, 403 -> ProjectIssue.Restricted
        404 -> ProjectIssue.Unavailable
        else -> ProjectIssue.Network
    })
    // Reject malformed or unbounded values instead of persisting an unusable wait time.
    val resetAt = reset?.toLongOrNull()?.takeIf { it in (now / 1000)..(now / 1000 + 86_400) }?.times(1000)
    val afterAt = retryAfter?.toLongOrNull()?.takeIf { it in 0..86_400 }?.let { now + it * 1000 }
        ?: retryAfter?.let { runCatching {
            java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()?.takeIf { time -> time in now..(now + 86_400_000) } }
    val retryAt = listOfNotNull(resetAt, afterAt).maxOrNull()?.let { maxOf(now + 60_000, it + 1000) }
    return ProjectFetchException(ProjectIssue.RateLimited, retryAt)
}

internal object ParatranzClient {
    fun fetch(): String = fetchProjectJson(ZeroProject.API)
}

internal fun fetchProjectJson(url: String): String {
        require(url == ZeroProject.API || url == ZeroGitHub.API)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "BYBUS-ProjectTracker/1")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (connection.responseCode != 200) throw projectHttpFailure(connection.responseCode,
                connection.getHeaderField("X-RateLimit-Remaining"), connection.getHeaderField("X-RateLimit-Reset"),
                connection.getHeaderField("Retry-After"), System.currentTimeMillis())
            if (connection.contentLengthLong > ProjectSnapshotCodec.MAX_BYTES) {
                throw ProjectFetchException(ProjectIssue.InvalidData)
            }
            val started = System.nanoTime()
            val bytes = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() - started > 12_000_000_000L) {
                        throw IOException("Project request interrupted or timed out")
                    }
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > ProjectSnapshotCodec.MAX_BYTES) {
                        throw ProjectFetchException(ProjectIssue.InvalidData)
                    }
                    bytes.write(buffer, 0, count)
                }
            }
            return bytes.toString("UTF-8")
        } finally {
            connection.disconnect()
        }
}

/** Called only by the tracker worker, never by the game-preparation executor. */
internal class ParatranzRepository(
    private val cache: Path,
    private val fetch: () -> String = ParatranzClient::fetch,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private var result: ProjectTrackerState? = null
    private var lastAttempt: Long? = null

    fun cached(): ProjectTrackerState {
        result?.let { return it }
        val snapshot = runCatching {
            require(Files.size(cache) <= ProjectSnapshotCodec.MAX_BYTES)
            ProjectSnapshotCodec.decodeCache(String(Files.readAllBytes(cache), Charsets.UTF_8), clock())
        }.getOrNull()
        return ProjectTrackerState(snapshot).also { result = it }
    }

    fun refresh(force: Boolean = false): ProjectTrackerState {
        val current = cached()
        val elapsedSinceAttempt = lastAttempt?.let { (elapsed() - it).coerceAtLeast(0) }
        if (elapsedSinceAttempt != null && elapsedSinceAttempt < if (force) 15_000 else 60_000) return current
        val age = current.snapshot?.let { clock() - it.fetchedAtMillis }
        if (!force && current.issue == null && age != null && age in 0 until 15 * 60_000) return current
        lastAttempt = elapsed()
        val response = try {
            fetch()
        } catch (error: Exception) {
            return current.copy(issue = (error as? ProjectFetchException)?.issue ?: ProjectIssue.Network)
                .also { result = it }
        }
        val snapshot = runCatching { ProjectSnapshotCodec.parseResponse(response, clock()) }.getOrNull()
            ?: return current.copy(issue = ProjectIssue.InvalidData).also { result = it }
        // A full disk must not discard successfully fetched data or the previous valid cache.
        runCatching { writeProjectCache(cache, ProjectSnapshotCodec.encode(snapshot)) }
        return ProjectTrackerState(snapshot).also { result = it }
    }
}
