package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.LimbusStoragePaths
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LimbusStoragePathsTest {
    @Test fun secondaryAndroidUserKeepsTheSameGuestDirectoryAsTheCoverageReader() {
        for (hostUser in listOf(0, 10, 11, 999)) {
            val storage = File("/data/user/$hostUser/io.github.gravere.bybus/virtual/storage/emulated/0")
            val mappings = linkedMapOf<String, String>()
            LimbusStoragePaths.redirectPackageDirectories("com.ProjectMoon.LimbusCompany", storage) { source, target ->
                assertNull(mappings.put(source, target))
            }
            assertEquals(3, mappings.size)
            for (area in listOf("data", "obb", "media")) {
                val relative = "Android/$area/com.ProjectMoon.LimbusCompany"
                assertEquals(File(storage, relative).absolutePath,
                    mappings["/storage/emulated/0/$relative/"])
            }
            // Only app-specific aliases: no access to another user's storage root.
            assertFalse(mappings.containsKey("/storage/emulated/0/"))
            assertFalse(mappings.keys.any { "/Android/data/io.github.gravere.bybus" in it })
        }
    }

    @Test fun unrelatedGuestDoesNotAcquireGameStorageAliases() {
        LimbusStoragePaths.redirectPackageDirectories("com.google.android.gms", File("/virtual/storage")) { _, _ ->
            fail("Unrelated guest was redirected")
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun arbitraryStorageAreasAreRejected() {
        LimbusStoragePaths.visiblePackageDirectory("../..")
    }
}
