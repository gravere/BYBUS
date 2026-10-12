package com.example.limbuszhcn.update

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameApkSetTest {
    private fun part(split: String = "", version: Long = 475, signer: String = GameApkSet.CERTIFICATE) = GameApk(
        Path.of("$split.apk"), PlayListing.PACKAGE, version, "1.115.0", split, null, emptySet(), setOf(signer), 26,
        split == "config.arm64_v8a", split == "UnityDataAssetPack")
    private fun complete() = listOf(part(), part("config.arm64_v8a"), part("UnityDataAssetPack"))

    @Test fun acceptsCompleteSetAndSameVersionRepairButRejectsPartialOrMixedInput() {
        assertEquals(475L, GameApkSet.validate(complete(), 475, 36).version)
        val bad = listOf(listOf(part()), complete().drop(1), complete() + part(),
            complete().map { if (it.split.isEmpty()) it.copy(version = 476) else it },
            complete().map { it.copy(signer = setOf("untrusted")) },
            complete().map { it.copy(packageName = "another.app") },
            complete().map { it.copy(minSdk = 99) },
            complete().map { if (it.split.isEmpty()) it.copy(requiredTypes = setOf("missing-type")) else it },
            complete().map { if (it.split.isEmpty()) it.copy(usesSplits = setOf("missing")) else it },
            complete().map { if (it.split.isNotEmpty()) it.copy(configFor = "missing") else it })
        for (set in bad) assertTrue(runCatching { GameApkSet.validate(set, 475, 36) }.isFailure)
        assertTrue(runCatching { GameApkSet.validate(complete(), 476, 36) }.isFailure)
    }

    private fun archive(root: Path, names: List<String>): Path {
        val zip = root.resolve("input.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { output -> names.forEach {
            output.putNextEntry(ZipEntry(it)); output.write(byteArrayOf(1, 2, 3)); output.closeEntry()
        } }
        return zip
    }

    @Test fun unpackUsesOwnedNamesAndRejectsTraversalObbAndTooManyVariants() {
        val root = Files.createTempDirectory("game-apk-set")
        val good = GamePackageFiles.unpack(archive(root, listOf("odd/base.apk", "split.apk", "manifest.json")), root)
        assertEquals(listOf("part-0.apk", "part-1.apk"), good.map { it.fileName.toString() })
        assertTrue(good.all { it.parent == root })
        for (names in listOf(listOf("../base.apk"), listOf("/base.apk"), listOf("C:/base.apk"),
                listOf("base.apk", "Android/obb/file.obb"), (0..32).map { "$it.apk" }, listOf("metadata.json"))) {
            assertTrue(runCatching { GamePackageFiles.unpack(archive(root, names), root) }.isFailure)
        }
    }

    @Test fun streamLimitStopsUnknownSizeInput() {
        val target = Files.createTempDirectory("game-apk-limit").resolve("input.apk")
        assertTrue(runCatching { GamePackageFiles.copy(ByteArray(1024).inputStream(), target, 8) }.isFailure)
        assertTrue(Files.size(target) <= 8)
    }

    @Test fun unsignedZipCannotPassOfficialSignatureCheck() {
        val root = Files.createTempDirectory("game-apk-signature")
        val zip = archive(root, listOf("AndroidManifest.xml"))
        assertTrue(runCatching { GameApkSet.read(zip, 36) }.isFailure)
    }
}
