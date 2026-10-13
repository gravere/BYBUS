package com.example.limbuszhcn.selfupdate

import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Replays exact signed APK bytes; ZIP entries are never inflated or recompressed. */
internal object AppUpdateDelta {
    const val MAX_OPERATIONS = 100_000
    private val magic = "BYBDLT01".toByteArray(Charsets.US_ASCII)

    fun matchesBase(base: File, delta: AppDelta): Boolean =
        base.isFile && base.length() == delta.fromSize && AppUpdateRepository.hash(base) == delta.fromSha256

    fun apply(base: File, patch: File, output: File, delta: AppDelta, release: AppRelease,
        progress: (Int) -> Unit = {}) {
        require(output.canonicalFile != base.canonicalFile && output.canonicalFile != patch.canonicalFile)
        var complete = false
        try {
            require(release.apk.size in 1..AppReleases.MAX_APK_BYTES && delta.fromSize in 1..AppReleases.MAX_APK_BYTES)
            require(matchesBase(base, delta)) { "已安装版本与差异补丁不匹配" }
            require(patch.length() == delta.asset.size && AppUpdateRepository.hash(patch) == delta.sha256) { "差异补丁校验失败" }
            val digest = MessageDigest.getInstance("SHA-256")
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
            fun checkpoint() {
                if (Thread.currentThread().isInterrupted) throw CancellationException()
                require(System.nanoTime() < deadline) { "合成安装包超时" }
            }
            patch.inputStream().buffered().use { compressed ->
                DataInputStream(GZIPInputStream(compressed, 64 * 1024)).use { input ->
                    fun bytes(size: Int) = ByteArray(size).also(input::readFully)
                    require(bytes(8).contentEquals(magic)) { "不支持的差异补丁格式" }
                    require(input.readLong() == delta.fromSize && input.readLong() == release.apk.size) { "补丁文件大小不匹配" }
                    require(bytes(32).hex() == delta.fromSha256 && bytes(32).hex() == release.sha256) { "补丁目标版本不匹配" }
                    val count = input.readInt()
                    require(count in 1..MAX_OPERATIONS) { "差异补丁指令数量异常" }
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var lastPercent = -1
                    RandomAccessFile(base, "r").use { source ->
                        output.outputStream().use { target ->
                            repeat(count) {
                                checkpoint()
                                val operation = input.readUnsignedByte()
                                require(operation == 0 || operation == 1) { "未知差异补丁指令" }
                                val offset = if (operation == 0) input.readLong() else 0L
                                val length = input.readLong()
                                require(length > 0 && length <= release.apk.size - total) { "差异补丁超出目标大小" }
                                if (operation == 0) {
                                    require(offset >= 0 && length <= delta.fromSize && offset <= delta.fromSize - length) { "差异补丁读取越界" }
                                    source.seek(offset)
                                }
                                var remaining = length
                                while (remaining > 0) {
                                    checkpoint()
                                    val size = minOf(remaining, buffer.size.toLong()).toInt()
                                    if (operation == 0) source.readFully(buffer, 0, size) else input.readFully(buffer, 0, size)
                                    target.write(buffer, 0, size)
                                    digest.update(buffer, 0, size)
                                    remaining -= size
                                    total += size
                                    val percent = (total * 100 / release.apk.size).toInt()
                                    if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                                }
                            }
                            require(total == release.apk.size && input.read() == -1) { "差异补丁不完整或有多余数据" }
                            require(digest.digest().hex() == release.sha256) { "合成安装包校验失败" }
                            checkpoint()
                            target.fd.sync()
                        }
                    }
                }
            }
            complete = true
        } finally { if (!complete) output.delete() }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
}
