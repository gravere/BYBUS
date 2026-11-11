package com.example.limbuszhcn

import org.junit.Assert.*
import org.junit.Test

class LauncherEntryPolicyTest {
    @Test fun desktopTapRoutesToExistingSession() {
        assertTrue(shouldResumeLauncherEntry("android.intent.action.MAIN", true, false, false))
    }
    @Test fun historyAndRecreationDoNotCauseReturnLoop() {
        assertFalse(shouldResumeLauncherEntry("android.intent.action.MAIN", true, true, false))
        assertFalse(shouldResumeLauncherEntry("android.intent.action.MAIN", true, false, true))
    }
    @Test fun managementAndOtherEntriesStayInHost() {
        for (action in listOf(null, MANAGE_BYBUS_ACTION, "io.github.gravere.bybus.UPDATE", "android.intent.action.VIEW"))
            assertFalse(shouldResumeLauncherEntry(action, true, false, false))
        assertFalse(shouldResumeLauncherEntry("android.intent.action.MAIN", false, false, false))
    }
}
