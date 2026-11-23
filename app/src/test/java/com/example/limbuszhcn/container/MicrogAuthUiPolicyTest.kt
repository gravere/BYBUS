package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.MicrogAuthUiPolicy as P
import org.junit.Assert.*
import org.junit.Test

class MicrogAuthUiPolicyTest {
    @Test fun consentResultRequiresGoogleOwnedRequestAndParent() {
        for (page in listOf("org.microg.gms.auth.AskPermissionActivity", "org.microg.gms.auth.AskPackageOverrideActivity")) {
            assertTrue(P.isConsentTarget(P.GMS, P.GMS, page))
            assertFalse(P.isConsentTarget(P.GAME, P.GMS, page))
            assertFalse(P.isConsentTarget(P.GMS, P.GAME, page))
        }
        assertFalse(P.isConsentTarget(P.GMS, P.GMS, P.LOGIN))
        assertTrue(P.canRelayConsent(P.GMS, true, 0))
        assertFalse(P.canRelayConsent(P.GMS, true, -1))
        assertFalse(P.canRelayConsent(P.GMS, false, 1))
        assertFalse(P.canRelayConsent(P.GAME, true, 1))
    }
    @Test fun finishingAnAddedAccountCannotRemoveItsParentGameTask() {
        assertEquals(0, P.finishTaskMode(P.GMS, P.LOGIN, false, 2))
        assertEquals(2, P.finishTaskMode(P.GMS, P.LOGIN, true, 2))
        assertEquals(0, P.finishTaskMode(P.GMS, P.LOGIN, false, 0))
        assertEquals(2, P.finishTaskMode(P.GMS, P.AUTH, false, 2))
        assertEquals(2, P.finishTaskMode(P.GAME, P.LOGIN, false, 2))
    }
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
