package com.example.limbuszhcn.container

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeLibraryDeploymentTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun apk(vararg entries: Pair<String, ByteArray>): Path = temporary.newFile().toPath().also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }

    @Test fun sameApkRepairsMissingTruncatedAndSameSizeCorruptLibraries() {
        val root = temporary.newFolder().toPath(); val target = root.resolve("lib")
        val archive = apk("lib/arm64-v8a/libgoogle.so" to byteArrayOf(1,2,3))
        val deployment = NativeLibraryDeployment(target, listOf("arm64-v8a"))
        assertFalse(deployment.isComplete(listOf(archive))); deployment.install(listOf(archive))
        assertTrue(deployment.isComplete(listOf(archive)))
        for (bytes in listOf(byteArrayOf(1), byteArrayOf(4,5,6))) {
            Files.write(target.resolve("libgoogle.so"), bytes)
            assertFalse(deployment.isComplete(listOf(archive)))
            deployment.install(listOf(archive))
            assertArrayEquals(byteArrayOf(1,2,3), Files.readAllBytes(target.resolve("libgoogle.so")))
        }
        Files.delete(target.resolve("libgoogle.so")); assertFalse(deployment.isComplete(listOf(archive)))
        deployment.install(listOf(archive)); assertTrue(deployment.isComplete(listOf(archive)))
    }

    @Test fun interruptedDirectorySwapRestoresPreviousWithoutTouchingAccountData() {
        val root = temporary.newFolder().toPath(); val target = root.resolve("lib")
        val archive = apk("lib/arm64-v8a/libgoogle.so" to byteArrayOf(7))
        val deployment = NativeLibraryDeployment(target, listOf("arm64-v8a"))
        val account = root.resolve("accounts.db").also { Files.write(it, byteArrayOf(9)) }
        deployment.install(listOf(archive))
        Files.move(target, root.resolve(".lib.previous"))
        val abandoned = Files.createDirectories(root.resolve(".lib.staging-00000000-0000-0000-0000-000000000001"))
        Files.write(abandoned.resolve("partial"), byteArrayOf(3))
        // VEnvironment can recreate an empty target before the completeness check.
        Files.createDirectories(target)
        assertFalse(deployment.isComplete(listOf(archive)))
        deployment.install(listOf(archive))
        assertTrue(deployment.isComplete(listOf(archive))); assertFalse(Files.exists(abandoned))
        assertArrayEquals(byteArrayOf(9), Files.readAllBytes(account))
    }

    @Test fun rejectsUnsupportedAbiDuplicateLibrariesAndMissingGameCoreBeforeReplacingAnything() {
        val root = temporary.newFolder().toPath(); val target = root.resolve("lib")
        val valid = apk("lib/arm64-v8a/libil2cpp.so" to byteArrayOf(1))
        val deployment = NativeLibraryDeployment(target, listOf("arm64-v8a"), "libil2cpp.so")
        deployment.install(listOf(valid))
        val unsupported = apk("lib/x86/libil2cpp.so" to byteArrayOf(2))
        val missing = apk("lib/arm64-v8a/libother.so" to byteArrayOf(2))
        for (set in listOf(listOf(unsupported), listOf(missing), listOf(valid,valid))) {
            assertThrows(IllegalArgumentException::class.java) { deployment.install(set) }
            assertTrue(deployment.isComplete(listOf(valid)))
        }
    }
}
