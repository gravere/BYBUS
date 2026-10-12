package com.example.limbuszhcn.update

import com.example.limbuszhcn.storage.AtomicFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal enum class GameUpdateSource(val key: String, val label: String, val page: String) {
    GooglePlay("play", "Google Play", "https://play.google.com/store/apps/details?id=com.ProjectMoon.LimbusCompany&hl=en&gl=US"),
    Uptodown("uptodown", "Uptodown", "https://limbus-company.en.uptodown.com/android");

    fun parse(html: String): String = when (this) {
        GooglePlay -> PlayListing.version(html)
        Uptodown -> UptodownListing.version(html)
    }

    companion object {
        fun fromKey(key: String?): GameUpdateSource? = entries.find { it.key == key }
    }
}

/** Every source retains its own provenance; never collapse different channels into a maximum. */
internal data class GameUpdateCatalog(
    val channels: Map<GameUpdateSource, GameUpdateState> = GameUpdateSource.entries.associateWith { GameUpdateState() }
) {
    val loading: Boolean get() = channels.values.any { it.loading }
    fun newerSources(installed: String?) = channels.filterValues { it.availableFor(installed) }.keys
    val label: String get() = when {
        loading -> "检查中"
        channels.values.all { it.checkedAt > 0 && it.issue == null } -> "已检查"
        channels.values.any { it.checkedAt > 0 && it.issue == null } -> "部分已查"
        channels.values.any { it.issue != null } -> "待确认"
        else -> "未检查"
    }
}

internal data class GameUpdateState(
    val latestVersion: String? = null,
    val checkedAt: Long = 0,
    val loading: Boolean = false,
    val issue: String? = null
) {
    fun availableFor(installed: String?): Boolean =
        GameVersion.compare(latestVersion, installed)?.let { it > 0 } == true

    val label: String get() = when {
        loading -> "检查中"
        issue != null -> "待确认"
        latestVersion != null -> "已检查"
        else -> "未检查"
    }
}

internal object GameVersion {
    private val pattern = Regex("[0-9]{1,6}(?:\\.[0-9]{1,6}){1,3}")
    fun valid(value: String?) = value != null && pattern.matches(value)
    fun compare(left: String?, right: String?): Int? {
        if (!valid(left) || !valid(right)) return null
        val a = left!!.split('.').map(String::toInt)
        val b = right!!.split('.').map(String::toInt)
        for (i in 0 until maxOf(a.size, b.size)) {
            val difference = (a.getOrNull(i) ?: 0).compareTo(b.getOrNull(i) ?: 0)
            if (difference != 0) return difference
        }
        return 0
    }
}

/** A deliberately narrow parser for the package record, never the review/version strings. */
internal object PlayListing {
    const val PACKAGE = "com.ProjectMoon.LimbusCompany"
    const val URL = "https://play.google.com/store/apps/details?id=$PACKAGE&hl=en&gl=US"
    const val MAX_BYTES = 3 * 1024 * 1024
    private val callback = Regex("AF_initDataCallback\\(\\{key:\\s*'ds:5',")

    fun version(html: String): String {
        require(html.length <= MAX_BYTES) { "Oversized Play listing" }
        val start = callback.find(html)?.range?.last ?: error("Missing Play package record")
        val end = html.indexOf("</script>", start).takeIf { it > start } ?: error("Incomplete Play record")
        val dataStart = html.indexOf("data:", start).takeIf { it in start until end }
            ?: error("Missing Play data")
        val dataEnd = html.lastIndexOf(", sideChannel:", end).takeIf { it > dataStart }
            ?: error("Incomplete Play data")
        val record = JSONArray(html.substring(dataStart + 5, dataEnd)).getJSONArray(1).getJSONArray(2)
        require(record.getJSONArray(77).getString(0) == PACKAGE) { "Wrong Play package" }
        val version = record.getJSONArray(140).getJSONArray(0).getJSONArray(0).getString(0)
        require(GameVersion.valid(version)) { "Invalid Play version" }
        return version
    }

    fun fetch(): String = GameListingHttp.fetch(GameUpdateSource.GooglePlay)
}

/** Only the current Android app entity and its explicit package field are accepted. */
internal object UptodownListing {
    private val scripts = Regex("<script\\b[^>]*\\btype\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val packageCell = Regex("<th>\\s*Package Name\\s*</th>\\s*<td>\\s*" +
        Regex.escape(PlayListing.PACKAGE) + "\\s*</td>", RegexOption.IGNORE_CASE)

    fun version(html: String): String {
        require(html.length <= PlayListing.MAX_BYTES && packageCell.containsMatchIn(html)) { "Wrong Uptodown package" }
        val candidates = scripts.findAll(html).take(32).mapNotNull { match ->
            val root = runCatching { JSONObject(match.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
            if (root.optString("@type") != "WebPage" || root.optString("url") != GameUpdateSource.Uptodown.page)
                return@mapNotNull null
            val app = root.optJSONObject("mainEntity") ?: return@mapNotNull null
            val types = app.opt("@type")
            val mobile = types == "MobileApplication" || types is JSONArray &&
                (0 until types.length()).any { types.optString(it) == "MobileApplication" }
            if (!mobile || app.optString("url") != GameUpdateSource.Uptodown.page ||
                app.optString("name") != "Limbus Company" || app.optString("operatingSystem") != "Android" ||
                app.optJSONObject("author")?.optString("name") != "Project Moon") return@mapNotNull null
            app.optString("softwareVersion").also { require(GameVersion.valid(it)) { "Invalid Uptodown version" } }
        }.toList()
        require(candidates.size == 1) { "Missing or ambiguous Uptodown app entity" }
        return candidates.single()
    }
}

internal object GameListingHttp {
    fun fetch(source: GameUpdateSource): String {
        val connection = URL(source.page).openConnection() as HttpURLConnection
        val started = System.nanoTime()
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.setRequestProperty("Accept", "text/html")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 BYBUS/1")
            require(connection.responseCode == 200) { "${source.label} listing unavailable" }
            require(connection.contentLengthLong <= PlayListing.MAX_BYTES) { "Oversized game listing" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() - started > 8_000_000_000L)
                        throw IOException("Game version check timed out")
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= PlayListing.MAX_BYTES) { "Oversized game listing" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString("UTF-8")
        } finally { connection.disconnect() }
    }
}

/** Background status and prelaunch checks share a cache; never called on the launch worker. */
internal class GameUpdateRepository(
    private val cache: Path,
    private val fetch: (() -> String)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val source: GameUpdateSource = GameUpdateSource.GooglePlay
) {
    private var current: GameUpdateState? = null
    private var lastAttempt: Long? = null

    @Synchronized fun cached(): GameUpdateState {
        current?.let { return it }
        val saved = runCatching {
            val legacy = cache.resolveSibling("game-update.properties")
            val savedPath = if (!Files.exists(cache) && source == GameUpdateSource.GooglePlay && Files.isRegularFile(legacy)) legacy else cache
            require(Files.size(savedPath) <= 4096)
            val p = AtomicFiles.readProperties(savedPath)
            val schema = p.getProperty("schema")
            require((schema == "2" && p.getProperty("source") == source.key ||
                schema == "1" && source == GameUpdateSource.GooglePlay) && p.getProperty("package") == PlayListing.PACKAGE)
            val version = p.getProperty("version")
            val time = p.getProperty("checkedAt").toLong()
            require(GameVersion.valid(version) && time in 1..clock())
            GameUpdateState(version, time, issue = if (clock() - time >= 15 * 60_000) "保存的渠道记录，等待刷新" else null)
        }.getOrElse { GameUpdateState() }
        return saved.also { current = it }
    }

    @Synchronized fun refresh(force: Boolean = false): GameUpdateState {
        val previous = cached()
        // A launch joining the just-finished home-screen check need not request the same page twice.
        val sinceAttempt = lastAttempt?.let { elapsed() - it }
        if (sinceAttempt != null && sinceAttempt in 0 until 15_000) return previous
        if (!force && previous.issue == null && previous.checkedAt > 0 &&
            clock() - previous.checkedAt in 0 until 15 * 60_000) return previous
        lastAttempt = elapsed()
        val result = try {
            GameUpdateState(source.parse(fetch?.invoke() ?: GameListingHttp.fetch(source)), clock())
        } catch (error: Exception) {
            if (Thread.currentThread().isInterrupted || error is java.util.concurrent.CancellationException) throw error
            previous.copy(loading = false, issue = "${source.label} 暂未刷新，可打开渠道页面查看")
        }
        if (result.issue == null) runCatching {
            AtomicFiles.properties(cache, Properties().apply {
                setProperty("schema", "2"); setProperty("source", source.key); setProperty("package", PlayListing.PACKAGE)
                setProperty("version", result.latestVersion!!); setProperty("checkedAt", result.checkedAt.toString())
            })
        }
        return result.also { current = it }
    }
}
