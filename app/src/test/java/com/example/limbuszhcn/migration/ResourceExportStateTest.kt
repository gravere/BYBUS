package com.example.limbuszhcn.migration

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class ResourceExportStateTest {
    @Test
    fun interruptedExportRetainsRecoveryUntilProviderDeletionSucceeds() {
        val directory = Files.createTempDirectory("export-recovery")
        val destination = "content://test.documents/document/new-archive"
        ResourceExportState(directory).begin(destination)
        val recovered = ResourceExportState(directory)
        assertThrows(IOException::class.java) { recovered.recover { throw IOException("provider offline") } }
        assertThrows(IllegalStateException::class.java) { recovered.begin("content://test.documents/document/second") }
        val deleted = mutableListOf<String>()
        recovered.recover { deleted += it }
        recovered.recover { fail("The same document must not be deleted twice") }
        assertEquals(listOf(destination), deleted)
    }

    @Test
    fun completedArchiveIsNeverDeletedOnNextVisit() {
        val directory = Files.createTempDirectory("export-complete")
        val state = ResourceExportState(directory)
        state.begin("content://test.documents/document/finished")
        state.complete()
        ResourceExportState(directory).recover { fail("A finished archive must be preserved") }
        state.begin("content://test.documents/document/next")
    }

    @Test
    fun rejectsPrivateFileDestinationAndMalformedRecoveryRecord() {
        val directory = Files.createTempDirectory("export-invalid")
        val state = ResourceExportState(directory)
        assertThrows(IllegalArgumentException::class.java) { state.begin("file:///data/user/0/private") }
        assertFalse(Files.exists(directory.resolve("pending-export.properties")))
        Files.write(directory.resolve("pending-export.properties"), "destination=file:///data/user/0/private".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { state.recover { fail("Must not delete a private path") } }
    }
}
