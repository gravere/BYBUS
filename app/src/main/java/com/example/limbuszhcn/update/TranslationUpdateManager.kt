package com.example.limbuszhcn.update

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.FilterInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.example.limbuszhcn.storage.AtomicFiles
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream

/**
 * 通过公开的 GitHub Releases 检查、下载并解压最新汉化包。
 *
 * @param transport HTTP 传输实现；测试可注入内存响应，生产环境默认使用 HTTPS 连接。
 * @param extractor 负责安全解压和生成补丁清单的归档处理器。
 */
internal class TranslationUpdateManager(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val extractor: TranslationArchiveExtractor = TranslationArchiveExtractor(),
    private val releaseMetadata: (() -> String)? = null,
    private val onProgress: (String) -> Unit = {}
) {
    /**
     * 查询 GitHub 最新 Release，校验并复用缓存归档，然后解压为统一补丁目录。
     *
     * @param request Releases 地址、缓存目录和可选的本地版本信息。
     * @return 已验证归档的解压结果和补丁清单。
     */
    fun installFromGitHub(request: InstallGitHubReleaseRequest): ExtractionResult {
        request.downloadCacheRoot.createDirectories()
        request.patchCacheRoot.createDirectories()

        val boundedTransport = BudgetHttpTransport(transport, 90_000)
        val releaseClient = GitHubTranslationReleaseClient(request.releaseUrl, boundedTransport)
        onProgress("检查汉化更新")
        logInfo("Fetching GitHub translation release url=${request.releaseUrl}")
        val release = releaseMetadata?.let { releaseClient.parseRelease(it()) } ?: releaseClient.fetchRelease()
        if (request.installedVersion != null && release.version <= request.installedVersion) {
            throw NoTranslationUpdateException(
                "No newer translation package: remote=${release.version}, installed=${request.installedVersion}"
            )
        }

        // The extractor already validates both the archive and every extracted file. Reusing it
        // directly avoids hashing the same cached archive again in the downloader on each launch.
        val cachedArchive = request.downloadCacheRoot.resolve("LimbusLocalize_${release.version}.7z")
        if (release.sha256 != null && java.nio.file.Files.isRegularFile(cachedArchive)) {
            try {
                onProgress("校验本地汉化文件")
                return extractor.extract(ExtractionRequest(cachedArchive, request.patchCacheRoot,
                    release.version.toString(), release.sha256))
            } catch (error: ArchiveValidationException) {
                if (Thread.currentThread().isInterrupted) throw error
                // A damaged archive follows the normal download-and-verify repair path.
            }
        }
        logInfo("Downloading GitHub translation package version=${release.version}")
        onProgress("下载最新汉化文件")
        val archive = TranslationPackageDownloader(boundedTransport).download(
            DownloadRequest(
                url = release.assetUrl,
                version = release.version.toString(),
                expectedSha256 = release.sha256,
                destinationDirectory = request.downloadCacheRoot
            )
        )

        logInfo("Extracting GitHub translation package file=${archive.file}, fromCache=${archive.fromCache}")
        onProgress("校验并解压汉化文件")
        return extractor.extract(
            ExtractionRequest(
                archive = archive.file,
                cacheRoot = request.patchCacheRoot,
                version = release.version.toString(),
                expectedSha256 = release.sha256
            )
        )
    }

    companion object {
        private const val TAG = "LimbusZhCN"

        private fun logInfo(message: String) {
            runCatching { Log.i(TAG, message) }
        }
    }
}

/**
 * 将公开的 GitHub Releases 页面转换为 API 请求，并解析最新汉化归档资产。
 *
 * @param releaseUrl GitHub Releases 页面或受支持的 GitHub API 地址。
 * @param transport 发起 GitHub API 请求的传输实现。
 */
internal class GitHubTranslationReleaseClient(
    private val releaseUrl: String,
    private val transport: HttpTransport = UrlConnectionHttpTransport()
) {
    /**
     * 获取目标仓库最新 Release 中的主汉化归档。
     *
     * @return 数字版本、公开下载地址和 GitHub 提供的可选 SHA-256。
     */
    fun fetchRelease(): GitHubTranslationRelease {
        val response = transport.execute(
            HttpRequest(
                url = releaseUrl.toGitHubApiReleaseUrl(),
                method = "GET",
                headers = mapOf(
                    "Accept" to "application/vnd.github+json",
                    "Cache-Control" to "no-cache",
                    "User-Agent" to DEFAULT_USER_AGENT
                ),
                deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            )
        )
        if (response.statusCode != HttpURLConnection.HTTP_OK) {
            response.body.close()
            throw TranslationHttpException("HTTP ${response.statusCode} from GitHub release API")
        }
        return parseRelease(response.bodyText())
    }

    internal fun parseRelease(json: String): GitHubTranslationRelease {
        try {
            require(json.length <= 2 * 1024 * 1024) { "Release metadata exceeds size limit" }
            val tokener = JSONTokener(json)
            val root = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') throw TranslationProtocolException("Trailing data in release JSON")
            val release = when (root) {
                is JSONObject -> root
                is JSONArray -> root.optJSONObject(0)
                else -> null
            } ?: throw TranslationProtocolException("GitHub release response is empty")
            val version = release.getString("tag_name").toLongOrNull()
                ?: throw TranslationProtocolException("GitHub release tag is not numeric")
            val expected = "LimbusLocalize_${version}.7z"
            val assets = release.getJSONArray("assets")
            val matches = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .filter { it.optString("name") == expected }
            val asset = matches.singleOrNull()
                ?: throw TranslationProtocolException("Expected exactly one $expected asset")
            val digest = if (asset.isNull("digest")) null else asset.optString("digest").takeIf { it.isNotBlank() }
            if (digest != null && !digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) {
                throw TranslationProtocolException("Invalid release digest")
            }
            val url = asset.getString("browser_download_url")
            val uri = URI(url)
            if (uri.scheme != "https" || uri.host != "github.com" || uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) {
                throw TranslationProtocolException("Invalid GitHub asset download URL")
            }
            return GitHubTranslationRelease(version, url, digest?.removePrefix("sha256:")?.lowercase())
        } catch (error: org.json.JSONException) {
            throw TranslationProtocolException("Invalid GitHub release JSON: ${error.javaClass.simpleName}")
        }
    }

    private fun String.toGitHubApiReleaseUrl(): String {
        val trimmed = trim()
        val uri = runCatching { URI(trimmed) }.getOrElse {
            throw TranslationProtocolException("Invalid GitHub release URL")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw TranslationProtocolException("GitHub release URL must use HTTPS")
        }
        if (uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) {
            // Releases 地址属于公开配置，不允许嵌入用户名、口令或转发到自定义端口。
            throw TranslationProtocolException("GitHub release URL must not contain credentials or a custom port")
        }
        val host = uri.host?.lowercase()
            ?: throw TranslationProtocolException("GitHub release URL does not contain a host")
        if (host == "api.github.com" && uri.path.startsWith("/repos/") && uri.path.contains("/releases")) {
            return trimmed
        }
        if (host != "github.com") {
            throw TranslationProtocolException("Only github.com Releases URLs are supported")
        }
        val parts = uri.path
            .trim('/')
            .split('/')
        if (parts.size >= 5 && parts[2] == "releases" && parts[3] == "tag") {
            return "https://api.github.com/repos/${parts[0]}/${parts[1]}/releases/tags/${parts[4]}"
        }
        if (parts.size >= 3 && parts[2] == "releases") {
            return "https://api.github.com/repos/${parts[0]}/${parts[1]}/releases?per_page=1"
        }
        throw TranslationProtocolException("Unsupported GitHub release URL")
    }

    companion object {
        private const val DEFAULT_USER_AGENT = "limbuszhcn-android"
    }
}

/**
 * 下载 GitHub Release 归档，并在可用时使用 SHA-256 校验和复用本地缓存。
 *
 * @param transport 归档下载使用的 HTTP 传输实现。
 * @param maxAttempts 短暂网络失败时允许的最大尝试次数。
 */
internal class TranslationPackageDownloader(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val maxAttempts: Int = 3
) {
    /**
     * 下载或复用指定版本的汉化归档。
     *
     * @param request 公开下载地址、版本、可选哈希和缓存目录。
     * @return 最终归档路径以及是否命中已验证缓存。
     */
    fun download(request: DownloadRequest): DownloadResult {
        request.destinationDirectory.createDirectories()
        val finalFile = request.destinationDirectory.resolve("LimbusLocalize_${request.version}.7z")
        if (finalFile.exists() && request.expectedSha256 != null && sha256(finalFile) == request.expectedSha256) {
            return DownloadResult(finalFile, fromCache = true)
        }

        val tempFile = request.destinationDirectory.resolve("${finalFile.fileName}.${java.util.UUID.randomUUID()}.tmp")
        val bounded = BudgetHttpTransport(transport, 90_000)
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                tempFile.deleteIfExists()
                val response = bounded.execute(
                    HttpRequest(
                        url = request.url,
                        method = "GET",
                        headers = mapOf("User-Agent" to "limbuszhcn-android")
                    )
                )
                if (response.statusCode != HttpURLConnection.HTTP_OK) {
                    response.body.close()
                    throw TranslationHttpException("HTTP ${response.statusCode} while downloading GitHub package")
                }

                val bytes = copyResponse(response, tempFile)
                if (response.contentLength > 0 && bytes != response.contentLength) {
                    throw DownloadValidationException(
                        "Downloaded size mismatch: expected ${response.contentLength}, actual $bytes"
                    )
                }

                if (request.expectedSha256 != null) {
                    val actual = sha256(tempFile)
                    if (actual != request.expectedSha256) {
                        throw DownloadValidationException(
                            "Downloaded SHA-256 mismatch: expected ${request.expectedSha256}, actual $actual"
                        )
                    }
                }

                AtomicFiles.move(tempFile, finalFile, replace = true)
                return DownloadResult(finalFile, fromCache = false)
            } catch (error: Exception) {
                lastError = error
                tempFile.deleteIfExists()
                if (error is java.io.InterruptedIOException || Thread.currentThread().isInterrupted) throw error
                if (attempt == maxAttempts - 1) {
                    throw error
                }
            }
        }

        throw lastError ?: DownloadValidationException("Download failed")
    }

    private fun copyResponse(response: HttpResponse, destination: Path): Long {
        var total = 0L
        response.body.use { input ->
            if (response.contentLength > MAX_ARCHIVE_BYTES) throw DownloadValidationException("Archive exceeds download limit")
            BufferedOutputStream(destination.outputStream()).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    total += read
                    if (total > MAX_ARCHIVE_BYTES) throw DownloadValidationException("Archive exceeds download limit")
                    output.write(buffer, 0, read)
                }
            }
        }
        return total
    }

    private fun sha256(path: Path): String = path.sha256()

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    }
}

/**
 * 可替换的 HTTP 传输边界，用于隔离真实网络和单元测试响应。
 */
internal interface HttpTransport {
    /**
     * 执行一条 HTTP 请求。
     *
     * @param request 请求地址、方法、请求体和请求头。
     * @return 状态码、长度和可读取响应流。
     */
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * 基于 `HttpURLConnection` 的 GitHub HTTPS 传输实现。
 *
 * @param connectTimeoutMillis 建立连接的超时时间（毫秒）。
 * @param readTimeoutMillis 下载或读取 API 响应的超时时间（毫秒）。
 */
internal class UrlConnectionHttpTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000
) : HttpTransport {
    /**
     * 使用系统网络栈执行请求，并把响应流所有权交给调用方。
     *
     * @param request 待执行的 HTTP 请求。
     * @return 尚未消费的响应流及其元数据。
     */
    override fun execute(request: HttpRequest): HttpResponse {
        checkNetworkDeadline(request.deadlineNanos)
        val connection = URL(request.url).openConnection() as HttpURLConnection
        val remaining = ((request.deadlineNanos - System.nanoTime()) / 1_000_000).coerceIn(1, 90_000)
        val disconnect = timers.schedule({ connection.disconnect() }, remaining, TimeUnit.MILLISECONDS)
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = minOf(connectTimeoutMillis, remaining.toInt())
            connection.readTimeout = minOf(readTimeoutMillis, remaining.toInt())
            connection.useCaches = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body -> connection.doOutput = true; connection.outputStream.use { it.write(body) } }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = object : FilterInputStream(input ?: ByteArray(0).inputStream()) {
                override fun close() {
                    try { super.close() } finally { disconnect.cancel(false); connection.disconnect() }
                }
            }
            return HttpResponse(status, connection.contentLengthLong, body)
        } catch (error: Exception) {
            disconnect.cancel(false)
            connection.disconnect()
            checkNetworkDeadline(request.deadlineNanos)
            throw error
        }
    }

    companion object {
        private val timers = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "limbus-http-deadline").apply { isDaemon = true }
        }
    }
}

/** 描述一次 GitHub Releases 汉化安装所需的公开地址与本地缓存目录。 */
internal data class InstallGitHubReleaseRequest(
    val releaseUrl: String,
    val downloadCacheRoot: Path,
    val patchCacheRoot: Path,
    val installedVersion: Long? = null
)

/** 描述 GitHub 归档下载地址、版本、校验值和缓存位置。 */
internal data class DownloadRequest(
    val url: String,
    val version: String,
    val expectedSha256: String?,
    val destinationDirectory: Path
)

/** 描述归档下载结果以及本次是否复用了已校验缓存。 */
internal data class DownloadResult(
    val file: Path,
    val fromCache: Boolean
)

/** 描述从 GitHub API 解析出的汉化 Release 主归档。 */
internal data class GitHubTranslationRelease(
    val version: Long,
    val assetUrl: String,
    val sha256: String?
)

/** 描述传输边界可执行的 HTTP 请求。 */
internal data class HttpRequest(
    val url: String,
    val method: String,
    val body: ByteArray? = null,
    val headers: Map<String, String> = emptyMap(),
    val deadlineNanos: Long = Long.MAX_VALUE
)

/** 描述尚未被消费的 HTTP 响应。 */
internal data class HttpResponse(
    val statusCode: Int,
    val contentLength: Long,
    val body: java.io.InputStream
) {
    /**
     * 读取并关闭响应流。
     *
     * @return 使用 UTF-8 解码后的完整响应正文。
     */
    fun bodyText(maxBytes: Int = 2 * 1024 * 1024): String = body.use { input ->
        if (contentLength > maxBytes) throw TranslationProtocolException("API response exceeds size limit")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) throw TranslationProtocolException("API response exceeds size limit")
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8")
    }
}

/** 汉化更新流程可预期错误的共同基类。 */
internal open class TranslationUpdateException(message: String) : IOException(message)

/** GitHub Release 响应格式或地址不符合预期。 */
internal class TranslationProtocolException(message: String) : TranslationUpdateException(message)

/** GitHub API 或归档下载返回了非成功 HTTP 状态。 */
internal class TranslationHttpException(message: String) : TranslationUpdateException(message)

/** 下载长度或 SHA-256 校验失败。 */
internal class DownloadValidationException(message: String) : TranslationUpdateException(message)

/** GitHub 最新版本不高于调用方指定的本地版本。 */
internal class NoTranslationUpdateException(message: String) : TranslationUpdateException(message)

private fun checkNetworkDeadline(deadline: Long) {
    if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) {
        throw java.io.InterruptedIOException("更新检查超过时间限制或已取消")
    }
}

internal class BudgetHttpTransport(private val delegate: HttpTransport, budgetMillis: Long) : HttpTransport {
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
    override fun execute(request: HttpRequest): HttpResponse {
        val limit = minOf(deadline, request.deadlineNanos)
        checkNetworkDeadline(limit)
        val response = delegate.execute(request.copy(deadlineNanos = limit))
        try { checkNetworkDeadline(limit) } catch (error: Exception) { response.body.close(); throw error }
        return response.copy(body = object : FilterInputStream(response.body) {
            override fun read(): Int { checkNetworkDeadline(limit); return super.read().also { checkNetworkDeadline(limit) } }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                checkNetworkDeadline(limit)
                return `in`.read(bytes, offset, length).also { checkNetworkDeadline(limit) }
            }
        })
    }
}

internal fun Path.sha256(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toLowerHex()
}

/**
 * 将字符串编码成可安全写入补丁清单的 JSON 字符串字面量。
 *
 * @return 包含双引号并完成控制字符转义的 JSON 值。
 */
internal fun String.jsonValue(): String {
    val escaped = buildString {
        this@jsonValue.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char < ' ') {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }
    return "\"$escaped\""
}
