package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.LimbusBillingDiagnostics
import com.lody.virtual.helper.compat.MicrogBillingIdentityPolicy
import org.junit.Assert.*
import org.junit.Test

class BillingFailureReasonTest {
    @Test fun initializationFailuresHaveDistinctSafeCategories() {
        val cases = mapOf(
            "Billing is disabled" to "billing_disabled",
            "Failed to obtain login token." to "authorization",
            "Failed to retrieve device information." to "registration",
            "No Google account found." to "account",
            "Failed to retrieve client information." to "package",
            "HTTP 400 DF-DFERH-01" to "play_backend",
        )
        cases.forEach { (message, category) ->
            assertEquals(category, LimbusBillingDiagnostics.classifyFailure(message))
        }
    }

    @Test fun diagnosticCategoryCannotEchoServerPayload() {
        assertEquals("none", LimbusBillingDiagnostics.classifyFailure(null))
        assertEquals("other", LimbusBillingDiagnostics.classifyFailure("sensitive payload"))
        assertEquals("other", LimbusBillingDiagnostics.classifyFailure("x".repeat(4097)))
    }

    @Test fun authorizationObservationOnlyMatchesContainerBillingScope() {
        val scope = "oauth2:https://www.googleapis.com/auth/googleplay https://www.googleapis.com/auth/accounts.reauth"
        assertTrue(MicrogBillingIdentityPolicy.isBillingAuthorization("com.android.vending", "com.google", scope))
        assertFalse(MicrogBillingIdentityPolicy.isBillingAuthorization("other", "com.google", scope))
        assertFalse(MicrogBillingIdentityPolicy.isBillingAuthorization("com.android.vending", "other", scope))
        assertFalse(MicrogBillingIdentityPolicy.isBillingAuthorization("com.android.vending", "com.google", "oauth2:other"))
    }
}
