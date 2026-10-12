package com.example.limbuszhcn.container

import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.gamefs.PatchInstallSummary
import com.example.limbuszhcn.gamefs.PatchUninstallSummary
import com.example.limbuszhcn.update.PatchManifest
import com.example.limbuszhcn.update.FileDigestSession
import com.example.limbuszhcn.measureStartup
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 将 GitHub 汉化包编译成运行时索引，并通过容器存储管理激活、刷新与卸载状态。
 *
 * @param cacheRoot 汉化下载、解压和运行时索引的宿主缓存根目录。
 * @param storage 容器内游戏本地化资源的访问边界。
 */
class ContainerPatchInstaller(
    private val cacheRoot: Path,
    private val storage: GameStorage,
    private val installedApkPaths: () -> List<String> = { emptyList() },
    private val releaseMetadata: (() -> String)? = null,
    private val onProgress: (String) -> Unit = {}
) {
    /**
     * 从指定 GitHub Releases 页面获取最新汉化包并激活完整运行时索引。
     *
     * @param releaseUrl 公开的 GitHub Releases 页面或兼容的 GitHub API 地址。
     * @param installedVersion 已知的本地汉化版本；非空时仅接受更高版本。
     * @return 本次索引编译、资源就绪度和激活状态摘要。
     */
    fun installFromGitHub(releaseUrl: String, installedVersion: Long? = null): PatchInstallSummary {
        val digests = FileDigestSession()
        val result = measureStartup("release_and_translation_files") {
            com.example.limbuszhcn.update.TranslationRepository(cacheRoot, releaseMetadata, digests::sha256, onProgress)
                .checkLatest(releaseUrl, installedVersion)
        }
        return activateVerifiedRedirect(result.outputDirectory, result.manifest, digests)
    }

    /** Revalidate archive, extracted files, source snapshot and index even when the API is offline. */
    fun verifyCachedForLaunch(): PatchInstallSummary {
        onProgress("更新暂不可用，校验本地汉化")
        val digests = FileDigestSession()
        val cached = measureStartup("cached_translation_files") {
            com.example.limbuszhcn.update.TranslationRepository(cacheRoot, fileDigest = digests::sha256)
                .verifiedForLaunch(activeInstallSummary()?.version)
        }
        return activateVerifiedRedirect(cached.outputDirectory, cached.manifest, digests).let { result ->
            if (result.indexActivated) result else activeInstallSummary()
                ?: result // Fully verified package, but the game still needs to prepare its resources.
        }
    }

    /**
     * 安装已经在本地准备好的补丁目录，主要用于离线测试和兼容旧补丁流程。
     *
     * @param patchDirectory 已解压且与清单匹配的补丁目录。
     * @param manifest 描述补丁版本、文件哈希和目标路径的清单。
     * @return 实际写入容器存储的文件摘要。
     */
    fun installPrepared(patchDirectory: Path, manifest: PatchManifest): PatchInstallSummary {
        return LegacyFilePatchMigration(storage).install(patchDirectory, manifest)
    }

    /**
     * 读取当前已通过完整性检查的运行时索引状态。
     *
     * @return 存在有效激活状态时返回安装摘要，否则返回 `null`。
     */
    fun activeInstallSummary(): PatchInstallSummary? {
        TranslationIndexStore(indexRoot()).activeManifest()?.let { properties ->
            return PatchInstallSummary(
                version = properties.getProperty("version", "unknown"),
                writtenFiles = properties.getProperty("availableSourceFileCount")
                    ?.toIntOrNull() ?: 0,
                indexActivated = true,
                candidateSourceFiles = properties.getProperty("candidateSourceFileCount")
                    ?.toIntOrNull() ?: 0,
                availableSourceFiles = properties.getProperty("availableSourceFileCount")
                    ?.toIntOrNull() ?: 0,
                sourceRecordCount = properties.getProperty("sourceRecordCount")
                    ?.toIntOrNull() ?: 0
            )
        }
        return null
    }

    /**
     * 使用最近一次缓存的 GitHub 汉化包重新编译并激活运行时索引。
     *
     * @return 重新编译后的资源统计和索引激活摘要。
     */
    fun refreshActiveRedirects(): PatchInstallSummary {
        val patchDirectory = latestPatchDirectory()
            ?: throw IllegalStateException("没有可刷新重定向的汉化缓存，请先安装汉化包")
        val manifestFile = patchDirectory.resolve("manifest.json")
        require(manifestFile.isRegularFile()) {
            "汉化缓存缺少 manifest.json: $manifestFile"
        }
        val manifest = PatchManifest.fromJson(manifestFile.readText(Charsets.UTF_8))
        return activateRedirect(patchDirectory, manifest)
    }

    /**
     * 将汉化 JSON 与设备上的日文资源配对，生成只包含明确文本映射的运行时索引。
     *
     * @param patchDirectory 已解压的汉化包缓存目录。
     * @param manifest 汉化包文件清单和版本信息。
     * @return 候选资源、可读日文源文件和索引激活结果。
     */
    fun activateRedirect(patchDirectory: Path, manifest: PatchManifest): PatchInstallSummary {
        return activateVerifiedRedirect(patchDirectory, manifest, FileDigestSession())
    }

    private fun activateVerifiedRedirect(patchDirectory: Path, manifest: PatchManifest,
                                         digests: FileDigestSession): PatchInstallSummary {
        onProgress("校验游戏文本与汉化索引")
        val preparedRoot = patchDirectory.toAbsolutePath().normalize()
        manifest.entries.forEach {
            require(preparedRoot.resolve(it.cachePath).normalize().startsWith(preparedRoot)) {
                "汉化清单文件超出准备目录"
            }
        }
        val store = TranslationIndexStore(indexRoot())
        val active = measureStartup("active_index_validation") { store.activeManifest() }
        val buildCache = TranslationBuildCache(indexRoot().resolve("build-receipt.properties"))
        val inputs = manifest.entries.mapNotNull { entry ->
            val source = patchDirectory.resolve(entry.cachePath).toAbsolutePath().normalize()
            if (!source.isRegularFile() || !source.name.endsWith(".json", ignoreCase = true)) null
            else TranslationBuildCache.Input(source, japaneseSourceFor(storage.resolvePatchTarget(entry.targetPath)))
        }
        val bundledHints = measureStartup("bundled_hints") { BundledBattleHints.read(installedApkPaths()) }
        val sourceNameCounts = inputs.groupingBy { it.originalPath.substringAfterLast('/') }.eachCount()
        fun inputIdentity(hints: Map<String, String>): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            hints.toSortedMap().forEach { (name, text) ->
                digest.update(name.toByteArray(Charsets.UTF_8)); digest.update(0)
                digest.update(text.toByteArray(Charsets.UTF_8)); digest.update(0)
            }
            return "${manifest.requestedVersion}:${manifest.archiveSha256}:" +
                digest.digest().joinToString("") { "%02x".format(it) }
        }
        val identity = inputIdentity(bundledHints)
        val fingerprint = measureStartup("translation_input_validation") {
            buildCache.fingerprint(identity, inputs, storage, digests::sha256)
        }
        // Legacy overlays must also be cleared on the fast path.
        Files.deleteIfExists(activeRedirectFile())
        Files.deleteIfExists(pendingRedirectFile())
        if (buildCache.matches(fingerprint, active)) {
            return PatchInstallSummary(
                version = active!!.getProperty("version"),
                writtenFiles = active.getProperty("availableSourceFileCount").toInt(),
                candidateSourceFiles = active.getProperty("candidateSourceFileCount").toInt(),
                availableSourceFiles = active.getProperty("availableSourceFileCount").toInt(),
                sourceRecordCount = active.getProperty("sourceRecordCount").toInt(),
                reusedIndex = true
            )
        }
        onProgress("适配更新后的游戏文本")
        val rebuildStarted = System.nanoTime()
        var activeFiles = 0
        val candidateFiles = manifest.entries.count { it.cachePath.endsWith(".json", ignoreCase = true) }
        var availableFiles = 0
        val indexCompiler = TranslationIndexCompiler()
        manifest.entries.forEach { entry ->
            val source = patchDirectory.resolve(entry.cachePath).toAbsolutePath().normalize()
            if (!source.isRegularFile() || !source.name.endsWith(".json", ignoreCase = true)) {
                return@forEach
            }
            val target = storage.resolvePatchTarget(entry.targetPath)
            val japaneseSource = japaneseSourceFor(target)
            // Only uniquely named manifest resources can be matched to a bundled name.
            val bundledName = japaneseSource.substringAfterLast('/')
            val bundled = bundledHints[bundledName].takeIf {
                sourceNameCounts[bundledName] == 1
            }
            if (bundled != null) {
                runCatching { TranslationJsonPairer.pair(japaneseSource, bundled, source.readText(Charsets.UTF_8)) }
                    .getOrNull()?.takeIf { it.sufficientlyPaired }?.records?.forEach(indexCompiler::add)
            }
            val japaneseJson = storage.read(japaneseSource) ?: return@forEach
            val pair = runCatching { TranslationJsonPairer.pair(
                path = japaneseSource,
                originalJson = japaneseJson.toString(Charsets.UTF_8),
                translationJson = source.readText(Charsets.UTF_8)
            ) }.getOrNull() ?: return@forEach
            if (pair.sufficientlyPaired) availableFiles += 1
            // Coverage is a readiness gate, not evidence that individually paired
            // rows are wrong. Keep proven rows from partially translated tables.
            if (pair.records.isNotEmpty()) {
                pair.records.forEach(indexCompiler::add)
                activeFiles += 1
            }
        }
        // The production path is runtime-only. Stale merged-file redirects must not survive an
        // upgrade from the earlier experiment.
        Files.deleteIfExists(activeRedirectFile())
        Files.deleteIfExists(pendingRedirectFile())
        val compiledIndex = indexCompiler.compile(
            version = manifest.requestedVersion,
            archiveSha256 = manifest.archiveSha256,
            candidateSourceFileCount = candidateFiles,
            availableSourceFileCount = availableFiles
        )
        val resourcesReady = hasSufficientJapaneseSources(availableFiles, candidateFiles)
        val previousIndexAvailable = active != null
        val previousCount = active?.getProperty("sourceRecordCount")?.toIntOrNull() ?: 0
        val recordCountSafe = previousCount == 0 || compiledIndex.sourceRecordCount * 100L >= previousCount * 90L
        val stableInputs = fingerprint == buildCache.fingerprint(identity, inputs, storage) &&
            identity == inputIdentity(BundledBattleHints.read(installedApkPaths()))
        val activated = resourcesReady && recordCountSafe && stableInputs && compiledIndex.uniqueEntries.isNotEmpty()
        if (activated) {
            val activeIndex = TranslationIndexStore(indexRoot()).activate(compiledIndex)
            runCatching { android.util.Log.i(
                TAG,
                "Activated Japanese full-text translation index version=${manifest.requestedVersion}" +
                    " unique=${compiledIndex.uniqueEntries.size}" +
                    " terms=${compiledIndex.termEntries.size}" +
                    " sources=${compiledIndex.sourceRecordCount}" +
                    " conflicts=${compiledIndex.conflictCount}" +
                    " dominant=${compiledIndex.dominantEntryCount}" +
                    " path=$activeIndex"
            ) }
            patchDirectory.resolve(ACTIVE_MARKER_FILE).writeText("runtime-index-jp-ui\n", Charsets.UTF_8)
            store.activeManifest()?.let { buildCache.save(fingerprint, it) }
        } else if (!resourcesReady) {
            runCatching { android.util.Log.w(
                TAG,
                "Japanese resources not ready version=${manifest.requestedVersion}" +
                    " available=$availableFiles candidate=$candidateFiles" +
                    " preserved=$previousIndexAvailable"
            ) }
        } else {
            runCatching { android.util.Log.w(
                TAG,
                "Translation index has no unambiguous entries version=${manifest.requestedVersion}" +
                    " preserved=$previousIndexAvailable"
            ) }
        }
        runCatching {
            val retained = store.pruneAndRetainedVersions() + manifest.requestedVersion
            com.example.limbuszhcn.update.TranslationRepository(cacheRoot).prune(retained)
        }
        runCatching { com.example.limbuszhcn.PersistentDiagnosticLog.info("LimbusStartup",
            "phase=translation_index_rebuild elapsedMs=${(System.nanoTime() - rebuildStarted) / 1_000_000} activated=$activated") }
        return PatchInstallSummary(
            version = manifest.requestedVersion,
            writtenFiles = activeFiles,
            indexActivated = activated,
            candidateSourceFiles = candidateFiles,
            availableSourceFiles = availableFiles,
            sourceRecordCount = compiledIndex.sourceRecordCount,
            previousIndexPreserved = !activated && previousIndexAvailable,
            pendingReason = when {
                activated -> null
                !stableInputs -> "资源正在变化，保留已有汉化，请在游戏下载结束后重试"
                !resourcesReady -> "有效日文资源或译文配对不足，需要完成资源下载"
                !recordCountSafe -> "新索引记录数量异常减少，已保留上一版汉化"
                else -> "尚无可用的文本映射"
            }
        )
    }

    /**
     * 仅停用当前运行时索引；不修改任何游戏资源，允许重复执行。
     *
     * @return 删除或恢复文件的卸载摘要。
     */
    fun uninstallLatest(): PatchUninstallSummary {
        val active = TranslationIndexStore(indexRoot()).activeManifest()
        TranslationIndexStore(indexRoot()).deactivate()
        Files.deleteIfExists(activeRedirectFile())
        Files.deleteIfExists(pendingRedirectFile())
        Files.deleteIfExists(indexRoot().resolve("build-receipt.properties"))
        // No game-resource writes, and no selection based on the latest cached release.
        return PatchUninstallSummary(active?.getProperty("version") ?: "未启用", 0)
    }

    /** Explicit legacy migration only; not called by runtime deactivation. */
    fun restorePrepared(patchDirectory: Path, manifest: PatchManifest): PatchUninstallSummary =
        LegacyFilePatchMigration(storage).restore(patchDirectory, manifest)

    private fun activeRedirectFile(): Path =
        cacheRoot.resolve(ACTIVE_REDIRECT_FILE)

    private fun pendingRedirectFile(): Path =
        cacheRoot.resolve(PENDING_REDIRECT_FILE)

    private fun indexRoot(): Path =
        cacheRoot.resolve(TRANSLATION_INDEX_DIR)

    private fun japaneseSourceFor(resolvedTarget: String): String {
        val normalized = resolvedTarget.replace('\\', '/')
        val root = storage.rootPath().trimEnd('/').replace('\\', '/')
        val relative = normalized.removePrefix(root).removePrefix("/")
        val withoutLanguage = relative
            .removePrefix("en/")
            .removePrefix("kr/")
            .removePrefix("jp/")
        val slash = withoutLanguage.lastIndexOf('/')
        val directory = if (slash >= 0) withoutLanguage.substring(0, slash + 1) else ""
        val fileName = if (slash >= 0) withoutLanguage.substring(slash + 1) else withoutLanguage
        val baseName = LANGUAGE_PREFIX_REGEX.replace(fileName, "")
        return "$root/jp/${directory}JP_$baseName"
    }

    private fun latestPatchDirectory(): Path? {
        val patchRoot = cacheRoot.resolve("patch-cache")
        if (!patchRoot.isDirectory()) {
            return null
        }
        return Files.list(patchRoot).use { paths ->
            paths
                .filter { it.isDirectory() && it.name.toLongOrNull() != null && it.resolve("manifest.json").isRegularFile() }
                .sorted { left, right -> comparePatchDirectories(right, left) }
                .findFirst()
                .orElse(null)
        }
    }

    private fun comparePatchDirectories(left: Path, right: Path): Int {
        val leftVersion = left.name.toLongOrNull()
        val rightVersion = right.name.toLongOrNull()
        if (leftVersion != null && rightVersion != null) {
            return leftVersion.compareTo(rightVersion)
        }
        return Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right))
    }

    companion object {
        private const val ACTIVE_REDIRECT_FILE = "active-redirects.tsv"
        private const val PENDING_REDIRECT_FILE = "pending-redirects.tsv"
        private const val ACTIVE_MARKER_FILE = ".active-redirect"
        private const val TRANSLATION_INDEX_DIR = "translation-index"
        private const val TAG = "LimbusZhCN"
        private val LANGUAGE_PREFIX_REGEX = Regex("^(EN|KR|JP)_")
    }

}

internal fun hasSufficientJapaneseSources(available: Int, candidate: Int): Boolean =
    candidate > 0 && available * 100L >= candidate * 90L
