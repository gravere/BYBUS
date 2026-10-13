package com.example.limbuszhcn.selfupdate

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream

class AppUpdateDeltaTest {
    @get:Rule val temporary = TemporaryFolder()

    private data class Fixture(val base: File, val patch: File, val target: File, val delta: AppDelta,
        val release: AppRelease, val raw: ByteArray)

    private fun fixture(): Fixture {
        val base = temporary.newFile()
        val bytes = ByteArray(180_000).also { java.util.Random(42).nextBytes(it) }
        base.writeBytes(bytes)
        val target = temporary.newFile().apply { writeBytes("new-header".toByteArray() + bytes.copyOfRange(700, 179_500) + "tail".toByteArray()) }
        val raw = ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { output ->
                output.writeBytes("BYBDLT01")
                output.writeLong(base.length()); output.writeLong(target.length())
                output.write(unhex(AppUpdateRepository.hash(base))); output.write(unhex(AppUpdateRepository.hash(target)))
                output.writeInt(3)
                output.writeByte(1); output.writeLong(10); output.writeBytes("new-header")
                output.writeByte(0); output.writeLong(700); output.writeLong(178_800)
                output.writeByte(1); output.writeLong(4); output.writeBytes("tail")
            }
        }.toByteArray()
        val patch = temporary.newFile().apply { writeBytes(gzip(raw)) }
        val delta = AppDelta(48, base.length(), AppUpdateRepository.hash(base), AppUpdateRepository.hash(patch), ReleaseAsset(2, "BYBUS-update-48-to-49.delta", patch.length()))
        val release = AppRelease("v1.0.6-build.49", "v1.0.6", 49, 26, AppUpdateRepository.hash(target), ReleaseAsset(1, "BYBUS.apk", target.length()), "", listOf(delta))
        return Fixture(base, patch, target, delta, release, raw)
    }

    private fun unhex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { result -> GZIPOutputStream(result).use { it.write(bytes) } }.toByteArray()
    private fun patched(f: Fixture, bytes: ByteArray): Fixture {
        f.patch.writeBytes(bytes)
        val delta = f.delta.copy(sha256 = AppUpdateRepository.hash(f.patch), asset = f.delta.asset.copy(size = bytes.size.toLong()))
        return f.copy(delta = delta, release = f.release.copy(deltas = listOf(delta)))
    }
    private fun rejects(f: Fixture) {
        val output = temporary.newFile()
        assertThrows(Exception::class.java) { AppUpdateDelta.apply(f.base, f.patch, output, f.delta, f.release) }
        assertFalse("Failed output must be removed", output.exists())
    }

    @Test fun reconstructsExactBytesAcrossBufferBoundaries() {
        val f = fixture(); val output = temporary.newFile(); val progress = mutableListOf<Int>()
        AppUpdateDelta.apply(f.base, f.patch, output, f.delta, f.release, progress::add)
        assertArrayEquals(f.target.readBytes(), output.readBytes())
        assertEquals(100, progress.last()); assertEquals(progress.sorted(), progress)
        assertTrue("Unchanged bytes must not travel in the patch", f.patch.length() < f.target.length() / 20)
    }

    @Test fun rejectsWrongBaseAndWrongTargetHashes() {
        val f = fixture()
        f.base.writeBytes(f.base.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() })
        rejects(f)
        val other = fixture(); rejects(other.copy(release = other.release.copy(sha256 = "0".repeat(64))))
    }

    @Test fun boundsCommandsRangesAndExpandedOutput() {
        val f = fixture()
        // Header length 92; first DATA command is 19 bytes; COPY offset starts at 112.
        val invalid = listOf(
            f.raw.clone().also { java.nio.ByteBuffer.wrap(it).putInt(88, AppUpdateDelta.MAX_OPERATIONS + 1) },
            f.raw.clone().also { it[92] = 7 },
            f.raw.clone().also { java.nio.ByteBuffer.wrap(it).putLong(93, Long.MAX_VALUE) },
            f.raw.clone().also { java.nio.ByteBuffer.wrap(it).putLong(112, Long.MAX_VALUE) },
            f.raw.clone().also { java.nio.ByteBuffer.wrap(it).putLong(120, -1) },
            f.raw + byteArrayOf(1))
        invalid.forEach { rejects(patched(f, gzip(it))) }
    }

    @Test fun rejectsTruncatedCompressedDataAndHashMismatch() {
        val f = fixture()
        rejects(patched(f, gzip(f.raw.copyOf(f.raw.size - 2))))
        val bytes = gzip(f.raw)
        rejects(patched(f, bytes.copyOf(bytes.size - 4)))
        rejects(patched(f, ByteArray(20)))
        rejects(f.copy(delta = f.delta.copy(sha256 = "0".repeat(64))))
    }

    @Test fun choosesPatchOnlyForExactInstalledBuildAndBytes() {
        val f = fixture(); val repo = AppUpdateRepository(temporary.newFolder())
        assertEquals(f.delta, repo.selectDelta(f.release, 48, f.base))
        assertNull(repo.selectDelta(f.release, 47, f.base))
        f.base.writeBytes(ByteArray(f.base.length().toInt()))
        assertNull(repo.selectDelta(f.release, 48, f.base))
    }

    @Test fun downloadsPatchThenReusesVerifiedCacheWithoutNetwork() {
        val f = fixture(); val cache = temporary.newFolder(); val requested = mutableListOf<String>()
        val repo = AppUpdateRepository(cache, AppUpdateTransport { url, _, consume ->
            requested += url
            assertEquals(f.delta.asset.url, url)
            consume(ByteArrayInputStream(f.patch.readBytes()), f.patch.length())
        })
        val stages = mutableListOf<AppUpdateProgress>()
        val result = repo.download(f.release, f.base, f.delta, stages::add)
        assertArrayEquals(f.target.readBytes(), result.readBytes())
        assertTrue(stages.any { it.message == "正在合成安装包" && it.percent == 100 })
        assertEquals(result, repo.download(f.release, f.base, f.delta) {})
        assertEquals(1, requested.size)
        assertEquals(listOf("BYBUS.apk"), cache.list()!!.toList())
    }

    @Test fun corruptPatchFallsBackExactlyOnceAndCleansTemporaryFiles() {
        val f = fixture(); val cache = temporary.newFolder(); val requested = mutableListOf<String>()
        val repo = AppUpdateRepository(cache, AppUpdateTransport { url, _, consume ->
            requested += url
            val bytes = if (url == f.delta.asset.url) ByteArray(f.patch.length().toInt()) else f.target.readBytes()
            consume(ByteArrayInputStream(bytes), bytes.size.toLong())
        })
        val stages = mutableListOf<AppUpdateProgress>()
        assertArrayEquals(f.target.readBytes(), repo.download(f.release, f.base, f.delta, stages::add).readBytes())
        assertEquals(listOf(f.delta.asset.url, f.release.apk.url), requested)
        assertTrue(stages.any { it.delta == null && it.message.contains("完整包") })
        assertEquals(listOf("BYBUS.apk"), cache.list()!!.toList())
    }

    @Test fun reconstructionFailureAlsoFallsBackAndFailedFullDownloadLeavesNoApk() {
        val original = fixture()
        val f = patched(original, gzip(original.raw.clone().also { it[92] = 9 }))
        val cache = temporary.newFolder(); val requested = mutableListOf<String>()
        val repo = AppUpdateRepository(cache, AppUpdateTransport { url, _, consume ->
            requested += url
            val bytes = if (url == f.delta.asset.url) f.patch.readBytes() else byteArrayOf(1)
            consume(ByteArrayInputStream(bytes), bytes.size.toLong())
        })
        assertThrows(Exception::class.java) { repo.download(f.release, f.base, f.delta) {} }
        assertEquals(listOf(f.delta.asset.url, f.release.apk.url), requested)
        assertTrue(cache.list()!!.isEmpty())
    }

    @Test fun cancellationDuringReconstructionNeverDownloadsFullApk() {
        val f = fixture(); val cache = temporary.newFolder(); var requests = 0
        val repo = AppUpdateRepository(cache, AppUpdateTransport { _, _, consume ->
            requests++; consume(ByteArrayInputStream(f.patch.readBytes()), f.patch.length())
        })
        try {
            assertThrows(CancellationException::class.java) {
                repo.download(f.release, f.base, f.delta) { if (it.message == "正在合成安装包") Thread.currentThread().interrupt() }
            }
            assertEquals(1, requests); assertTrue(cache.list()!!.isEmpty())
        } finally { Thread.interrupted() }
    }

    @Test fun noPatchDownloadsFullApkAndClearsStalePartialFiles() {
        val f = fixture(); val cache = temporary.newFolder()
        File(cache, "update.delta.part").writeText("stale")
        File(cache, "download.part").writeText("stale")
        val repo = AppUpdateRepository(cache, AppUpdateTransport { url, _, consume ->
            assertEquals(f.release.apk.url, url); consume(ByteArrayInputStream(f.target.readBytes()), f.target.length())
        })
        assertArrayEquals(f.target.readBytes(), repo.download(f.release, f.base, null) {}.readBytes())
        repo.clear(); assertTrue(cache.list()!!.isEmpty())
    }
}
