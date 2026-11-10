package com.example.limbuszhcn.selfupdate

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class AppInstallJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun path() = temporary.root.toPath().resolve("app-install.properties")

    @Test fun replacementReconcilesVersionAcrossProcessRecreationBeforeClaimingAFrame() {
        val old = AppInstallJournal(path())
        old.prepare(81, 82, 100)
        old.phase(AppInstallJournal.Phase.DISPATCHED, 110)
        val next = AppInstallJournal(path())
        next.observe(82, 200)
        assertEquals("installed", next.read()!!.outcome)
        assertEquals(0L, next.read()!!.firstHomeFrameAt)
        next.observe(82, 210, homeFrame = true)
        next.observe(82, 220, homeFrame = true)
        assertEquals(210L, AppInstallJournal(path()).read()!!.firstHomeFrameAt)
        assertTrue(next.summary().contains("openSource=unknown"))
    }

    @Test fun installerReturningWithoutUpdateIsNotSuccessOrAutomaticRetry() {
        val journal = AppInstallJournal(path())
        journal.prepare(81, 82, 100)
        journal.phase(AppInstallJournal.Phase.DISPATCHED, 110)
        journal.phase(AppInstallJournal.Phase.RETURNED, 120)
        journal.observe(81, 130, homeFrame = true)
        val afterRestart = AppInstallJournal(path()).read()!!
        assertEquals("not-updated", afterRestart.outcome)
        assertEquals(AppInstallJournal.Phase.RETURNED, afterRestart.phase)
        assertEquals(0L, afterRestart.firstHomeFrameAt)
    }

    @Test fun laterVersionAndLateCallbacksPreserveCompletedReceipt() {
        val journal = AppInstallJournal(path())
        journal.prepare(81, 82, 100)
        journal.phase(AppInstallJournal.Phase.DISPATCHED, 110)
        journal.observe(83, 200, homeFrame = true)
        journal.phase(AppInstallJournal.Phase.DISPATCH_FAILED, 210)
        assertEquals("superseded", journal.read()!!.outcome)
        assertEquals(AppInstallJournal.Phase.DISPATCHED, journal.read()!!.phase)
    }

    @Test fun newAttemptReplacesOldReceiptWithoutGrowingHistory() {
        val journal = AppInstallJournal(path())
        journal.prepare(81, 82, 100)
        journal.observe(82, 200, homeFrame = true)
        journal.prepare(82, 83, 300)
        assertEquals(82L, journal.read()!!.fromCode)
        assertEquals("unknown", journal.read()!!.outcome)
        assertEquals(0L, journal.read()!!.firstHomeFrameAt)
        assertTrue(Files.size(path()) < 4096)
        Files.list(path().parent).use { assertEquals(1L, it.count()) }
    }

    @Test fun absentCorruptOrOversizedReceiptDoesNotPreventStartup() {
        val journal = AppInstallJournal(path())
        assertNull(journal.read())
        journal.observe(82, 100, homeFrame = true)
        assertFalse(Files.exists(path()))
        Files.write(path(), "schema=1\nfromCode=private@example.com".toByteArray())
        assertNull(journal.read())
        assertFalse(journal.summary().contains("private@"))
        Files.write(path(), ByteArray(4097) { 65 })
        assertNull(journal.read())
        journal.prepare(81, 82, 200)
        assertEquals(82L, journal.read()!!.targetCode)
    }

    @Test fun invalidTargetDoesNotOverwritePreviousRecord() {
        val journal = AppInstallJournal(path())
        journal.prepare(81, 82, 100)
        for (target in listOf(0L, 80L, 81L)) {
            assertTrue(runCatching { journal.prepare(81, target, 200) }.isFailure)
        }
        assertEquals(82L, journal.read()!!.targetCode)
    }
}
