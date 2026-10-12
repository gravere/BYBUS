package com.example.limbuszhcn.container

import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.gamefs.PatchInstallSummary
import com.example.limbuszhcn.gamefs.PatchUninstallSummary
import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.update.PatchManifest
import com.example.limbuszhcn.update.sha256
import com.example.limbuszhcn.update.toLowerHex
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

/** Explicit legacy migration only. Never invoked by the runtime-index deactivate action. */
internal class LegacyFilePatchMigration(private val storage: GameStorage) {
    fun install(directory: Path, manifest: PatchManifest): PatchInstallSummary {
        val root = directory.toAbsolutePath().normalize()
        val baseline = root.resolve("baseline")
        require(!Files.exists(baseline)) { "已有旧补丁基线，请先完成显式恢复" }
        val files = manifest.entries.map { entry ->
            val source = root.resolve(entry.cachePath).normalize()
            require(source.startsWith(root) && Files.isRegularFile(source)) { "Invalid prepared patch source" }
            storage.resolvePatchTarget(entry.targetPath) to source
        }
        Files.createDirectories(baseline)
        val receipt = Properties().apply {
            setProperty("version", manifest.requestedVersion)
            setProperty("archive", manifest.archiveSha256)
        }
        files.forEach { (target, source) ->
            val key = Base64.getUrlEncoder().withoutPadding().encodeToString(target.toByteArray(Charsets.UTF_8))
            val original = storage.read(target)
            if (original != null) {
                Files.write(baseline.resolve(key), original)
                receipt.setProperty("original.$key", hash(original))
            } else receipt.setProperty("original.$key", "missing")
            receipt.setProperty("patched.$key", source.sha256())
        }
        // The complete restoration plan is durable before the first game write.
        AtomicFiles.properties(baseline.resolve("ownership.properties"), receipt)
        files.forEach { (target, source) -> storage.write(target, source) }
        return PatchInstallSummary(manifest.requestedVersion, files.size, indexActivated = false)
    }

    fun restore(directory: Path, manifest: PatchManifest): PatchUninstallSummary {
        val baseline = directory.resolve("baseline")
        val receipt = AtomicFiles.readProperties(baseline.resolve("ownership.properties"))
        require(receipt.getProperty("version") == manifest.requestedVersion &&
            receipt.getProperty("archive") == manifest.archiveSha256) { "旧补丁所有权记录与版本不匹配" }
        var restored = 0
        val plans = receipt.stringPropertyNames().filter { it.startsWith("patched.") }.map { property ->
            val key = property.removePrefix("patched.")
            val target = String(Base64.getUrlDecoder().decode(key), Charsets.UTF_8)
            val currentHash = storage.read(target)?.let(::hash) ?: "missing"
            val originalHash = receipt.getProperty("original.$key")
            require(currentHash == originalHash || currentHash == receipt.getProperty(property)) {
                "游戏资源已在补丁安装后发生变化，拒绝覆盖；请重新下载该资源"
            }
            if (currentHash != originalHash && originalHash != "missing") {
                require(baseline.resolve(key).sha256() == originalHash) { "旧补丁基线校验失败" }
            }
            Triple(key, target, currentHash != originalHash)
        }
        plans.forEach { (key, target, changed) ->
            if (changed) {
                val originalHash = receipt.getProperty("original.$key")
                if (originalHash == "missing") storage.delete(target)
                else {
                    val original = baseline.resolve(key)
                    storage.write(target, original)
                }
                restored++
            }
        }
        return PatchUninstallSummary(manifest.requestedVersion, restored)
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()
}
