package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.LimbusBillingBroadcastPolicy as Policy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LimbusBillingBroadcastPolicyTest {
    private val game = "com.ProjectMoon.LimbusCompany"
    private val action = "com.android.vending.billing.PURCHASES_UPDATED"

    @Test fun routesTheGamesOwnBillingResult() {
        assertTrue(Policy.isSelfResult(game, game, null, action))
        assertTrue(Policy.isSelfResult(game, game, null, Policy.LOCAL_ACTION))
    }

    @Test fun rejectsOtherCallersAndDestinations() {
        for (other in listOf(null, "com.android.vending", "com.example.limbuszhcn", "$game.other")) {
            assertFalse(Policy.isSelfResult(other, game, null, action))
            assertFalse(Policy.isSelfResult(game, other, null, action))
        }
    }

    @Test fun rejectsComponentDirectedAndUnrelatedBroadcasts() {
        assertFalse(Policy.isSelfResult(game, game, "com.example.Receiver", action))
        for (other in listOf(null, "", "android.intent.action.CLOSE_SYSTEM_DIALOGS", "$action.other")) {
            assertFalse(Policy.isSelfResult(game, game, null, other))
        }
    }

    @Test fun normalizesOnlyTheGamesOwnApplicationContextAlias() {
        val host = "com.example.limbuszhcn"
        for (resultAction in listOf(action, Policy.LOCAL_ACTION)) {
            assertEquals(game, Policy.resolveSelfTarget(game, game, host, null, resultAction))
            assertEquals(game, Policy.resolveSelfTarget(game, host, host, null, resultAction))
            for (other in listOf(null, host, "com.android.vending", "$game.other")) {
                assertNull(Policy.resolveSelfTarget(other, host, host, null, resultAction))
            }
            for (target in listOf(null, "", "com.android.vending", "$game.other")) {
                assertNull(Policy.resolveSelfTarget(game, target, host, null, resultAction))
            }
        }
        assertNull(Policy.resolveSelfTarget(game, host, host, "some.Receiver", Policy.LOCAL_ACTION))
        assertNull(Policy.resolveSelfTarget(game, host, host, null, "${Policy.LOCAL_ACTION}.other"))
        assertNull(Policy.resolveSelfTarget(game, null, null, null, Policy.LOCAL_ACTION))
        assertNull(Policy.resolveSelfTarget(game, "", "", null, Policy.LOCAL_ACTION))
    }
}
