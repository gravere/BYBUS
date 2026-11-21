package com.example.limbuszhcn.translation

import com.example.limbuszhcn.update.sha256
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Public data only: no account headers, cookies, or credentials are attached to these requests. */
internal class LocalSourceDownload(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val now: () -> Long = System::currentTimeMillis
) {
    data class Result(val file: Path?, val link: SourceUpdateLink)

    fun fetch(link: SourceUpdateLink, revision: String, target: Path,
        progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        var address = SourceUpdateLink.downloadUrl(link.url)
        val started = System.nanoTime()
        val seen = hashSetOf<String>()
        fun active() {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            require((System.nanoTime() - started) / 1_000_000 < 120_000) { "下载超时，已保留原汉化包" }
        }
        try {
            repeat(6) {
                active()
                require(seen.add(address)) { "更新链接重复跳转，请更换下载链接" }
                val fingerprint = AiTranslationJob.digest(address)
                val conditional = link.validatorTarget == fingerprint && link.validatorRevision == revision &&
                    (link.etag != null || link.modified != null)
                val connection = connect(URL(address))
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 12_000
                    connection.useCaches = false
                    connection.setRequestProperty("User-Agent", "BYBUS-Translation-Update")
                    connection.setRequestProperty("Accept", "application/octet-stream, application/zip, application/x-7z-compressed")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    if (conditional) {
                        link.etag?.let { connection.setRequestProperty("If-None-Match", it) }
                        link.modified?.let { connection.setRequestProperty("If-Modified-Since", it) }
                    }
                    val code = connection.responseCode
                    if (code in setOf(301, 302, 303, 307, 308)) {
                        val location = requireNotNull(connection.getHeaderField("Location")) { "下载链接跳转不完整" }
                        address = SourceUpdateLink.downloadUrl(URL(URL(address), location).toString())
                        return@repeat
                    }
                    if (code == 304) {
                        require(conditional) { "更新服务器返回了无效的缓存结果" }
                        return Result(null, link.copy(checkedAt = now()))
                    }
                    require(code == 200) { "更新服务器返回 $code，已保留原汉化包" }
                    require(!connection.contentType.orEmpty().contains("text/html", true)) { "此链接是网页，请填写 ZIP/7z 文件的下载链接" }
                    val length = connection.contentLengthLong
                    val max = 256L * 1024 * 1024
                    require(length <= max) { "汉化包超过 256 MB" }
                    Files.createDirectories(target.parent)
                    var received = 0L
                    connection.inputStream.use { input -> Files.newOutputStream(target).use { output ->
                        val buffer = ByteArray(32768)
                        progress(0, length.coerceAtLeast(0).toInt())
                        while (true) {
                            active()
                            val n = input.read(buffer)
                            if (n < 0) break
                            received += n
                            require(received <= max) { "汉化包超过 256 MB" }
                            output.write(buffer, 0, n)
                            progress(received.toInt(), length.coerceAtLeast(0).toInt())
                        }
                    } }
                    require(received > 0 && (length < 0 || length == received)) { "下载不完整，已保留原汉化包" }
                    val updated = link.copy(etag = SourceUpdateLink.header(connection.getHeaderField("ETag")),
                        modified = SourceUpdateLink.header(connection.getHeaderField("Last-Modified")),
                        validatorTarget = fingerprint, validatorRevision = target.sha256(), checkedAt = now())
                    return Result(target, updated)
                } finally { connection.disconnect() }
            }
            error("下载链接跳转过多，请更换下载链接")
        } catch (error: Exception) {
            Files.deleteIfExists(target)
            // Network exception messages can contain signed URLs; never propagate those into diagnostics.
            if (error is CancellationException || error is IllegalArgumentException || error is IllegalStateException) throw error
            throw IllegalStateException("无法下载汉化更新，已保留原汉化包；请稍后重试")
        }
    }
}
