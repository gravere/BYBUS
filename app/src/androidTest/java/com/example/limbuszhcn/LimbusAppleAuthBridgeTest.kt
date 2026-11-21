package com.example.limbuszhcn

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.helper.compat.LimbusAppleAuthBridge as Bridge
import com.lody.virtual.helper.compat.LimbusAppleAuthPolicy as Policy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LimbusAppleAuthBridgeTest {
    private val event = "12345678-1234-1234-1234-123456789abc"
    private fun callback() = Intent.parseUri("intent://firebase.auth/#Intent;scheme=genericidp;" +
        "package=${Policy.GAME};S.eventId=$event;S.link=encrypted-result;B.encryptionEnabled=true;end", Intent.URI_INTENT_SCHEME)

    @Test fun officialIntentCallbackKeepsRequiredFieldsAndDropsExternalPayload() {
        val input = callback().putExtra("untrusted", "discard").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clean = Bridge.result(input, event)!!
        assertEquals(ComponentName(Policy.GAME, Policy.ACTIVITY), clean.component)
        assertEquals("encrypted-result", clean.getStringExtra("link"))
        assertEquals(event, clean.getStringExtra("eventId"))
        assertTrue(clean.getBooleanExtra("encryptionEnabled", false))
        assertFalse(clean.hasExtra("untrusted"))
        assertEquals(0, clean.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    @Test fun unknownSessionOrWrongUriCannotBeForwarded() {
        assertNull(Bridge.result(callback(), "other"))
        assertNull(Bridge.result(callback().setData(android.net.Uri.parse("genericidp://firebase.auth/other")), event))
        assertNull(Bridge.result(callback().setData(android.net.Uri.parse("genericidp://evil.test/")), event))
        assertNull(Bridge.result(callback().putExtra("link", "x".repeat(65537)), event))
    }
    @Test fun genuineFailureIsReturnedWithoutInventingASuccessResult() {
        val error = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("genericidp://firebase.auth/"))
            .putExtra("firebaseError", "{\"code\":\"auth/web-context-cancelled\"}")
        val clean = Bridge.result(error, event)!!
        assertTrue(clean.hasExtra("firebaseError")); assertFalse(clean.hasExtra("link"))
        assertNull(Bridge.result(error.putExtra("link", "conflicting"), event))
    }
    @Test fun authWindowCannotBeOpenedByAnotherAppAndOrdinaryIntentsDoNotGetAFreeBrowserRoute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getActivityInfo(ComponentName(context.packageName, Bridge.WINDOW), 0)
        assertFalse(info.exported)
        assertNull(Bridge.resolveBrowserProbe(Intent(Intent.ACTION_VIEW)))
        assertNull(Bridge.browserIntent(Policy.GAME, Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))))
    }
}
