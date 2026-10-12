package com.example.limbuszhcn.tracking

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files

class ParatranzRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val json = """{"id":6860,"dest":"zh-cn","name":"LLC","stats":{"total":100,"translated":90,"checked":50,"reviewed":20,"modifiedAt":"2026-09-22T06:00:00Z"}}"""
    private val now = 1_790_000_000_000L

    @Test fun countersAreIndependentNotAddedTogether() {
        val snapshot = ProjectSnapshotCodec.parseResponse(json, now)
        assertEquals(.9f, snapshot.translatedFraction!!, .0001f)
        assertEquals(50L, snapshot.checked)
        assertEquals(20L, snapshot.reviewed)
    }

    @Test fun frozenMirrorAndWrongLanguageAreRejected() {
        for (bad in listOf(json.replace("6860", "20115"), json.replace("zh-cn", "zh-tw"),
            json.replace("\"stats\":{", "\"stats\":{\"id\":20115,"))) {
            assertThrows(IllegalArgumentException::class.java) { ProjectSnapshotCodec.parseResponse(bad, now) }
        }
    }

    @Test fun corruptCountersCannotLookLikeValidProgress() {
        for (value in listOf("-1", "101", "90.5", "\"90\"", "999999999999999999")) {
            assertThrows(IllegalArgumentException::class.java) {
                ProjectSnapshotCodec.parseResponse(json.replace("\"translated\":90", "\"translated\":$value"), now)
            }
        }
    }

    @Test fun missingCountersAndEmptyProjectsDoNotInventCompletion() {
        val empty = """{"id":6860,"dest":"zh-cn","name":"LLC","stats":{"total":0,"translated":0}}"""
        val snapshot = ProjectSnapshotCodec.parseResponse(empty, now)
        assertNull(snapshot.translatedFraction)
        assertNull(snapshot.checked)
        assertNull(snapshot.reviewed)
    }

    @Test fun outOfRangeTimestampCannotCrashTheProjectPanel() {
        val malformed = json.replace("2026-09-22T06:00:00Z", "+999999999-12-31T23:59:59Z")
        val snapshot = ProjectSnapshotCodec.parseResponse(malformed, now)
        assertEquals(90L, snapshot.translated)
        assertNull(snapshot.modifiedAt)
        assertNull(ProjectSnapshotCodec.decodeCache(ProjectSnapshotCodec.encode(snapshot), now).modifiedAt)
    }

    @Test fun successfulSnapshotSurvivesRestartAndAvoidsFreshNetworkRequest() {
        val file = folder.root.toPath().resolve("project.json")
        ParatranzRepository(file, { json }, { now }).refresh()
        var calls = 0
        val cached = ParatranzRepository(file, { calls++; throw IOException() }, { now + 60_000 }).refresh()
        assertEquals(0, calls)
        assertEquals(90L, cached.snapshot?.translated)
        assertNull(cached.issue)
    }

    @Test fun failedRefreshKeepsCacheAndErrorIsExplicit() {
        val file = folder.root.toPath().resolve("project.json")
        ParatranzRepository(file, { json }, { now }).refresh()
        val before = String(Files.readAllBytes(file), Charsets.UTF_8)
        val failed = ParatranzRepository(file, { throw ProjectFetchException(ProjectIssue.Unavailable) },
            { now + 1_000_000 }).refresh()
        assertEquals(ProjectIssue.Unavailable, failed.issue)
        assertEquals(now, failed.snapshot?.fetchedAtMillis)
        assertEquals(before, String(Files.readAllBytes(file), Charsets.UTF_8))
    }

    @Test fun invalidResponseCannotOverwriteLastGoodSnapshot() {
        val file = folder.root.toPath().resolve("project.json")
        ParatranzRepository(file, { json }, { now }).refresh()
        val result = ParatranzRepository(file, { json.replace("6860", "20115") }, { now + 1_000_000 }).refresh()
        assertEquals(ProjectIssue.InvalidData, result.issue)
        assertEquals(90L, result.snapshot?.translated)
    }

    @Test fun repeatedManualRequestsAreThrottledIncludingFailures() {
        var calls = 0
        var tick = 0L
        val repository = ParatranzRepository(folder.root.toPath().resolve("project.json"),
            { calls++; throw IOException() }, { now }, { tick })
        repository.refresh(true)
        repository.refresh(true)
        tick = 14_999
        repository.refresh(true)
        assertEquals(1, calls)
        tick = 15_000
        repository.refresh(true)
        assertEquals(2, calls)
    }

    @Test fun corruptAndFutureCachesAreIgnoredAndCanRecover() {
        val file = folder.root.toPath().resolve("project.json")
        for (cache in listOf("partial JSON", ProjectSnapshotCodec.encode(ProjectSnapshotCodec.parseResponse(json, now + 600_000)))) {
            Files.write(file, cache.toByteArray(Charsets.UTF_8))
            val repository = ParatranzRepository(file, { json }, { now })
            assertNull(repository.cached().snapshot)
            assertEquals(90L, repository.refresh().snapshot?.translated)
        }
    }
}
