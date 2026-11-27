package com.example.limbuszhcn

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GameFaultDiagnosticsTest {
    @Test fun curlFailuresRetainCodeButNeverConnectionPayload() {
        val prefix = "1795742733.611 576 688 E Unity : "
        for ((code, reason) in mapOf(6 to "dns", 18 to "partial_transfer", 23 to "local_write",
                28 to "timeout", 35 to "tls_handshake", 60 to "certificate", 56 to "receive")) {
            assertEquals("time=1795742733.611 pid=576 event=network_request_failed curl=$code reason=$reason",
                safeGameFaultMarker(prefix + "Curl error $code: https://secret.example/download?token=PRIVATE"))
        }
        assertEquals(" reason=unspecified", safeCurlFailureDetails("Curl error PRIVATE"))
    }

    @Test fun googleAuthStagesExcludeAccountsAndTokens() {
        val prefix = "1795705125.432 14812 14812 D AuthorizationService : "
        assertEquals("time=1795705125.432 pid=14812 event=google_auth stage=authorize_requested component=AuthorizationService",
            safeGoogleAuthMarker(prefix + "authorize called, packageName=private request=secret@example.com"))
        assertTrue(safeGoogleAuthMarker(prefix + "authorize resolved: silent, grantedScopes=2")!!.contains("stage=authorize_completed"))
        assertTrue(safeGoogleAuthMarker(prefix + "authorize failed, falling back to PendingIntent")!!.contains("stage=authorize_retry_consent"))
        assertNull(safeGoogleAuthMarker(prefix + "token=SECRET"))
        assertNull(safeGoogleAuthMarker(prefix.replace("AuthorizationService", "OtherApp") + "authorize called"))
    }
    @Test fun identityUiStagesDoNotExportResultPayloadsOrBorrowOtherTags() {
        fun marker(tag: String, message: String) = safeGoogleAuthMarker("1795759354.000 1234 1234 I $tag : $message")
        for ((tag, message) in listOf(
            "AssistedSignInActivity" to "loginResult: googleSignInAccount: SECRET@example.com",
            "AssistedSignInActivity" to "errorResult: Status{message=SECRET}",
            "AuthSignInActivity" to "Result: Bundle{token=SECRET}",
            "AssistedSignInFragment" to "checkAccountAuthStatus: account:SECRET auth error",
            "LimbusVA" to "Relay Google Identity UI result resultCode=-1 dataPresent=true"
        )) {
            val result = marker(tag, message)
            assertNotNull(result)
            assertFalse(result!!.contains("SECRET"))
            assertFalse(result.contains("Bundle"))
        }
        assertTrue(marker("LimbusVA", "Relay Google Identity UI result resultCode=0 dataPresent=false")!!.contains("cancelled"))
        assertNull(marker("AuthSignInActivity", "authorize called SECRET"))
        assertNull(marker("LimbusVA", "account=SECRET"))
        assertNull(marker("Other", "Result: SECRET"))
    }
    @Test fun inputExceptionsKeepGameCallbacksAndExcludeMessages() {
        val prefix = "1795681302.441 32346 32520 W Unity : "
        for (type in listOf("ArgumentException", "InvalidCastException", "MissingMethodException",
                "NotSupportedException", "KeyNotFoundException")) {
            assertEquals("time=1795681302.441 pid=32346 event=managed_exception type=$type",
                safeGameFaultMarker(prefix + "$type: secret@example.com /private/path"))
        }
        assertEquals("time=1795681302.441 pid=32346 event=managed_frame method=SkillButton.OnPointerEnter",
            safeGameFaultMarker(prefix + "at SkillButton.OnPointerEnter (PointerEventData private-value)"))
        assertNull(safeGameFaultMarker(prefix + "secret@example.com (private-value)"))
        assertNull(safeGameFaultMarker(prefix + "https://example.com/key (private-value)"))
    }

    @Test fun reportSiteKeepsOnlyFixedCodeAndRelativeOffset() {
        val prefix = "1795681302.441 32346 32520 W LimbusIntegrity : "
        assertEquals("time=1795681302.441 pid=32346 event=game_report_site code=50025 callerOffset=75940 forwarded=1",
            safeGameFaultMarker(prefix + "report code=50025 callerOffset=75940 forwarded=1"))
        assertNull(safeGameFaultMarker(prefix + "report code=50025 callerOffset=/private/path forwarded=1"))
        assertNull(safeGameFaultMarker(prefix + "report code=50025 callerOffset=75940 forwarded=1 secret"))
        assertEquals("time=1795681302.441 pid=32346 event=game_report_context site=94e90 flags=00000020 probeCode=0 forwarded=1",
            safeGameFaultMarker(prefix + "report_context site=94e90 flags=00000020 probeCode=0 forwarded=1"))
        assertNull(safeGameFaultMarker(prefix + "report_context site=94e90 flags=00000020 probeCode=0 forwarded=1 private"))
        assertNull(safeGameFaultMarker(prefix + "report_context site=other flags=00000020 probeCode=0 forwarded=1"))
    }
    @Test fun errorsPreserveCategoryAndFrameWithoutPayload() {
        val prefix = "1795512900.987 4470 4587 E Unity : "
        assertEquals("time=1795512900.987 pid=4470 event=managed_exception type=NullReferenceException",
            safeGameFaultMarker(prefix + "NullReferenceException: private-account@example.com"))
        assertEquals("time=1795512900.987 pid=4470 event=managed_frame method=StageScript_Cromer.CheckTrigger",
            safeGameFaultMarker(prefix + "at StageScript_Cromer.CheckTrigger (System.String secret-token)"))
        assertNull(safeGameFaultMarker(prefix + "private-account@example.com receipt=SECRET"))
        assertNull(safeGameFaultMarker(prefix.replace("Unity", "Billing") + "NullReferenceException"))
        assertEquals("time=1795512900.987 pid=4470 event=graphics_device_lost",
            safeGameFaultMarker(prefix + "Vulkan error VK_ERROR_DEVICE_LOST at private-path"))
    }
    @Test fun fileLimitKeepsRecentCompleteRows() {
        val file = File.createTempFile("bounded-diagnostic", ".log")
        try {
            file.writeText("OLD-ROW\n" + "x".repeat(1000) + "\nLATEST-EXIT\n")
            val tail = diagnosticTail(file, 80)
            assertTrue(tail.startsWith("[truncated to recent data]"))
            assertTrue(tail.contains("LATEST-EXIT"))
            assertFalse(tail.contains("OLD-ROW"))
        } finally { file.delete() }
    }
}
