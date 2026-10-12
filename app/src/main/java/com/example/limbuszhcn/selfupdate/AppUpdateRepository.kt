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

internal class UpdateHttpException(val status: Int, val retryAfterMillis: Long) : Exception(when (status) {
    403, 429 -> "GitHub 暂时限制了请求，请稍后重试或查看发行页面"
    404 -> "暂未找到正式发行版，请稍后重试"
    else -> "更新服务暂时不可用（HTTP $status）"
})

internal class AppUpdateRepository(private val directory: File) {
    private val apk get() = File(directory, AppReleases.APK)

    fun check(): AppRelease {
        val release = JSONObject(readJson("${AppReleases.API}/latest", 512 * 1024))
        require(!release.getBoolean("draft") && !release.getBoolean("prerelease")) { "尚无正式版本" }
        val metadata = AppRelease.asset(release, AppReleases.MANIFEST)
        require(metadata.size <= 32 * 1024) { "更新描述过大" }
        return AppRelease.parse(release, JSONObject(readJson(metadata.url, 32 * 1024, binary = true)))
    }

    private fun readJson(url: String, maxBytes: Int, binary: Boolean = false): String =
        fetch(url, binary) { input, _ ->
            val output = ByteArrayOutputStream()
            copy(input, maxBytes.toLong()) { bytes, count -> output.write(bytes, 0, count) }
            output.toString("UTF-8")
        }

    fun download(release: AppRelease, progress: (Int) -> Unit): File = synchronized(stagingLock) {
        require(directory.isDirectory || directory.mkdirs()) { "无法创建更新缓存" }
        if (apk.isFile && apk.length() == release.apk.size && hash(apk) == release.sha256) return@synchronized apk
        val pending = File(directory, "download.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var last = -1
            val size = fetch(release.apk.url, binary = true) { input, length ->
                require(length <= 0 || length == release.apk.size) { "下载文件大小与发行信息不一致" }
                pending.outputStream().use { output ->
                    var total = 0L
                    copy(input, release.apk.size) { bytes, count ->
                        output.write(bytes, 0, count)
                        digest.update(bytes, 0, count)
                        total += count
                        val percent = (total * 100 / release.apk.size).toInt()
                        if (percent != last) { last = percent; progress(percent) }
                    }
                    output.fd.sync()
                    total
                }
            }
            require(size == release.apk.size && digest.digest().hex() == release.sha256) { "安装包校验失败，请重新下载" }
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            AtomicFiles.move(pending.toPath(), apk.toPath(), replace = true)
            apk
        } finally { pending.delete() }
    }

    fun clear() = synchronized(stagingLock) { apk.delete(); File(directory, "download.part").delete() }

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

        private fun <T> fetch(initial: String, binary: Boolean, consume: (InputStream, Long) -> T): T {
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
                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        url = URL(URL(url), requireNotNull(connection.getHeaderField("Location"))).toString()
                    } else {
                        if (status != 200) {
                            val retry = connection.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000)
                            val reset = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)?.minus(System.currentTimeMillis())
                            throw UpdateHttpException(status, maxOf(60_000, retry ?: 0, reset ?: 0).coerceAtMost(86_400_000))
                        }
                        return connection.inputStream.use { consume(it, connection.contentLengthLong) }
                    }
                } finally { connection.disconnect() }
            }
            error("更新服务器跳转次数过多")
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }
}
