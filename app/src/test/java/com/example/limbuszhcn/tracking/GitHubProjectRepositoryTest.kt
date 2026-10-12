package com.example.limbuszhcn.tracking

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.time.Instant

class GitHubProjectRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val now = Instant.parse("2026-09-24T06:00:00Z").toEpochMilli()
    private val json = """{"tag_name":"2026092102","draft":false,"prerelease":false,"published_at":"2026-09-21T04:27:24Z","html_url":"${ZeroGitHub.RELEASES}/tag/2026092102"}"""

    private val downloadable get() = org.json.JSONObject(json).put("body", "not retained")
        .put("assets", org.json.JSONArray().put(org.json.JSONObject().apply {
            put("name", "LimbusLocalize_2026092102.7z")
            put("browser_download_url", "${ZeroGitHub.RELEASES}/download/2026092102/LimbusLocalize_2026092102.7z")
            put("digest", "sha256:" + "a".repeat(64))
        })).toString()

    @Test fun launchSharesRecentMetadataButDoesNotReuseTheFifteenMinuteDisplayCache() {
        var calls = 0
        var tick = 0L
        val file = folder.root.toPath().resolve("github.json")
        val repository = GitHubProjectRepository(file, { calls++; downloadable }, { now + tick }, { tick })
        val observed = mutableListOf<GitHubTrackerState>()
        val unsubscribe = repository.observe { observed += it }
        repository.refresh()
        val release = com.example.limbuszhcn.update.GitHubTranslationReleaseClient(ZeroGitHub.API)
            .parseRelease(repository.releaseForLaunch())
        assertEquals(2026092102L, release.version)
        assertEquals(1, calls)
        tick = 16_000
        repository.releaseForLaunch()
        assertEquals(2, calls)
        assertEquals(now + tick, observed.last().snapshot!!.fetchedAtMillis)
        assertFalse(String(Files.readAllBytes(file)).contains("not retained"))
        val notifications = observed.size
        unsubscribe()
        tick += 16_000
        repository.releaseForLaunch()
        assertEquals(notifications, observed.size)
    }

    @Test fun launchAndTrackerShareTheSamePersistentServerCooldown() {
        var wall = now
        var calls = 0
        val file = folder.root.toPath().resolve("github.json")
        val deadline = wall + 3_600_000
        val repository = GitHubProjectRepository(file,
            { calls++; throw ProjectFetchException(ProjectIssue.RateLimited, deadline) }, { wall })
        repository.refresh(true)
        assertEquals(ProjectIssue.RateLimited, assertThrows(ProjectFetchException::class.java) {
            repository.releaseForLaunch()
        }.issue)
        val restarted = GitHubProjectRepository(file, { calls++; downloadable }, { wall })
        assertThrows(ProjectFetchException::class.java) { restarted.releaseForLaunch() }
        assertEquals(1, calls)
        wall = deadline
        assertTrue(restarted.releaseForLaunch().contains("LimbusLocalize_2026092102.7z"))
        assertEquals(2, calls)
        assertNull(restarted.cached().retryAtMillis)
    }

    @Test fun concurrentLaunchJoinsTheTrackerRequestWithoutAnotherNetworkCall() {
        val started = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val tick = java.util.concurrent.atomic.AtomicLong()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val repository = GitHubProjectRepository(folder.root.toPath().resolve("github.json"), {
            calls.incrementAndGet(); started.countDown()
            assertTrue(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
            tick.set(40_000) // A slow request is still shared after it completes.
            downloadable
        }, { now }, { tick.get() })
        try {
            val tracker = pool.submit<GitHubTrackerState> { repository.refresh() }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val launch = pool.submit<String> { repository.releaseForLaunch() }
            finish.countDown()
            assertNotNull(tracker.get(5, java.util.concurrent.TimeUnit.SECONDS).snapshot)
            assertTrue(launch.get(5, java.util.concurrent.TimeUnit.SECONDS).contains("assets"))
            assertEquals(1, calls.get())
        } finally { finish.countDown(); pool.shutdownNow() }
    }

    @Test fun newAndExistingInstallsDefaultToGithubUnlessExplicitlySelected() {
        assertEquals(ProjectSource.GitHub, ProjectSource.fromPreference(null))
        assertEquals(ProjectSource.GitHub, ProjectSource.fromPreference("obsolete"))
        assertEquals(ProjectSource.ParaTranz, ProjectSource.fromPreference("paratranz"))
        assertEquals(ProjectSource.GitHub, ProjectTrackingState().source)
    }

    @Test fun malformedAssetFieldsCannotSilentlyRemoveChecksumValidationOrReplaceGoodCache() {
        val file = folder.root.toPath().resolve("github.json")
        GitHubProjectRepository(file, { downloadable }, { now }).refresh()
        val before = Files.readAllBytes(file)
        for (field in listOf("digest", "browser_download_url")) {
            val bad = org.json.JSONObject(downloadable)
            bad.getJSONArray("assets").getJSONObject(0).put(field, org.json.JSONObject().put("invalid", true))
            val repository = GitHubProjectRepository(file, { bad.toString() }, { now + 1_000_000 })
            assertEquals(ProjectIssue.InvalidData, assertThrows(ProjectFetchException::class.java) {
                repository.releaseForLaunch()
            }.issue)
            assertArrayEquals(before, Files.readAllBytes(file))
        }
    }

    @Test fun rejectsOtherRepositoriesDraftsPrereleasesAndInvalidDates() {
        for (invalid in listOf(json.replace("LocalizeLimbusCompany", "unrelated"),
            json.replace("\"draft\":false", "\"draft\":true"),
            json.replace("\"prerelease\":false", "\"prerelease\":true"),
            json.replace("2026-09-21", "2099-09-21"), json.replace("2026092102", "latest"),
            json + "{}")) {
            assertThrows(Exception::class.java) { GitHubSnapshotCodec.parseResponse(invalid, now) }
        }
    }

    @Test fun releaseCacheSurvivesRestartWithoutNetworkAndHasNoProgressPercentage() {
        val file = folder.root.toPath().resolve("github.json")
        GitHubProjectRepository(file, { json }, { now }).refresh()
        var calls = 0
        val result = GitHubProjectRepository(file, { calls++; throw IOException() }, { now + 60_000 }).refresh()
        assertEquals(0, calls)
        assertEquals("2026092102", result.snapshot?.tag)
        assertEquals(Instant.parse("2026-09-21T04:27:24Z").toEpochMilli(), result.snapshot?.publishedAtMillis)
        assertFalse(String(Files.readAllBytes(file)).contains("translated"))
    }

    @Test fun networkErrorsAndInvalidResponsesKeepTheLastGoodRelease() {
        val file = folder.root.toPath().resolve("github.json")
        GitHubProjectRepository(file, { json }, { now }).refresh()
        val before = Files.readAllBytes(file)
        val failed = GitHubProjectRepository(file, { throw ProjectFetchException(ProjectIssue.RateLimited) },
            { now + 1_000_000 }).refresh()
        assertEquals(ProjectIssue.RateLimited, failed.issue)
        assertEquals("2026092102", failed.snapshot?.tag)
        val invalid = GitHubProjectRepository(file, { "{}" }, { now + 2_000_000 }).refresh()
        assertEquals(ProjectIssue.InvalidData, invalid.issue)
        assertEquals("2026092102", invalid.snapshot?.tag)
        assertArrayEquals(before, Files.readAllBytes(file))
    }

    @Test fun corruptFutureAndWrongSourceCachesRecover() {
        val file = folder.root.toPath().resolve("github.json")
        for (bad in listOf("partial JSON", """{"schema":1,"fetchedAt":$now,"project":{}}""",
            GitHubSnapshotCodec.encode(GitHubSnapshotCodec.parseResponse(json, now + 600_000)))) {
            Files.write(file, bad.toByteArray())
            val repository = GitHubProjectRepository(file, { json }, { now })
            assertNull(repository.cached().snapshot)
            assertEquals("2026092102", repository.refresh().snapshot?.tag)
        }
    }

    @Test fun repeatedManualRefreshIsThrottledEvenAfterFailure() {
        var calls = 0
        var tick = 0L
        val repository = GitHubProjectRepository(folder.root.toPath().resolve("github.json"),
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

    @Test fun serverDeadlineSurvivesRestartAndManualRefreshCannotSpendMoreQuota() {
        val file = folder.root.toPath().resolve("github.json")
        GitHubProjectRepository(file, { json }, { now }).refresh()
        val goodCache = Files.readAllBytes(file)
        var wall = now + 1_000_000
        var tick = 0L
        var calls = 0
        val deadline = wall + 3_600_000
        val repository = GitHubProjectRepository(file,
            { calls++; throw ProjectFetchException(ProjectIssue.RateLimited, deadline) }, { wall }, { tick })
        assertEquals(deadline, repository.refresh().retryAtMillis)
        tick += 30_000; wall += 30_000
        repository.refresh(true)
        assertEquals(1, calls)
        assertArrayEquals(goodCache, Files.readAllBytes(file))
        val restarted = GitHubProjectRepository(file, { calls++; json }, { wall }, { tick })
        assertEquals("2026092102", restarted.refresh(true).snapshot?.tag)
        assertEquals(deadline, restarted.cached().retryAtMillis)
        assertEquals(1, calls)
        wall = deadline; tick += 3_600_000
        assertNull(restarted.refresh(true).issue)
        assertEquals(2, calls)
        assertFalse(Files.exists(file.resolveSibling("github.json.retry")))
    }

    @Test fun missingRetryHeadersBackOffAcrossRestartsWithoutRewritingGoodData() {
        val file = folder.root.toPath().resolve("github.json")
        var wall = now
        var calls = 0
        fun repository() = GitHubProjectRepository(file,
            { calls++; throw ProjectFetchException(ProjectIssue.RateLimited) }, { wall })
        assertEquals(wall + 60_000, repository().refresh(true).retryAtMillis)
        assertEquals(wall + 60_000, repository().refresh(true).retryAtMillis)
        assertEquals(1, calls)
        wall += 60_000
        assertEquals(wall + 120_000, repository().refresh(true).retryAtMillis)
        assertEquals(2, calls)
        wall += 120_000
        assertEquals(wall + 240_000, repository().refresh(true).retryAtMillis)
        assertEquals(3, calls)
        assertFalse(Files.exists(file))
    }

    @Test fun invalidRetryCacheCannotDisableRefresh() {
        val file = folder.root.toPath().resolve("github.json")
        val retry = file.resolveSibling("github.json.retry")
        for (bad in listOf("partial", """{"schema":1,"retryAt":${now + 999_999_999},"attempts":1}""",
            """{"schema":1,"retryAt":${now + 60_000},"attempts":99}""")) {
            Files.write(retry, bad.toByteArray())
            var calls = 0
            assertNull(GitHubProjectRepository(file, { calls++; json }, { now }).refresh(true).issue)
            assertEquals(1, calls)
            assertFalse(Files.exists(retry))
        }
    }

    @Test fun httpSignalsDistinguishQuotasAndRespectTheLaterSafeDeadline() {
        val reset = ((now + 3_600_000) / 1000).toString()
        val primary = projectHttpFailure(403, "0", reset, "120", now)
        assertEquals(ProjectIssue.RateLimited, primary.issue)
        assertEquals(now + 3_601_000, primary.retryAtMillis)
        assertEquals(now + 121_000, projectHttpFailure(429, null, null, "120", now).retryAtMillis)
        assertEquals(now + 121_000, projectHttpFailure(403, "5", null,
            "Thu, 24 Sep 2026 06:02:00 GMT", now).retryAtMillis)
        assertEquals(ProjectIssue.Restricted, projectHttpFailure(403, "5", null, null, now).issue)
        assertEquals(ProjectIssue.Restricted, projectHttpFailure(401, "0", reset, null, now).issue)
        assertEquals(ProjectIssue.Unavailable, projectHttpFailure(404, null, null, null, now).issue)
        for (bad in listOf("invalid", "-100", Long.MAX_VALUE.toString())) {
            assertNull(projectHttpFailure(429, null, bad, bad, now).retryAtMillis)
        }
    }
}
