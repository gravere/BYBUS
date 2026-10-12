package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.LimbusPlayStoreCompat as Play
import org.junit.Assert.*
import org.junit.Test

class LimbusPlayStoreCompatTest {
    private val market = "market://details?id=${Play.GAME_PACKAGE}"
    private fun accepts(data: String?, caller: String = Play.GAME_PACKAGE,
        action: String = "android.intent.action.VIEW", pkg: String? = null, component: String? = null) =
        Play.isGameDetailsRequest(caller, action, data, pkg, component)

    @Test fun allowsOnlyTheGameDetailsPage() {
        assertTrue(accepts(market))
        assertTrue(accepts(Play.DETAILS_URL))
        assertTrue(accepts(market, pkg = Play.STORE_PACKAGE, component = Play.STORE_PACKAGE))
    }

    @Test fun rejectsRedirectsImpersonationAndOtherActivities() {
        for (data in listOf(null, "market://details?id=other.game", "$market&id=other.game",
                "$market&referrer=anything", "$market#fragment", "market://search?q=limbus",
                "intent://details?id=${Play.GAME_PACKAGE}", Play.DETAILS_URL.replace("https:", "http:"),
                Play.DETAILS_URL.replace("play.google.com", "play.google.com.evil"),
                Play.DETAILS_URL.replace("play.google.com", "user@play.google.com"),
                Play.DETAILS_URL.replace("play.google.com", "play.google.com:8443"),
                Play.DETAILS_URL.replace("apps/details", "apps/%64etails"))) assertFalse(data, accepts(data))
        assertFalse(accepts(market, caller = "another.app"))
        assertFalse(accepts(market, action = "android.intent.action.SEND"))
        assertFalse(accepts(market, pkg = "another.app"))
        assertFalse(accepts(market, component = "another.app"))
    }
}
