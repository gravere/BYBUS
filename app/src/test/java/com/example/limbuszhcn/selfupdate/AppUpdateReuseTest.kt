package com.example.limbuszhcn.selfupdate

import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppUpdateReuseTest {
    @get:Rule val temporary = TemporaryFolder()
    private val common = ByteArray(360_000).also { java.util.Random(17).nextBytes(it) }
    private val font = ByteArray(180_000).also { java.util.Random(42).nextBytes(it) }

    private fun archive(prefix: Int, changed: Boolean = false, omit: Boolean = false): File = temporary.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name).apply { time = 1_600_000_000_000L }); zip.write(bytes); zip.closeEntry()
            }
            entry("classes.dex", ByteArray(prefix).also { java.util.Random(prefix.toLong()).nextBytes(it) })
            if (!omit) entry("assets/common", if (changed) common.reversedArray() else common)
            entry("assets/font", font)
        }
    }

    private fun release(target: File): AppRelease {
        val entries = mutableListOf<ApkReuseEntry>()
        ZipFile.builder().setFile(target).get().use { zip ->
            val all = zip.entriesInPhysicalOrder
            RandomAccessFile(target, "r").use { file ->
                while (all.hasMoreElements()) {
                    val e = all.nextElement()
                    if (e.compressedSize < AppReleases.MIN_REUSE_BYTES) continue
                    val bytes = ByteArray(e.compressedSize.toInt())
                    file.seek(e.dataOffset); file.readFully(bytes)
                    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    entries += ApkReuseEntry(e.name, e.dataOffset, e.compressedSize, sha)
                }
            }
        }
        return AppRelease("v1.0.14-build.57", "v1.0.14", 57, 26, AppUpdateRepository.hash(target),
            ReleaseAsset(17, "BYBUS.apk", target.length()), "", reuse = entries)
    }

    private fun reader(target: File, ranges: MutableList<Pair<Long, Long>>) = AppRangeReader { offset, length, consume ->
        ranges += offset to length
        RandomAccessFile(target, "r").use {
            it.seek(offset); val bytes = ByteArray(length.toInt()); it.readFully(bytes); consume(ByteArrayInputStream(bytes))
        }
    }

    @Test fun oneTargetDescriptorReconstructsDifferentOlderLayouts() {
        val target = archive(7000); val release = release(target)
        for (prefix in listOf(100, 2100, 11_000)) {
            val base = archive(prefix); val plan = requireNotNull(AppUpdateReuse.plan(base, release))
            val requested = mutableListOf<Pair<Long, Long>>(); val progress = mutableListOf<Int>()
            val output = temporary.newFile()
            AppUpdateReuse.apply(base, output, release, plan, reader(target, requested), progress::add)
            assertArrayEquals(target.readBytes(), output.readBytes())
            assertEquals(plan.downloadBytes, requested.sumOf { it.second })
            assertTrue(plan.downloadBytes < target.length()/10)
            assertEquals(100, progress.last()); assertEquals(progress.sorted(), progress)
        }
    }

    @Test fun changedOrMissingComponentsAreDownloadedWithoutAnotherPatch() {
        val target = archive(7000); val release = release(target)
        for (base in listOf(archive(100, changed = true), archive(100, omit = true))) {
            val plan = requireNotNull(AppUpdateReuse.plan(base, release))
            assertEquals(listOf("assets/font"), plan.spans.map { it.entry.name })
            val output = temporary.newFile()
            AppUpdateReuse.apply(base, output, release, plan, reader(target, mutableListOf()))
            assertArrayEquals(target.readBytes(), output.readBytes())
        }
        val unavailable = release.copy(reuse = release.reuse.map { it.copy(sha256 = "0".repeat(64)) })
        assertNull(AppUpdateReuse.plan(archive(100), unavailable))
    }

    @Test fun rejectsWrongStatusRangesLengthsAndEncoding() {
        val range = AppByteRange(20, 30, 100)
        assertEquals("bytes=20-49", range.header)
        range.validate(206, "bytes 20-49/100", 30, null)
        range.validate(206, "bytes 20-49/100", -1, "identity")
        val invalid = listOf(
            { range.validate(200, "bytes 20-49/100", 30, null) },
            { range.validate(206, "bytes 21-50/100", 30, null) },
            { range.validate(206, "bytes 20-49/101", 30, null) },
            { range.validate(206, null, 30, null) },
            { range.validate(206, "bytes 20-49/100", 31, null) },
            { range.validate(206, "bytes 20-49/100", 30, "gzip") },
            { AppByteRange(Long.MAX_VALUE, 30, 100); Unit })
        invalid.forEach { check -> assertThrows(IllegalArgumentException::class.java) { check() } }
    }

    @Test fun badRangeFallsBackOnceAndVerifiedCacheAvoidsRequests() {
        val target = archive(7000); val release = release(target); val base = archive(100)
        val plan = requireNotNull(AppUpdateReuse.plan(base, release))
        val cache = temporary.newFolder(); var completeReads = 0; var rangeReads = 0
        val repo = AppUpdateRepository(cache, AppUpdateTransport { _, _, consume ->
            completeReads++; target.inputStream().use { consume(it, target.length()) }
        }, { AppRangeReader { _, length, consume ->
            rangeReads++; consume(ByteArrayInputStream(ByteArray(length.toInt())))
        } })
        assertArrayEquals(target.readBytes(), repo.download(release, base, null, plan) {}.readBytes())
        val requests = rangeReads
        assertEquals(1, completeReads); assertTrue(requests > 0)
        repo.download(release, base, null, plan) {}
        assertEquals(requests, rangeReads); assertEquals(1, completeReads)
        assertEquals(listOf("BYBUS.apk"), cache.list()!!.toList())
    }

    @Test fun cancelledAndRateLimitedRangesNeverFallBackToFullDownload() {
        val target = archive(7000); val release = release(target); val base = archive(100)
        val plan = requireNotNull(AppUpdateReuse.plan(base, release))
        for (failure in listOf(CancellationException(), UpdateHttpException(429, 60_000))) {
            val cache = temporary.newFolder(); var calls = 0
            val repo = AppUpdateRepository(cache, AppUpdateTransport { _, _, _ -> fail("Unexpected full download") },
                { AppRangeReader { _, _, _ -> calls++; throw failure } })
            assertThrows(failure.javaClass) { repo.download(release, base, null, plan) {} }
            assertEquals(1, calls); assertTrue(cache.list()!!.isEmpty())
        }
    }

    @Test fun staleBaseAndShortOrOversizedNetworkDataCannotProduceAnApk() {
        val target = archive(7000); val release = release(target); val base = archive(100)
        val plan = requireNotNull(AppUpdateReuse.plan(base, release))
        RandomAccessFile(base, "rw").use {
            val offset = plan.spans.first().sourceOffset
            it.seek(offset); val previous = it.readUnsignedByte(); it.seek(offset); it.writeByte(previous xor 1)
        }
        val output = temporary.newFile()
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateReuse.apply(base, output, release, plan, reader(target, mutableListOf()))
        }
        assertFalse(output.exists())
        for (extra in listOf(-1, 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                AppUpdateReuse.apply(base, output, release, plan, AppRangeReader { _, length, consume ->
                    consume(ByteArrayInputStream(ByteArray(length.toInt()+extra)))
                })
            }
            assertFalse(output.exists())
        }
    }
}
