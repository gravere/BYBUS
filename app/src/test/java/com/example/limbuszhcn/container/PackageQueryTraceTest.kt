package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.PackageQueryTrace
import org.junit.Assert.*
import org.junit.Test

class PackageQueryTraceTest {
    @Test fun metadataNeverContainsIdentityOrExceptionMessages() {
        val trace = PackageQueryTrace()
        trace.record("getPackagesForUid", true, arrayOf("private.package"), null)
        trace.record("getNameForUid", true, "private.package", null)
        trace.record("getPackageUid", true, 18493, null)
        trace.record("getPackageInfo", false, null, SecurityException("secret@example.test"))
        val summary = trace.snapshot()
        assertTrue(summary.contains("getPackagesForUid:container:packages"))
        assertTrue(summary.contains("getPackageInfo:platform:security_error"))
        listOf("private", "18493", "secret", "example").forEach { assertFalse(summary.contains(it)) }
    }

    @Test fun onlyRecentSupportedOperationsAreRetained() {
        val trace = PackageQueryTrace()
        trace.record("getPackageInfo", true, null, null)
        repeat(40) { trace.record("checkSignatures", true, -4, null) }
        trace.record("unrecognized.private.method", true, "sensitive", null)
        val summary = trace.snapshot()
        assertTrue(summary.startsWith("calls=41 totals="))
        assertEquals(32, summary.substringAfter("recent=").split(',').size)
        assertFalse(summary.substringAfter("recent=").contains("getPackageInfo"))
        assertFalse(summary.contains("private"))
        assertTrue(summary.contains("code_-4"))
    }

    @Test fun earlyPermissionCallsSurviveLaterPackageQueriesWithoutArguments() {
        val trace = PackageQueryTrace()
        trace.record("checkPermission", true, -1, null)
        trace.record("checkPermission", false, 0, null)
        repeat(60) { trace.record("getPackageInfo", true, null, null) }
        val summary = trace.snapshot()
        assertTrue(summary.contains("checkPermission:container:1"))
        assertTrue(summary.contains("checkPermission:platform:1"))
        assertTrue(summary.contains("getPackageInfo:container:60"))
        assertFalse(summary.substringAfter("recent=").contains("checkPermission"))
        assertTrue(summary.length < 4000)
    }

    @Test fun emptyUnknownAndExceptionalResultsRemainDistinct() {
        val trace = PackageQueryTrace()
        trace.record("getPackagesForUid", true, null, null)
        trace.record("getPackagesForUid", true, emptyArray<String>(), null)
        trace.record("getPackageInfoVersioned", false, null, IllegalStateException("do not retain"))
        val summary = trace.snapshot()
        assertTrue(summary.contains(":null"))
        assertTrue(summary.contains(":empty"))
        assertTrue(summary.contains("getPackageInfoVersioned:platform:exception"))
        assertFalse(summary.contains("retain"))
    }
}
