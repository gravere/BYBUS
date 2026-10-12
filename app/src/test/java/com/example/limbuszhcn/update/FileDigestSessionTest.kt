package com.example.limbuszhcn.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class FileDigestSessionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun oneOperationSharesReadsButChangedAndNewLaunchFilesAreFullyRead() {
        val file = folder.newFile().toPath()
        Files.write(file, "old".toByteArray())
        val modified = Files.getLastModifiedTime(file)
        var reads = 0
        val session = FileDigestSession { reads++; it.sha256() }
        val before = session.sha256(file)
        assertEquals(before, session.sha256(file))
        assertEquals(1, reads)
        Files.write(file, "new".toByteArray())
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified.toMillis() + 2_000))
        assertNotEquals(before, session.sha256(file))
        assertEquals(2, reads)
        // Even a restored timestamp cannot hide changes from the next launch's first full read.
        Files.write(file, "old".toByteArray())
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified.toMillis() + 2_000))
        assertEquals(before, FileDigestSession { reads++; it.sha256() }.sha256(file))
        assertEquals(3, reads)
    }

    @Test fun aFileChangingWhileReadNeverBecomesAVerifiedDigest() {
        val file = folder.newFile().toPath()
        Files.write(file, "before".toByteArray())
        val session = FileDigestSession {
            val digest = it.sha256()
            Files.write(it, "after-change".toByteArray())
            digest
        }
        assertThrows(IOException::class.java) { session.sha256(file) }
    }

    @Test fun localCandidateOnlySelectsWaitingBudgetAndDoesNotAuthorizeCorruptFiles() {
        val root = folder.root.toPath()
        val repo = TranslationRepository(root)
        assertFalse(repo.hasLaunchCandidate())
        Files.createDirectories(root.resolve("downloads"))
        Files.write(root.resolve("downloads/LimbusLocalize_100.7z"), byteArrayOf(1))
        assertTrue(repo.hasLaunchCandidate())
        assertThrows(IllegalStateException::class.java) { repo.verifiedForLaunch(null) }
    }
}
