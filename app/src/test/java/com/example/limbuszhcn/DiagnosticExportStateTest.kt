package com.example.limbuszhcn

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiagnosticExportStateTest {
    @Test fun processRecreationRetainsArchiveAndSelectedDestinationUntilSaveCompletes() {
        val directory = Files.createTempDirectory("diagnostic-export")
        val archive = directory.resolve("limbus-diagnostics-20261012-120000.zip")
        Files.write(archive, byteArrayOf(80, 75, 3, 4))
        DiagnosticExportState(directory).begin(archive)
        val recreated = DiagnosticExportState(directory)
        assertEquals(archive, recreated.read()!!.archive)
        recreated.selected("content://documents/export/123")
        val resumed = DiagnosticExportState(directory)
        assertEquals("content://documents/export/123", resumed.read()!!.destination)
        resumed.retry()
        assertNull(resumed.read()!!.destination)
        assertTrue(Files.exists(archive))
        resumed.selected(null)
        assertTrue(DiagnosticExportState(directory).read()!!.cancelled)
        resumed.complete()
        assertNull(resumed.read())
        assertFalse(Files.exists(archive))
    }

    @Test fun missingOrForeignSourceCannotSilentlyProduceAnEmptyFile() {
        val directory = Files.createTempDirectory("diagnostic-export")
        val state = DiagnosticExportState(directory)
        try { state.selected("content://documents/123"); fail("missing archive") }
        catch (_: IllegalArgumentException) { }
        Files.write(directory.resolve("pending-export.properties"), "archive=../private.zip\nphase=choose\n".toByteArray())
        try { state.read(); fail("foreign path") }
        catch (_: IllegalArgumentException) { }
        state.discardMarker()
        assertNull(state.read())
    }
}
