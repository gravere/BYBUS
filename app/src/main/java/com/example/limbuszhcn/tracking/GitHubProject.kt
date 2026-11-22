package com.example.limbuszhcn.tracking

import org.json.JSONObject
import org.json.JSONTokener
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

internal enum class ProjectSource(val key: String, val title: String) {
    GitHub("github", "GitHub 更新"), ParaTranz("paratranz", "翻译进度");

    companion object {
        fun fromPreference(value: String?): ProjectSource = entries.firstOrNull { it.key == value } ?: GitHub
    }
}

internal object ZeroGitHub {
    const val REPOSITORY = "https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany"
    const val RELEASES = "$REPOSITORY/releases"
    const val API = "https://api.github.com/repos/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/latest"
    const val FEED = "https://raw.githubusercontent.com/gravere/BYBUS/updates/zero-release.json"
}

internal data class GitHubSnapshot(val tag: String, val publishedAtMillis: Long, val fetchedAtMillis: Long)

internal data class GitHubTrackerState(
    val snapshot: GitHubSnapshot? = null,
    val loading: Boolean = false,
    val issue: ProjectIssue? = null,
    val retryAtMillis: Long? = null
)

/** Each source owns its result: a late request cannot replace the other source's data or selection. */
internal data class ProjectTrackingState(
    val source: ProjectSource = ProjectSource.GitHub,
    val github: GitHubTrackerState = GitHubTrackerState(),
    val paratranz: ProjectTrackerState = ProjectTrackerState()
) {
    val loading: Boolean get() = if (source == ProjectSource.GitHub) github.loading else paratranz.loading
}

internal object GitHubSnapshotCodec {
    fun parseResponse(text: String, fetchedAtMillis: Long): GitHubSnapshot {
        require(text.length <= ProjectSnapshotCodec.MAX_BYTES && fetchedAtMillis > 0)
        val tokener = JSONTokener(text)
        val root = tokener.nextValue() as? JSONObject ?: error("Invalid release")
        require(tokener.nextClean() == '\u0000')
        val tag = root.getString("tag_name")
        require(tag.matches(Regex("[0-9]{1,18}")) && tag.toLong() > 0)
        require(root.get("draft") == false && root.get("prerelease") == false)
        require(root.getString("html_url") == "${ZeroGitHub.RELEASES}/tag/$tag")
        val published = Instant.parse(root.getString("published_at")).toEpochMilli()
        require(published in 1..fetchedAtMillis + 5 * 60_000)
        return GitHubSnapshot(tag, published, fetchedAtMillis)
    }

    fun encode(snapshot: GitHubSnapshot): String = JSONObject().apply {
        put("schema", 1)
        put("fetchedAt", snapshot.fetchedAtMillis)
        put("release", JSONObject().apply {
            put("tag_name", snapshot.tag)
            put("published_at", Instant.ofEpochMilli(snapshot.publishedAtMillis).toString())
            put("html_url", "${ZeroGitHub.RELEASES}/tag/${snapshot.tag}")
            put("draft", false); put("prerelease", false)
        })
    }.toString()

    fun decodeCache(text: String, nowMillis: Long): GitHubSnapshot {
        require(text.length <= ProjectSnapshotCodec.MAX_BYTES)
        val root = JSONObject(text)
        require(root.getInt("schema") == 1)
        val fetchedAt = root.getLong("fetchedAt")
        require(fetchedAt in 1..nowMillis + 5 * 60_000)
        return parseResponse(root.getJSONObject("release").toString(), fetchedAt)
    }
}

/** Application-wide release metadata and quota state, shared by the tracker and launch check. */
internal class GitHubProjectRepository(
    private val cache: Path,
    private val fetch: (() -> String)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private var result: GitHubTrackerState? = null
    private var lastAttempt: Long? = null
    private var rateLimitAttempts = 0
    // A REST quota window from an older app must not block the independent static feed.
    private val retryCache = cache.resolveSibling(cache.fileName.toString() + ".public.retry")
    private val publicFeed = com.example.limbuszhcn.update.PublicMetadataCache(
        cache.resolveSibling(cache.fileName.toString() + ".http"), ZeroGitHub.FEED, 30_000, 32 * 1024,
        { ZeroReleaseFeed.release(it, clock()) }, clock)
    private var releaseMetadata: String? = null
    private val listeners = CopyOnWriteArrayList<(GitHubTrackerState) -> Unit>()

    init {
        // Delete only the obsolete REST wait; keep translations, snapshots and the new endpoint's wait.
        runCatching { Files.deleteIfExists(cache.resolveSibling(cache.fileName.toString() + ".retry")) }
    }

    fun observe(listener: (GitHubTrackerState) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun publish(value: GitHubTrackerState): GitHubTrackerState {
        result = value
        listeners.forEach { listener -> runCatching { listener(value) } }
        return value
    }

    @Synchronized fun cached(): GitHubTrackerState {
        result?.let { return it }
        val snapshot = runCatching {
            require(Files.size(cache) <= ProjectSnapshotCodec.MAX_BYTES)
            val json = String(Files.readAllBytes(cache), Charsets.UTF_8)
            GitHubSnapshotCodec.decodeCache(json, clock()).also {
                releaseMetadata = JSONObject(json).getJSONObject("release").toString()
            }
        }.getOrNull()
        val retryAt = runCatching {
            require(Files.size(retryCache) <= 1024)
            val saved = JSONObject(String(Files.readAllBytes(retryCache), Charsets.UTF_8))
            require(saved.getInt("schema") == 1)
            val until = saved.getLong("retryAt")
            require(until in (clock() - 86_400_000)..(clock() + 86_401_000))
            rateLimitAttempts = saved.getInt("attempts").also { require(it in 1..7) }
            until
        }.getOrNull()
        return GitHubTrackerState(snapshot, issue = if (retryAt != null) ProjectIssue.RateLimited else null,
            retryAtMillis = retryAt).also { result = it }
    }

    @Synchronized fun refresh(force: Boolean = false): GitHubTrackerState {
        val current = cached()
        // A force refresh must not ignore the server's deadline, including after an app restart.
        if (current.retryAtMillis?.let { clock() < it } == true) return publish(current)
        val sinceAttempt = lastAttempt?.let { (elapsed() - it).coerceAtLeast(0) }
        if (!force && sinceAttempt != null && sinceAttempt < 60_000) return publish(current)
        val age = current.snapshot?.let { clock() - it.fetchedAtMillis }
        if (!force && current.issue == null && age != null && age in 0 until 5 * 60_000) return publish(current)
        publish(current.copy(loading = true, issue = null, retryAtMillis = null))
        lastAttempt = elapsed()
        val response = try { fetch?.invoke() ?: ZeroReleaseFeed.release(publicFeed.get(force), clock()) } catch (error: Exception) {
            val http = error as? com.example.limbuszhcn.selfupdate.UpdateHttpException
            val failure = error as? ProjectFetchException ?: http?.takeIf { it.retryAfterMillis > 0 }?.let {
                ProjectFetchException(ProjectIssue.RateLimited, clock() + it.retryAfterMillis)
            }
            val issue = failure?.issue ?: ProjectIssue.Network
            val retryAt = if (issue == ProjectIssue.RateLimited) {
                rateLimitAttempts = (rateLimitAttempts + 1).coerceAtMost(7)
                val backoff = (60_000L shl (rateLimitAttempts - 1)).coerceAtMost(3_600_000L)
                maxOf(clock() + backoff, failure?.retryAtMillis ?: 0).also { until ->
                    runCatching { writeProjectCache(retryCache, JSONObject().apply {
                        put("schema", 1); put("retryAt", until); put("attempts", rateLimitAttempts)
                    }.toString()) }
                }
            } else {
                clearRetry()
                null
            }
            return publish(current.copy(loading = false, issue = issue, retryAtMillis = retryAt))
        } finally { lastAttempt = elapsed() }
        clearRetry()
        val snapshot = runCatching { GitHubSnapshotCodec.parseResponse(response, clock()) }.getOrNull()
            ?: return publish(current.copy(issue = ProjectIssue.InvalidData, retryAtMillis = null))
        // Retain only the exact translation asset's public download fields, never release bodies,
        // authors, account records, or unrelated assets. Old schema-1 display caches still read.
        val saved = JSONObject(GitHubSnapshotCodec.encode(snapshot))
        val release = saved.getJSONObject("release")
        val assets = JSONObject(response).optJSONArray("assets")
        val expected = "LimbusLocalize_${snapshot.tag}.7z"
        val selected = org.json.JSONArray()
        if (assets != null) for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") != expected) continue
            val url = asset.opt("browser_download_url")
            val digest = asset.opt("digest")
            if (url !is String || (digest != null && digest != JSONObject.NULL && digest !is String)) {
                return publish(current.copy(loading = false, issue = ProjectIssue.InvalidData, retryAtMillis = null))
            }
            selected.put(JSONObject().apply {
                put("name", expected)
                put("browser_download_url", url)
                put("digest", digest ?: JSONObject.NULL)
            })
        }
        release.put("assets", selected)
        releaseMetadata = release.toString()
        runCatching { writeProjectCache(cache, saved.toString()) }
        return publish(GitHubTrackerState(snapshot))
    }

    /** Share the five-minute validated snapshot with the home page and concurrent launch checks. */
    @Synchronized fun releaseForLaunch(): String {
        cached()
        val hasAssets = runCatching { JSONObject(releaseMetadata ?: "{}").getJSONArray("assets").length() == 1 }.getOrDefault(false)
        val checked = refresh(force = !hasAssets)
        checked.issue?.let { throw ProjectFetchException(it, checked.retryAtMillis) }
        return releaseMetadata ?: throw ProjectFetchException(ProjectIssue.InvalidData)
    }

    /** User requested a new network check, including after an offline attempt. */
    @Synchronized fun releaseForManualCheck(): String {
        val checked = refresh(force = true)
        checked.issue?.let { throw ProjectFetchException(it, checked.retryAtMillis) }
        return releaseMetadata ?: throw ProjectFetchException(ProjectIssue.InvalidData)
    }

    private fun clearRetry() {
        rateLimitAttempts = 0
        runCatching { Files.deleteIfExists(retryCache) }
    }
}

internal fun writeProjectCache(cache: Path, json: String) {
    Files.createDirectories(cache.parent)
    val temporary = cache.resolveSibling(cache.fileName.toString() + ".part")
    try {
        Files.write(temporary, json.toByteArray(Charsets.UTF_8))
        try {
            Files.move(temporary, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally { Files.deleteIfExists(temporary) }
}
