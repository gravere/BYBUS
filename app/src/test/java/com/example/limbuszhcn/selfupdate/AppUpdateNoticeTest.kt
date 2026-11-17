package com.example.limbuszhcn.selfupdate

import org.junit.Assert.*
import org.junit.Test

class AppUpdateNoticeTest {
    @Test fun resumedTaskIsANewOpeningButInternalActivityAndRotationAreNot() {
        val foreground = AppForegroundOpening()
        assertFalse(foreground.started())
        foreground.stopped(false); assertTrue(foreground.started())
        assertFalse(foreground.started())
        foreground.launchedActivity(); foreground.stopped(false); assertFalse(foreground.started())
        foreground.stopped(false); assertTrue(foreground.started())
        foreground.stopped(true); assertFalse(foreground.started())
    }
    private val now = 1_800_000_000_000L

    @Test fun restartRetainsNoticeAndInstallingThatBuildClearsIt() {
        val saved = AppUpdateNotice().checked(59, now)
        val restored = AppUpdateNotice(saved.versionCode, saved.checkedAt, saved.attemptedAt)
        assertTrue(restored.hasNewer(58))
        assertFalse(restored.hasNewer(59))
        assertFalse(restored.hasNewer(60))
        assertTrue(AppUpdateState(58, knownVersionCode = restored.versionCode).updateAvailable)
        assertFalse(AppUpdateState(59, knownVersionCode = restored.versionCode).updateAvailable)
    }

    @Test fun ongoingForegroundSessionReusesSuccessfulChecksForSixHours() {
        assertTrue(AppUpdateNotice().shouldCheck(now))
        val saved = AppUpdateNotice().checked(59, now)
        assertFalse(saved.shouldCheck(now + AppUpdateNotice.INTERVAL - 1))
        assertTrue(saved.shouldCheck(now + AppUpdateNotice.INTERVAL))
        assertEquals(AppUpdateNotice.INTERVAL - 1000, saved.nextCheckDelay(now + 1000))
    }

    @Test fun failedAttemptKeepsNoticeAndWaitsFifteenMinutes() {
        val failed = AppUpdateNotice(59).attempted(now)
        assertTrue(failed.hasNewer(58))
        assertFalse(failed.shouldCheck(now + AppUpdateNotice.RETRY_INTERVAL - 1))
        assertTrue(failed.shouldCheck(now + AppUpdateNotice.RETRY_INTERVAL))
        assertEquals(AppUpdateNotice.RETRY_INTERVAL, failed.nextCheckDelay(now))
    }

    @Test fun withdrawnReleaseClearsStaleNoticeAfterSuccessfulCheck() {
        val previous = AppUpdateNotice().checked(59, now)
        assertFalse(previous.checked(58, now + 1000).hasNewer(58))
    }

    @Test fun ClockRollbackDoesNotLockChecksIndefinitely() {
        assertTrue(AppUpdateNotice().checked(59, now).shouldCheck(now - 1000))
        assertTrue(AppUpdateNotice(59, -1, -1).shouldCheck(now))
    }

    @Test fun coldStartAndExplicitReopenCheckEvenWithRecentPersistedSuccess() {
        val saved = AppUpdateNotice().checked(104, now)
        val session = AppUpdateNoticeSession()
        assertTrue(session.shouldCheck(saved, now + 1000))
        assertEquals(1000L, session.nextDelay(saved, now + 1000))
        session.attempted()
        assertFalse(session.shouldCheck(saved, now + 2000)) // Resume/configuration change uses the same session.
        session.opened()
        assertTrue(session.shouldCheck(saved, now + 3000))
        assertTrue(AppUpdateNoticeSession().shouldCheck(saved, now + 3000)) // Process recreation.
    }

    @Test fun automaticFailureRetriesNormallyButNewOpeningCanTryAgain() {
        val notice = AppUpdateNotice(104).attempted(now)
        val session = AppUpdateNoticeSession()
        session.attempted()
        assertFalse(session.shouldCheck(notice, now + 1000))
        assertTrue(session.shouldCheck(notice, now + AppUpdateNotice.RETRY_INTERVAL))
        session.opened()
        assertTrue(session.shouldCheck(notice, now + 1000))
    }

    @Test fun freshOpeningWaitsForConfirmedServerLimitWithoutLosingItsCheck() {
        var elapsed = 1000L
        val policy = AppUpdateCheckPolicy({ elapsed }, { now })
        policy.failed(UpdateHttpException(429, 60_000L))
        val session = AppUpdateNoticeSession()
        val saved = AppUpdateNotice().checked(104, now)
        assertTrue(session.shouldCheck(saved, now))
        assertEquals(UpdateCheckDecision.Wait, policy.beforeCheck(force = true))
        elapsed += 60_000L
        assertTrue(session.shouldCheck(saved, now))
        assertEquals(UpdateCheckDecision.Check, policy.beforeCheck(force = true))
        session.attempted()
        assertFalse(session.shouldCheck(saved, now))
    }
}
