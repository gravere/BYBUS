package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.LimbusBillingUiPolicy as Policy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimbusBillingUiPolicyTest {
    private val store = "com.android.vending"
    private val activity = "org.microg.vending.billing.ui.InAppBillingHostActivity"
    private val game = "com.ProjectMoon.LimbusCompany"

    @Test fun onlyCompanionsOwnCheckoutCanChangePendingTaskFlags() {
        assertTrue(Policy.isCheckoutTarget(store, store, activity))
        for (other in listOf(null, game, "com.example.limbuszhcn", "com.google.android.gms")) {
            assertFalse(Policy.isCheckoutTarget(other, store, activity))
            assertFalse(Policy.isCheckoutTarget(store, other, activity))
        }
        assertFalse(Policy.isCheckoutTarget(store, store, "$activity.other"))
        assertFalse(Policy.isCheckoutTarget(store, store, null))
    }

    @Test fun relayRequiresARealGameResultDestinationAndAcceptsRequestZero() {
        assertTrue(Policy.canRelayResult(game, true, 100))
        assertTrue(Policy.canRelayResult(game, true, 0))
        assertFalse(Policy.canRelayResult(game, true, -1))
        assertFalse(Policy.canRelayResult(game, false, 100))
        for (other in listOf(null, store, "com.example.limbuszhcn", "$game.other")) {
            assertFalse(Policy.canRelayResult(other, true, 100))
        }
    }
}
