package com.example.limbuszhcn.container

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class BundledComponentCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root.toPath().resolve("bundled")
    private fun apk(version: String): Path = root.resolve("$version/component.apk").also {
        Files.createDirectories(it.parent)
        it.writeText("verified fixture")
    }

    @Test fun keepsBothConfiguredAndInstalledGenerationsAndIgnoresOtherData() {
        val old = apk("0.3.6")
        val installed = apk("0.3.16")
        val configured = apk("0.3.17")
        val unrelated = apk("manual-files")
        val account = temporary.root.toPath().resolve("account.db").also { it.writeText("untouched") }
        assertEquals(1, pruneUnusedComponentBundles(root, listOf(installed, configured)))
        assertFalse(Files.exists(old))
        listOf(installed, configured, unrelated, account).forEach { assertTrue(Files.exists(it)) }
        assertEquals(0, pruneUnusedComponentBundles(root, listOf(installed, configured)))
    }

    @Test fun incompleteReferencesNeverAuthorizeRemovingKnownGoodBundles() {
        val old = apk("0.3.6")
        val configured = apk("0.3.17")
        assertThrows(Exception::class.java) {
            pruneUnusedComponentBundles(root, listOf(configured, root.resolve("missing.apk")))
        }
        assertTrue(Files.exists(old))
        assertEquals(0, pruneUnusedComponentBundles(root, emptyList()))
        assertTrue(Files.exists(configured))
    }
}
