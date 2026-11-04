package com.example.limbuszhcn.selfupdate

import org.junit.Assert.*
import org.junit.Test

class AppUpdateNoticeTest {
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

    @Test fun resumeDoesNotRefetchWithinSixHoursOfSuccess() {
        assertTrue(AppUpdateNotice().shouldCheck(now))
        val saved = AppUpdateNotice().checked(59, now)
        assertFalse(saved.shouldCheck(now + 6 * 60 * 60_000L - 1))
        assertTrue(saved.shouldCheck(now + 6 * 60 * 60_000L))
    }

    @Test fun failedAttemptKeepsNoticeAndRetriesAfterFifteenMinutes() {
        val failed = AppUpdateNotice(59).attempted(now)
        assertTrue(failed.hasNewer(58))
        assertFalse(failed.shouldCheck(now + 15 * 60_000L - 1))
        assertTrue(failed.shouldCheck(now + 15 * 60_000L))
    }

    @Test fun withdrawnReleaseClearsStaleNoticeAfterSuccessfulCheck() {
        val previous = AppUpdateNotice().checked(59, now)
        assertFalse(previous.checked(58, now + 1000).hasNewer(58))
    }

    @Test fun ClockRollbackDoesNotLockChecksIndefinitely() {
        assertTrue(AppUpdateNotice().checked(59, now).shouldCheck(now - 1000))
        assertTrue(AppUpdateNotice(59, -1, -1).shouldCheck(now))
    }
}
