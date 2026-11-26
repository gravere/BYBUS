package com.example.limbuszhcn

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GameFaultDiagnosticsTest {
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
