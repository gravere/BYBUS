package com.example.limbuszhcn.container

import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.storage.OperationLock
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.io.path.writeText

class OperationRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun differentCallersCannotWriteAtTheSameTime() {
        val root = temporary.root.toPath()
        val pool = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val active = AtomicInteger(); val peak = AtomicInteger()
        try {
            val first = pool.submit {
                OperationLock.run(root) {
                    peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); active.decrementAndGet()
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = pool.submit {
                OperationLock.run(root) {
                    peak.accumulateAndGet(active.incrementAndGet(), ::maxOf); active.decrementAndGet()
                }
            }
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS)
            assertEquals(1, peak.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun interruptedDirectoryReplacementCanResumeWithACompleteCandidate() {
        val root = temporary.root.toPath()
        val previous = Files.createDirectory(root.resolve(".libs.previous"))
        previous.resolve("old.so").writeText("old-complete")
        val staged = Files.createDirectory(root.resolve(".libs.staging"))
        staged.resolve("new.so").writeText("new-complete")
        AtomicFiles.replaceDirectory(staged, root.resolve("libs"))
        assertEquals("new-complete", root.resolve("libs/new.so").readText())
        assertEquals("old-complete", previous.resolve("old.so").readText())
    }

    @Test fun runtimeReceiptsAreBoundToOneLaunchRequest() {
        val root = temporary.root.toPath()
        val receipt = RuntimeReceipt(root)
        receipt.beginLaunch()
        val first = root.resolve("launch.request").readText().trim()
        root.resolve("runtime-state.properties").writeText("request=$first\nstate=hooks_installed\nnativeProfile=verified-test")
        assertTrue(receipt.description().contains("文本接管已加载"))
        receipt.beginLaunch()
        root.resolve("runtime-state.properties").writeText("request=$first\nstate=hooks_installed")
        assertFalse(receipt.description().contains("文本接管已加载"))
    }

    @Test fun pruningRetainsActiveAndPreviousIndexGenerations() {
        val root = temporary.root.toPath()
        val store = TranslationIndexStore(root)
        fun index(version: String) = CompiledTranslationIndex(version, "archive", "input$version",
            mapOf("名前" to "名称$version"), emptyMap(), 1, 0, 0, 1, 1)
        store.activate(index("100")); store.activate(index("200")); store.activate(index("300"))
        assertEquals(setOf("200", "300"), store.pruneAndRetainedVersions())
        assertEquals(2, Files.list(root.resolve("versions")).use { it.count() }.toInt())
        assertEquals("300", store.activeManifest()!!.getProperty("version"))
    }

    @Test fun releasePruningPreservesActivePreviousAndLegacyRestorationData() {
        val root = temporary.root.toPath()
        val patches = Files.createDirectories(root.resolve("patch-cache"))
        val downloads = Files.createDirectories(root.resolve("downloads"))
        listOf("100", "200", "300", "400").forEach { version ->
            Files.createDirectory(patches.resolve(version))
            downloads.resolve("LimbusLocalize_$version.7z").writeText("archive")
        }
        Files.createDirectory(patches.resolve("100/baseline"))
        Files.createDirectory(patches.resolve(".200.previous"))
        val interrupted = Files.createDirectory(patches.resolve(".400.12345678-1234-1234-1234-123456789012.tmp"))
        com.example.limbuszhcn.update.TranslationRepository(root).prune(setOf("300", "400"))
        assertTrue(Files.exists(patches.resolve("100/baseline")))
        assertTrue(Files.exists(downloads.resolve("LimbusLocalize_100.7z")))
        assertFalse(Files.exists(patches.resolve("200")))
        assertFalse(Files.exists(patches.resolve(".200.previous")))
        assertFalse(Files.exists(interrupted))
        assertTrue(Files.exists(patches.resolve("300")))
        assertTrue(Files.exists(patches.resolve("400")))
    }
}
