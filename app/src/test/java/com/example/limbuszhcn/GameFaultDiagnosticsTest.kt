package com.example.limbuszhcn

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GameFaultDiagnosticsTest {
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
