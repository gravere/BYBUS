package com.example.limbuszhcn.selfupdate

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class AppUpdateCheckPolicyTest {
    private val now = 1_800_000_000_000L
    private fun date(time: Long) = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC))

    @Test fun explicitRefreshBypassesLocalSuccessCacheAndAutomaticCacheExpires() {
        var elapsed = 10_000L
        val policy = AppUpdateCheckPolicy({ elapsed }, { now })
        policy.succeeded()
        assertEquals(UpdateCheckDecision.Cached, policy.beforeCheck(false))
        assertEquals(UpdateCheckDecision.Check, policy.beforeCheck(true))
        elapsed += 60_000
        assertEquals(UpdateCheckDecision.Check, policy.beforeCheck(false))
    }

    @Test fun ordinaryHttpErrorsNeverTurnResetHeadersIntoAWait() {
        for (status in listOf(403, 404, 500, 503)) {
            val error = UpdateHttpException.fromResponse(status, "59", ((now + 3_600_000) / 1000).toString(),
                null, date(now), "request failed", now)
            assertEquals(0L, error.retryAfterMillis)
            val policy = AppUpdateCheckPolicy({ 0 }, { now })
            policy.failed(error)
            assertEquals(UpdateCheckDecision.Check, policy.beforeCheck(true))
            assertFalse(error.message!!.contains("限制了请求"))
        }
    }

    @Test fun confirmedPrimaryLimitUsesServerClockInsteadOfIncorrectDeviceClock() {
        val error = UpdateHttpException.fromResponse(403, "0", ((now + 120_000) / 1000).toString(),
            null, date(now), "API rate limit exceeded", now - 3_600_000)
        assertEquals(120_000L, error.retryAfterMillis)
        val policy = AppUpdateCheckPolicy({ 1000 }, { now })
        policy.failed(error)
        assertEquals(UpdateCheckDecision.Wait, policy.beforeCheck(true))
        assertEquals(UpdateCheckDecision.Wait, policy.beforeCheck(false))
        assertEquals(121_000L, policy.retryAtElapsed)
    }

    @Test fun secondaryLimitDoesNotWaitForUnusedPrimaryQuotaReset() {
        val reset = ((now + 3_600_000) / 1000).toString()
        assertEquals(90_000L, UpdateHttpException.fromResponse(403, "50", reset, "90", date(now), "", now).retryAfterMillis)
        assertEquals(60_000L, UpdateHttpException.fromResponse(403, "50", reset, null, date(now),
            "You have exceeded a secondary rate limit", now).retryAfterMillis)
        assertEquals(95_000L, UpdateHttpException.fromResponse(429, "50", reset, date(now + 95_000), date(now), "", now).retryAfterMillis)
    }

    @Test fun malformedAndOverflowingHeadersCannotCreateAnUnboundedLock() {
        for (value in listOf("9223372036854775807", "-1", "not-a-date")) {
            val error = UpdateHttpException.fromResponse(429, "0", value, value, null, "", now)
            assertEquals(60_000L, error.retryAfterMillis)
        }
        assertEquals(0L, UpdateHttpException.fromResponse(403, "5", null, "invalid", null, "forbidden", now).retryAfterMillis)
    }

    @Test fun waitSurvivesRestartButExpiresAndSuccessfulChecksClearPersistence() {
        var elapsed = 2000L
        var wall = now
        var saved: UpdateRateLimitWindow? = null
        val policy = AppUpdateCheckPolicy({ elapsed }, { wall }, persist = { saved = it })
        policy.failed(UpdateHttpException(429, 120_000))
        assertEquals(UpdateRateLimitWindow(now, now + 120_000), saved)
        wall += 30_000
        val restarted = AppUpdateCheckPolicy({ elapsed }, { wall }, saved) { saved = it }
        assertEquals(92_000L, restarted.retryAtElapsed)
        // A wall-clock change while running must not prolong or bypass a server wait.
        wall -= 3_600_000
        assertEquals(UpdateCheckDecision.Wait, restarted.beforeCheck(true))
        elapsed += 90_000
        assertEquals(UpdateCheckDecision.Check, restarted.beforeCheck(true))
        assertNull(saved)
        restarted.failed(UpdateHttpException(429, 60_000))
        restarted.succeeded()
        assertNull(saved); assertNull(restarted.retryAtElapsed)
    }

    @Test fun expiredImpossibleOrClockReversedSavedWaitsAreDiscarded() {
        val invalid = listOf(UpdateRateLimitWindow(now - 120_000, now - 1),
            UpdateRateLimitWindow(now, now + UpdateHttpException.MAX_WAIT + 1),
            UpdateRateLimitWindow(now + 10_000, now + 20_000), UpdateRateLimitWindow(-1, now))
        invalid.forEach { saved ->
            var cleared = false
            val policy = AppUpdateCheckPolicy({ 0 }, { now }, saved) { cleared = it == null }
            assertEquals(UpdateCheckDecision.Check, policy.beforeCheck(true)); assertTrue(cleared)
        }
    }

    @Test fun networkFailureCanRetryImmediatelyAndCancellationDoesNotWriteAWait() {
        var writes = 0
        val policy = AppUpdateCheckPolicy({ 0 }, { now }, persist = { writes++ })
        policy.succeeded(); policy.failed(java.io.IOException("offline"))
        assertEquals(UpdateCheckDecision.Check, policy.beforeCheck(false))
        val previousWrites = writes
        policy.failed(java.util.concurrent.CancellationException())
        assertEquals(previousWrites, writes)
        assertEquals(UpdateCheckDecision.Check, policy.beforeCheck(true))
    }
}
