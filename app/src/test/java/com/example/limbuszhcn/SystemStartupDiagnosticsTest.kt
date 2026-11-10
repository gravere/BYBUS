package com.example.limbuszhcn

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemStartupDiagnosticsTest {
    @Test fun exitSummaryAcceptsOnlyKnownBoundedCheckpointFormat() {
        fun parse(value: String) = SystemStartupDiagnostics.exitCheckpoint(value.toByteArray())
        assertEquals("bybus:1:82:HOME_FRAME", parse("bybus:1:82:HOME_FRAME"))
        assertEquals("unavailable", SystemStartupDiagnostics.exitCheckpoint(null))
        for (value in listOf("bybus:1:82:HOME_FRAME\nsecret", "token=private", "A".repeat(129),
            "bybus:1:82:account@example.com", "bybus:2:82:HOME_FRAME")) {
            assertEquals("unrecognized", parse(value))
        }
    }
}
