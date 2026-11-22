package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.LimbusAppleAuthPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class LimbusAppleAuthPolicyTest {
    @Test fun postReturnFromAppleAcceptsTheObservedHandlerBeforeDisplayedUrlChanges() {
        val flow = com.lody.virtual.helper.compat.LimbusAppleNavigation("game-test.firebaseapp.com")
        val apple = "https://appleid.apple.com/auth/authorize"
        assertTrue(flow.requested(apple))
        assertFalse(flow.canReturn(apple))
        assertTrue(flow.requested("https://game-test.firebaseapp.com/__/auth/handler"))
        assertTrue(flow.canReturn(apple)) // getUrl still names the last committed Apple page.
        assertTrue(flow.requested(apple))
        assertFalse(flow.canReturn(apple))
        assertFalse(flow.requested("https://evil.test/__/auth/handler"))
        assertFalse(flow.canReturn("https://evil.test/__/auth/handler"))
        assertTrue(flow.requested("https://game-test.firebaseapp.com/other"))
        assertFalse(flow.canReturn("https://game-test.firebaseapp.com/other"))
    }
    private val event = "12345678-1234-1234-1234-123456789abc"
    private val url = "https://game-test.firebaseapp.com/__/auth/handler?apn=${Policy.GAME}&providerId=apple.com" +
        "&authType=signInWithRedirect&eventId=$event&sessionId=original&apiKey=public-config&publicKey=original&sha1Cert=${"a".repeat(40)}"
    @Test fun preservesGameIdentityAndAcceptsOnlyTheOfficialAppleHandler() {
        assertEquals(event, Policy.event(url))
        for (bad in listOf(url.replace(Policy.GAME, "io.github.gravere.bybus"), url.replace("apple.com", "google.com"),
            url.replace("https:", "http:"), url.replace("firebaseapp.com", "firebaseapp.com.evil.test"),
            url.replace("/__/auth/handler", "/other"), url + "&apn=${Policy.GAME}", url.replace(event, "bad"),
            url.replace("https://", "https://user:password@"))) assertNull(bad, Policy.event(bad))
    }
    @Test fun browserNavigationIsLimitedToThisProjectAndAppleSignIn() {
        assertTrue(Policy.navigation("https://appleid.apple.com/auth/authorize", "game-test.firebaseapp.com"))
        assertTrue(Policy.navigation(url, "game-test.firebaseapp.com"))
        for (bad in listOf("javascript:alert(1)", "file:///data/local/tmp/file", "https://evil.test/",
            "https://appleid.apple.com.evil.test/", "https://other.firebaseapp.com/", "https://appleid.apple.com:444/"))
            assertFalse(bad, Policy.navigation(bad, "game-test.firebaseapp.com"))
    }
}
