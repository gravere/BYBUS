package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.MicrogBillingIdentityPolicy as Policy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicrogBillingIdentityPolicyTest {
    @Test fun registrationBridgeRejectsDisabledOrIncompleteState() {
        assertNull(Policy.registeredId(false, 42, 100))
        assertNull(Policy.registeredId(true, 0, 100))
        assertNull(Policy.registeredId(true, 42, 0))
        assertNull(Policy.registeredId(true, 42, -1))
        assertEquals("42", Policy.registeredId(true, 42, 100))
    }

    @Test fun registrationIdRetainsUnsigned64BitRepresentation() {
        assertEquals("18446744073709551615", Policy.registeredId(true, -1, 100))
        assertEquals("9223372036854775808", Policy.registeredId(true, Long.MIN_VALUE, 100))
        assertEquals("9223372036854775807", Policy.registeredId(true, Long.MAX_VALUE, 100))
    }
    @Test fun registrationIdDiagnosticsRejectOtherOrCombinedQueries() {
        assertTrue(Policy.isRegistrationIdSelection(arrayOf("android_id")))
        assertFalse(Policy.isRegistrationIdSelection(arrayOf("android_id", "other")))
        assertFalse(Policy.isRegistrationIdSelection(arrayOf("device_id")))
        assertFalse(Policy.isRegistrationIdSelection(emptyArray()))
        assertFalse(Policy.isRegistrationIdSelection(null))
    }

    @Test fun gservicesLookupDoesNotApplyToHostOrOtherAuthorities() {
        val owner = "com.google.android.gms"
        val name = "org.microg.gms.gservices.GServicesProvider"
        val authority = "com.google.android.gsf.gservices"
        assertTrue(Policy.isGoogleGservicesProvider(owner, name, authority))
        assertFalse(Policy.isGoogleGservicesProvider("com.google.android.gsf", name, authority))
        assertFalse(Policy.isGoogleGservicesProvider(owner, "$name.other", authority))
        assertFalse(Policy.isGoogleGservicesProvider(owner, name, "$authority.other"))
        assertFalse(Policy.isGoogleGservicesProvider(owner, name, null))
    }
    @Test fun accountPermissionRequiresExactContainerServerIdentity() {
        val permission = "android.permission.ACCOUNT_MANAGER"
        assertTrue(Policy.isVirtualAccountManagerCaller(permission, 10453, 200, 10453, 200))
        assertFalse(Policy.isVirtualAccountManagerCaller(permission, 10453, 201, 10453, 200))
        assertFalse(Policy.isVirtualAccountManagerCaller(permission, 10551, 200, 10453, 200))
        assertFalse(Policy.isVirtualAccountManagerCaller(permission, 10453, 0, 10453, 0))
        assertFalse(Policy.isVirtualAccountManagerCaller(permission, 10453, 0, 10453, 200))
        assertFalse(Policy.isVirtualAccountManagerCaller("android.permission.GET_ACCOUNTS", 10453, 200, 10453, 200))
    }

    @Test fun authenticatorTransportCannotApplyToOtherGoogleServices() {
        val authenticator = "org.microg.gms.auth.loginservice.GoogleLoginService"
        assertTrue(Policy.isGoogleAuthenticator("com.google.android.gms", authenticator))
        assertFalse(Policy.isGoogleAuthenticator("com.android.vending", authenticator))
        assertFalse(Policy.isGoogleAuthenticator("com.google.android.gms", "org.microg.gms.checkin.CheckinService"))
        assertFalse(Policy.isGoogleAuthenticator("com.google.android.gms", "$authenticator.other"))
        assertFalse(Policy.isGoogleAuthenticator(null, authenticator))
    }

    @Test fun registrationBroadcastCannotRedirectOtherAppsOrActions() {
        val gms = "com.google.android.gms"
        val target = "org.microg.gms.checkin.TriggerReceiver"
        assertTrue(Policy.isGoogleRegistrationBroadcast(gms, gms, target, null))
        assertFalse(Policy.isGoogleRegistrationBroadcast("com.android.vending", gms, target, null))
        assertFalse(Policy.isGoogleRegistrationBroadcast(gms, "com.example.limbuszhcn", target, null))
        assertFalse(Policy.isGoogleRegistrationBroadcast(gms, gms, "$target.other", null))
        assertFalse(Policy.isGoogleRegistrationBroadcast(gms, gms, target, "android.provider.Telephony.SECRET_CODE"))
        assertFalse(Policy.isGoogleRegistrationBroadcast(null, gms, target, null))
    }

    @Test fun googleIdentityCannotBeAppliedToOtherProvidersOrCallers() {
        val provider = "com.google.android.gms"
        val authority = "com.google.android.gsf.gservices"
        assertTrue(Policy.preservesGoogleProviderCaller(provider, authority, "com.android.vending"))
        assertTrue(Policy.preservesGoogleProviderCaller(provider, authority, provider))
        assertFalse(Policy.preservesGoogleProviderCaller("com.google.android.gsf", authority, "com.android.vending"))
        assertFalse(Policy.preservesGoogleProviderCaller(provider, "settings", "com.android.vending"))
        assertFalse(Policy.preservesGoogleProviderCaller(provider, authority, "com.ProjectMoon.LimbusCompany"))
        assertFalse(Policy.preservesGoogleProviderCaller(provider, authority, "com.android.vending.other"))
        assertFalse(Policy.preservesGoogleProviderCaller(provider, authority, null))
        val settings = "com.google.android.gms.microg.settings"
        assertTrue(Policy.preservesGoogleProviderCaller(provider, settings, provider))
        assertTrue(Policy.preservesGoogleProviderCaller(provider, settings, "com.android.vending"))
        assertFalse(Policy.preservesGoogleProviderCaller(provider, settings, "com.example.limbuszhcn"))
        assertFalse(Policy.preservesGoogleProviderCaller(provider, settings, "com.ProjectMoon.LimbusCompany"))
    }

    @Test fun settingsProviderLookupRequiresExactOwnerClassAndAuthority() {
        val provider = "com.google.android.gms"
        val name = "org.microg.gms.settings.SettingsProvider"
        val authority = "com.google.android.gms.microg.settings"
        assertTrue(Policy.isGoogleSettingsProvider(provider, name, authority))
        assertFalse(Policy.isGoogleSettingsProvider("com.example.limbuszhcn", name, authority))
        assertFalse(Policy.isGoogleSettingsProvider(provider, "$name.other", authority))
        assertFalse(Policy.isGoogleSettingsProvider(provider, name, "settings"))
        assertFalse(Policy.isGoogleSettingsProvider(provider, null, authority))
    }

    @Test fun onlyExactGoogleComponentsKeepTheirBasePackage() {
        assertTrue(Policy.isGoogleComponent("com.android.vending"))
        assertTrue(Policy.isGoogleComponent("com.google.android.gms"))
        assertFalse(Policy.isGoogleComponent("com.example.limbuszhcn"))
        assertFalse(Policy.isGoogleComponent("com.google.android.gms.other"))
        assertFalse(Policy.isGoogleComponent(null))
    }
}
