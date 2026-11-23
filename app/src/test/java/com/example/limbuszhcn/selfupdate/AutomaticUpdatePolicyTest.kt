package com.example.limbuszhcn.selfupdate

import org.junit.Assert.*
import org.junit.Test

class AutomaticUpdatePolicyTest {
    private fun AutomaticUpdatePolicy.step(enabled: Boolean = true, eligible: Boolean = true,
        published: Long = 200, metadata: Long? = 200, busy: Boolean = false, ready: Boolean = false,
        failed: Boolean = false, verified: Boolean = true) = next(enabled, eligible, 155, published,
            metadata, busy, ready, failed, verified)

    @Test fun disabledBusyOfflineAndGameRunningDoNotConsumeAnAttempt() {
        val policy = AutomaticUpdatePolicy()
        assertEquals(AutomaticUpdatePolicy.Action.None, policy.step(enabled = false))
        assertEquals(AutomaticUpdatePolicy.Action.None, policy.step(eligible = false))
        assertEquals(AutomaticUpdatePolicy.Action.None, policy.step(busy = true))
        assertEquals(AutomaticUpdatePolicy.Action.None, policy.step(verified = false))
        assertEquals(AutomaticUpdatePolicy.Action.Download, policy.step())
    }

    @Test fun failedOrCanceledAutomaticWorkDoesNotLoopOnRecomposition() {
        val policy = AutomaticUpdatePolicy()
        assertEquals(AutomaticUpdatePolicy.Action.Download, policy.step())
        repeat(10) { assertEquals(AutomaticUpdatePolicy.Action.None, policy.step()) }
        policy.opened()
        assertEquals(AutomaticUpdatePolicy.Action.None, policy.step(failed = true))
        assertEquals(AutomaticUpdatePolicy.Action.Download, policy.step())
        policy.pause()
        assertEquals(AutomaticUpdatePolicy.Action.None, policy.step(published = 201, metadata = 201))
        policy.opened()
        assertEquals(AutomaticUpdatePolicy.Action.Download, policy.step())
    }

    @Test fun metadataMustMatchFreshPublicationBeforeDownload() {
        val policy = AutomaticUpdatePolicy()
        assertEquals(AutomaticUpdatePolicy.Action.Check, policy.step(metadata = 199))
        assertEquals(AutomaticUpdatePolicy.Action.None, policy.step(metadata = 199))
        assertEquals(AutomaticUpdatePolicy.Action.Download, policy.step())
        assertEquals(AutomaticUpdatePolicy.Action.None, policy.step(published = 155, metadata = 200))
        assertEquals(AutomaticUpdatePolicy.Action.Check, policy.step(published = 201))
    }

    @Test fun readyPackageStaysReadyAndGameInterruptionCanBeDeferred() {
        val policy = AutomaticUpdatePolicy()
        assertEquals(AutomaticUpdatePolicy.Action.None, policy.step(ready = true))
        assertEquals(AutomaticUpdatePolicy.Action.Download, policy.step())
        policy.defer()
        assertEquals(AutomaticUpdatePolicy.Action.None, policy.step(eligible = false))
        assertEquals(AutomaticUpdatePolicy.Action.Download, policy.step())
    }

    @Test fun publicationRefreshPreservesUserPreference() {
        val state = AppUpdateState(155, automaticUpdates = true, error = "超时", checkFailed = true)
        assertTrue(state.withPublishedVersion(200).automaticUpdates)
        assertNull(state.withPublishedVersion(200).error)
        assertFalse(AppUpdateState(155).automaticUpdates)
    }
}
