package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.InstallerSourcePolicy
import org.junit.Assert.*
import org.junit.Test

class InstallerSourcePolicyTest {
    @Test fun importedGamesKeepTheirActualChannelIncludingSideloads() {
        for (installer in listOf("com.android.vending", "com.apkpure.aegon", "com.android.packageinstaller", null)) {
            assertEquals(installer, InstallerSourcePolicy.query(true, false, true) { installer })
        }
    }
    @Test fun unknownPackageThrowsInsteadOfInventingPlayStore() {
        assertThrows(IllegalArgumentException::class.java) {
            InstallerSourcePolicy.query(false, false, false) { fail("Must not query an invisible host package"); null }
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstallerSourcePolicy.query(false, true, false) { throw IllegalArgumentException("unknown") }
        }
    }
    @Test fun bundledComponentsDoNotBorrowAnUnrelatedHostInstallSource() {
        assertNull(InstallerSourcePolicy.query(true, true, false) { fail("Host Google is a different installation"); null })
        assertNull(InstallerSourcePolicy.query(true, false, true) { throw IllegalArgumentException("host uninstalled") })
    }
    @Test fun genuinePermissionAndServiceFailuresArePreserved() {
        val denied = SecurityException("denied")
        val failure = assertThrows(SecurityException::class.java) {
            InstallerSourcePolicy.query(true, false, true) { throw denied }
        }
        assertSame(denied, failure)
    }
}
