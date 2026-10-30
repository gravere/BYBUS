package com.example.limbuszhcn.selfupdate

import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

internal data class ApkReuseSpan(val entry: ApkReuseEntry, val sourceOffset: Long)
internal data class AppReusePlan(val sourcePath: String, val sourceSize: Long, val targetSha256: String,
    val targetSize: Long, val spans: List<ApkReuseSpan>) {
    val downloadBytes: Long get() = targetSize - spans.sumOf { it.entry.length }
}

internal fun interface AppRangeReader {
    fun read(offset: Long, length: Long, consume: (InputStream) -> Unit)
}

internal data class AppByteRange(val offset: Long, val length: Long, val total: Long) {
    init { require(total in 1..AppReleases.MAX_APK_BYTES && offset >= 0 && offset < total && length in 1..total-offset) }
    val header: String get() = "bytes=$offset-${offset+length-1}"
    fun validate(status: Int, contentRange: String?, contentLength: Long, encoding: String?) {
        require(status == 206 && contentRange == "bytes $offset-${offset+length-1}/$total") { "服务器未返回所需更新区间" }
        require(contentLength < 0 || contentLength == length) { "更新区间长度不一致" }
        require(encoding.isNullOrBlank() || encoding.equals("identity", ignoreCase = true)) { "更新区间编码异常" }
    }
}

/** Copies verified raw ZIP payloads; missing bytes come from the one canonical target APK. */
internal object AppUpdateReuse {
    private fun checkpoint(deadline: Long) {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
        require(System.nanoTime() < deadline) { "处理更新内容超时" }
    }

    fun plan(base: File, release: AppRelease): AppReusePlan? {
        if (release.reuse.isEmpty() || !base.isFile || base.length() !in 1..AppReleases.MAX_APK_BYTES) return null
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
        val spans = mutableListOf<ApkReuseSpan>()
        ZipFile.builder().setFile(base).get().use { zip ->
            val entries = zip.entries
            val names = HashSet<String>()
            while (entries.hasMoreElements()) {
                checkpoint(deadline)
                require(names.size < 50_000 && names.add(entries.nextElement().name)) { "已安装包目录异常" }
            }
            RandomAccessFile(base, "r").use { input ->
                val buffer = ByteArray(64 * 1024)
                for (wanted in release.reuse) {
                    checkpoint(deadline)
                    val entry = zip.getEntry(wanted.name) ?: continue
                    if (entry.isDirectory || entry.generalPurposeBit.usesEncryption() || entry.compressedSize != wanted.length) continue
                    val start = entry.dataOffset
                    require(start >= 0 && start <= input.length() && wanted.length <= input.length()-start)
                    input.seek(start)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var remaining = wanted.length
                    while (remaining > 0) {
                        checkpoint(deadline)
                        val count = minOf(buffer.size.toLong(), remaining).toInt()
                        input.readFully(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        remaining -= count
                    }
                    if (digest.digest().hex() == wanted.sha256) spans += ApkReuseSpan(wanted, start)
                }
            }
        }
        return AppReusePlan(base.canonicalPath, base.length(), release.sha256, release.apk.size, spans).takeIf {
            it.spans.isNotEmpty() && it.downloadBytes < release.apk.size * 4 / 5
        }
    }

    fun apply(base: File, output: File, release: AppRelease, plan: AppReusePlan, reader: AppRangeReader,
        progress: (Int) -> Unit = {}) {
        require(output.canonicalFile != base.canonicalFile)
        var complete = false
        try {
            require(base.canonicalPath == plan.sourcePath && base.length() == plan.sourceSize)
            require(plan.targetSha256 == release.sha256 && plan.targetSize == release.apk.size)
            require(plan.spans.isNotEmpty() && plan.spans.size <= AppReleases.MAX_REUSE_ENTRIES)
            var previousEnd = 0L
            for (span in plan.spans) {
                val entry = span.entry
                require(entry in release.reuse && entry.offset >= previousEnd && entry.offset <= plan.targetSize)
                require(entry.length > 0 && entry.length <= plan.targetSize-entry.offset)
                require(span.sourceOffset >= 0 && span.sourceOffset <= plan.sourceSize && entry.length <= plan.sourceSize-span.sourceOffset)
                previousEnd = entry.offset + entry.length
            }
            require(plan.downloadBytes > 0)
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var written = 0L
            var downloaded = 0L
            var lastPercent = -1
            fun changed() {
                val percent = (downloaded * 100 / plan.downloadBytes).toInt().coerceAtMost(99)
                if (percent != lastPercent) { lastPercent = percent; progress(percent) }
            }
            changed()
            RandomAccessFile(base, "r").use { source ->
                AppUpdateStorage.output(output).use { target ->
                    fun append(bytes: ByteArray, count: Int) {
                        checkpoint(deadline)
                        require(count.toLong() <= plan.targetSize-written)
                        target.write(bytes, 0, count); digest.update(bytes, 0, count); written += count
                    }
                    fun missing(length: Long) {
                        if (length == 0L) return
                        val start = written
                        reader.read(start, length) { input ->
                            val received = AppUpdateRepository.copy(input, length) { bytes, count ->
                                append(bytes, count); downloaded += count; changed()
                            }
                            require(received == length) { "更新区间下载不完整" }
                        }
                        require(written == start+length)
                    }
                    for (span in plan.spans) {
                        checkpoint(deadline)
                        missing(span.entry.offset-written)
                        source.seek(span.sourceOffset)
                        var remaining = span.entry.length
                        while (remaining > 0) {
                            checkpoint(deadline)
                            val count = minOf(buffer.size.toLong(), remaining).toInt()
                            source.readFully(buffer, 0, count); append(buffer, count); remaining -= count
                        }
                    }
                    missing(plan.targetSize-written)
                    require(written == plan.targetSize && digest.digest().hex() == release.sha256) { "合成安装包校验失败" }
                    target.sync()
                }
            }
            complete = true
            progress(100)
        } finally { if (!complete) output.delete() }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
}
