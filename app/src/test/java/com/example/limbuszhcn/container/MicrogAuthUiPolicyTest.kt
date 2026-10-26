package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.MicrogAuthUiPolicy as P
import org.junit.Assert.*
import org.junit.Test

class MicrogAuthUiPolicyTest {
    @Test fun newAccountAndReturningAccountKeepTheSameResultRoute() {
        for (activity in listOf(P.ASSISTED, P.AUTH, P.LOGIN, "org.microg.gms.accountsettings.ui.MainActivity")) {
            assertTrue(P.useClientLocal(P.GMS, "${P.GMS}:ui", P.GMS, "${P.GMS}:ui", activity))
        }
    }

    @Test fun differentProcessesAndUnknownTargetsCannotUseLocalClassloader() {
        assertFalse(P.useClientLocal(P.GMS, P.GMS, P.GMS, "${P.GMS}:ui", P.LOGIN))
        assertFalse(P.useClientLocal(P.GMS, null, P.GMS, null, P.LOGIN))
        assertFalse(P.useClientLocal(P.GAME, "ui", P.GMS, "ui", P.LOGIN))
        assertFalse(P.useClientLocal(P.GMS, "ui", P.GAME, "ui", P.LOGIN))
        assertFalse(P.useClientLocal(P.GMS, "ui", P.GMS, "ui", "org.microg.gms.auth.signin.Unknown"))
    }

    @Test fun bridgeIdentityRequiresGoogleCreatorAndExactGameRequest() {
        val action = "com.google.android.gms.auth.api.credentials.ASSISTED_SIGNIN"
        assertTrue(P.isGameIdentityBridge(P.GMS, P.GMS, P.ASSISTED, action, P.GAME))
        assertFalse(P.isGameIdentityBridge(P.GAME, P.GMS, P.ASSISTED, action, P.GAME))
        assertFalse(P.isGameIdentityBridge(P.GMS, P.GAME, P.ASSISTED, action, P.GAME))
        assertFalse(P.isGameIdentityBridge(P.GMS, P.GMS, P.LOGIN, action, P.GAME))
        assertFalse(P.isGameIdentityBridge(P.GMS, P.GMS, P.ASSISTED, "other", P.GAME))
        assertFalse(P.isGameIdentityBridge(P.GMS, P.GMS, P.ASSISTED, action, "other"))
    }
}
