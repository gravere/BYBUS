package com.example.limbuszhcn

import com.lody.virtual.helper.compat.ProviderRoutePolicy
import org.junit.Assert.*
import org.junit.Test

class ProviderRoutePolicyTest {
    @Test fun callersAreScopedWithoutBrandOrSdkRules() {
        listOf("com.ProjectMoon.LimbusCompany", "com.google.android.gms", "com.android.vending").forEach {
            assertTrue(ProviderRoutePolicy.supportedCaller(it))
        }
        listOf(null, "io.github.gravere.bybus", "com.google.android.gms.other", "other.guest").forEach {
            assertFalse(ProviderRoutePolicy.supportedCaller(it))
        }
    }
    @Test fun physicalUserIsNotConfusedWithVirtualUser() {
        assertEquals("gms.settings", ProviderRoutePolicy.localAuthority("gms.settings", 10))
        assertEquals("gms.settings", ProviderRoutePolicy.localAuthority("10@gms.settings", 10))
        assertNull(ProviderRoutePolicy.localAuthority("0@gms.settings", 10))
        assertNull(ProviderRoutePolicy.localAuthority("10@gms.settings", 0))
    }
    @Test fun malformedAndDocumentAuthoritiesStayWithAndroid() {
        listOf(null, "", "@gms", "a@gms", "-1@gms", "99999999999@gms", "0@", "0@gms@extra",
            "content://gms", "gms/path", "gms;other", "gms other", "com.android.externalstorage.documents").forEach {
            assertNull(it, ProviderRoutePolicy.localAuthority(it, 0))
        }
    }
}
