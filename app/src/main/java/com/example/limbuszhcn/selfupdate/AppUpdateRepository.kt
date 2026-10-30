package com.example.limbuszhcn.selfupdate

import com.example.limbuszhcn.storage.AtomicFiles
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal fun interface AppUpdateTransport {
    fun read(url: String, binary: Boolean, consume: (InputStream, Long) -> Unit)
}

internal data class AppUpdateProgress(val message: String, val percent: Int?, val delta: AppDelta?, val reuse: AppReusePlan? = null)

internal class AppUpdateRepository @JvmOverloads constructor(private val directory: File,
    private val transport: AppUpdateTransport = AppUpdateTransport { url, binary, consume -> fetch(url, binary, consume = consume) },
    private val rangeFactory: (ReleaseAsset) -> AppRangeReader = { asset -> ranges(asset) }) {
    private val apk get() = File(directory, AppReleases.APK)

    fun check(): AppRelease {
        val release = JSONObject(readJson("${AppReleases.API}/latest", 512 * 1024))
        require(!release.getBoolean("draft") && !release.getBoolean("prerelease")) { "尚无正式版本" }
        val metadata = AppRelease.asset(release, AppReleases.MANIFEST)
        require(metadata.size <= 32 * 1024) { "更新描述过大" }
        return AppRelease.parse(release, JSONObject(readJson(metadata.url, 32 * 1024, binary = true)))
    }

    private fun readJson(url: String, maxBytes: Int, binary: Boolean = false): String {
        val output = ByteArrayOutputStream()
        transport.read(url, binary) { input, _ ->
            copy(input, maxBytes.toLong()) { bytes, count -> output.write(bytes, 0, count) }
        }
        return output.toString("UTF-8")
    }

    fun selectDelta(release: AppRelease, installedCode: Long, baseApk: File): AppDelta? {
        val delta = release.deltas.singleOrNull { it.fromVersionCode == installedCode } ?: return null
        return try { delta.takeIf { AppUpdateDelta.matchesBase(baseApk, it) } }
        catch (_: java.io.IOException) { null }
    }

    fun selectReuse(release: AppRelease, baseApk: File): AppReusePlan? = try {
        AppUpdateReuse.plan(baseApk, release)
    } catch (error: Exception) {
        if (error is CancellationException || Thread.currentThread().isInterrupted) throw CancellationException()
        null // Optional local reuse must never disable a complete update.
    }

    fun download(release: AppRelease, baseApk: File?, delta: AppDelta?, progress: (AppUpdateProgress) -> Unit): File =
        download(release, baseApk, delta, null, progress)

    fun download(release: AppRelease, baseApk: File?, delta: AppDelta?, reuse: AppReusePlan?,
        progress: (AppUpdateProgress) -> Unit): File = synchronized(stagingLock) {
        require(directory.isDirectory || directory.mkdirs()) { "无法创建更新缓存" }
        val pending = File(directory, "download.part")
        val patch = File(directory, "update.delta.part")
        pending.delete(); patch.delete()
        if (apk.isFile && apk.length() == release.apk.size && hash(apk) == release.sha256) return@synchronized apk
        apk.delete()
        try {
            var reconstructed = false
            var fallback = false
            if ((reuse != null || delta != null) && baseApk != null) {
                try {
                    if (reuse != null) {
                        progress(AppUpdateProgress("正在复用已安装内容", 0, null, reuse))
                        AppUpdateReuse.apply(baseApk, pending, release, reuse, rangeFactory(release.apk)) {
                            progress(AppUpdateProgress("正在下载差异内容", it, null, reuse))
                        }
                    } else {
                        requireNotNull(delta)
                        progress(AppUpdateProgress("正在校验已安装版本", null, delta))
                        require(delta in release.deltas && AppUpdateDelta.matchesBase(baseApk, delta)) { "差异补丁与当前版本不匹配" }
                        downloadAsset(delta.asset, delta.sha256, patch) {
                            progress(AppUpdateProgress("正在下载差异补丁", it, delta))
                        }
                        progress(AppUpdateProgress("正在合成安装包", 0, delta))
                        AppUpdateDelta.apply(baseApk, patch, pending, delta, release) {
                            progress(AppUpdateProgress("正在合成安装包", it, delta))
                        }
                    }
                    reconstructed = true
                } catch (error: Exception) {
                    if (error is CancellationException || Thread.currentThread().isInterrupted) throw CancellationException()
                    if (error is UpdateHttpException && error.retryAfterMillis > 0) throw error
                    pending.delete(); patch.delete()
                    fallback = true
                    progress(AppUpdateProgress("增量更新不可用，改为下载完整包", null, null))
                }
            }
            if (!reconstructed) downloadAsset(release.apk, release.sha256, pending) {
                progress(AppUpdateProgress(if (fallback) "已回退完整包 · 正在下载" else "正在下载完整安装包", it, null))
            }
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            AtomicFiles.move(pending.toPath(), apk.toPath(), replace = true)
            apk
        } finally { pending.delete(); patch.delete() }
    }

    private fun downloadAsset(asset: ReleaseAsset, sha256: String, target: File, progress: (Int) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var last = -1
        progress(0)
        transport.read(asset.url, true) { input, length ->
            require(length <= 0 || length == asset.size) { "下载文件大小与发行信息不一致" }
            target.outputStream().use { output ->
                copy(input, asset.size) { bytes, count ->
                    output.write(bytes, 0, count)
                    digest.update(bytes, 0, count)
                    total += count
                    val percent = (total * 100 / asset.size).toInt()
                    if (percent != last) { last = percent; progress(percent) }
                }
                output.fd.sync()
            }
        }
        require(total == asset.size && digest.digest().hex() == sha256) { "下载文件校验失败，请重新下载" }
    }

    fun clear() = synchronized(stagingLock) {
        apk.delete(); File(directory, "download.part").delete(); File(directory, "update.delta.part").delete()
    }

    companion object {
        // A recreated screen must not race a cancelled worker still leaving a socket read.
        private val stagingLock = Any()
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { copy(it, AppReleases.MAX_APK_BYTES) { bytes, count -> digest.update(bytes, 0, count) } }
            return digest.digest().hex()
        }

        fun copy(input: InputStream, limit: Long, consume: (ByteArray, Int) -> Unit): Long {
            var size = 0L
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw CancellationException()
                if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= limit) { "下载文件超出大小限制" }
                consume(buffer, count)
            }
            return size
        }

        private fun ranges(asset: ReleaseAsset): AppRangeReader {
            // Keep the signed CDN redirect only for this download session; no extra API hit per gap.
            var endpoint = asset.url
            return AppRangeReader { offset, length, consume ->
                fetch(endpoint, true, AppByteRange(offset, length, asset.size), { endpoint = it }) { input, _ -> consume(input) }
            }
        }

        private fun <T> fetch(initial: String, binary: Boolean, range: AppByteRange? = null,
            resolved: (String) -> Unit = {}, consume: (InputStream, Long) -> T): T {
            var url = initial
            repeat(5) {
                require(AppReleases.allowed(url)) { "更新下载地址不受信任" }
                if (Thread.currentThread().isInterrupted) throw CancellationException()
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 12_000
                    connection.readTimeout = 15_000
                    connection.useCaches = false
                    connection.setRequestProperty("User-Agent", "BYBUS-App-Update")
                    connection.setRequestProperty("Accept", if (binary) "application/octet-stream" else "application/vnd.github+json")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    range?.let { connection.setRequestProperty("Range", it.header) }
                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        url = URL(URL(url), requireNotNull(connection.getHeaderField("Location"))).toString()
                    } else {
                        if (range != null && status == 200) error("服务器不支持分段更新")
                        if (status != if (range == null) 200 else 206) {
                            val body = connection.errorStream?.use { input ->
                                val bytes = ByteArray(8192)
                                var used = 0
                                while (used < bytes.size) {
                                    if (Thread.currentThread().isInterrupted) throw CancellationException()
                                    val count = input.read(bytes, used, bytes.size - used)
                                    if (count <= 0) break
                                    used += count
                                }
                                String(bytes, 0, used, Charsets.UTF_8)
                            }.orEmpty()
                            throw UpdateHttpException.fromResponse(status, connection.getHeaderField("X-RateLimit-Remaining"),
                                connection.getHeaderField("X-RateLimit-Reset"), connection.getHeaderField("Retry-After"),
                                connection.getHeaderField("Date"), body, System.currentTimeMillis())
                        }
                        range?.validate(status, connection.getHeaderField("Content-Range"), connection.contentLengthLong,
                            connection.getHeaderField("Content-Encoding"))
                        resolved(url)
                        return connection.inputStream.use { consume(it, connection.contentLengthLong) }
                    }
                } finally { connection.disconnect() }
            }
            error("更新服务器跳转次数过多")
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }
}
