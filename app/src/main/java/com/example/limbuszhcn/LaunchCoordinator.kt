package com.example.limbuszhcn

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.limbuszhcn.container.AndroidContainerRuntimeFactory
import com.example.limbuszhcn.container.AndroidInstalledGameImporter
import com.example.limbuszhcn.container.ContainerPatchInstaller
import com.example.limbuszhcn.container.ContainerStatus
import com.example.limbuszhcn.container.GameImportRequest
import com.example.limbuszhcn.gamefs.PatchInstallSummary
import java.io.File
import java.util.concurrent.Executors
import com.example.limbuszhcn.update.GameUpdateSource
import com.example.limbuszhcn.update.GameUpdateCatalog
import com.example.limbuszhcn.update.GameStores
import com.example.limbuszhcn.update.GameInstallSource
import com.example.limbuszhcn.update.GamePackageInstaller
import com.example.limbuszhcn.update.GameInstallReview
import com.example.limbuszhcn.update.PreparedGameUpdate
import android.content.Intent
import android.content.pm.PackageInstaller
import com.example.limbuszhcn.translation.*

internal data class LauncherUiState(
    val sources: SourceState = SourceState(),
    val ai: AiUiState = AiUiState(),
    val coverage: CoverageState = CoverageState(),
    val aiUpstreamOffer: AiUpstreamOffer? = null,
    val aiPreparingLaunch: Boolean = false,
    val container: ContainerStatus? = null,
    val installedVersion: Long? = null,
    val installedVersionName: String? = null,
    val canResumeGame: Boolean = false,
    val nativeCompatibilityIssue: String? = null,
    val gameUpdate: GameUpdateCatalog = GameUpdateCatalog(),
    val installSource: GameInstallSource = GameInstallSource(),
    val preferredGameSource: GameUpdateSource? = null,
    val updatesRequest: Long = 0,
    val gameInstallReview: GameInstallReview? = null,
    val gameInstallPending: Boolean = false,
    val gameInstallConfirmation: Intent? = null,
    val translation: PatchInstallSummary? = null,
    val resourcePreparation: PatchInstallSummary? = null,
    val resourcePreparationRequest: Long = 0,
    val googlePayment: com.example.limbuszhcn.container.GooglePaymentState? = null,
    val googlePaymentIssue: String? = null,
    val compatibility: DeviceCompatibilityReport? = null,
    val runtimeStatus: com.example.limbuszhcn.container.RuntimeLaunchStatus = com.example.limbuszhcn.container.RuntimeLaunchStatus(),
    val repair: RepairState = RepairState(),
    val clearing: DataClearState = DataClearState(),
    val busy: Boolean = false,
    val stage: String = "正在读取状态",
    val launchStep: Int = -1,
    val message: String = "正在检测手机上的边狱巴士",
    val failed: Boolean = false,
    val exportRequest: File? = null
) {
    val translationLabel: String? get() = translation?.let {
        if (sources.selected.isOfficial) it.version else sources.selected.name
    }
}

/** Application-owned operations survive Activity replacement; one shared state and file lock. */
internal class LaunchCoordinator private constructor(application: Application) {
    var state by mutableStateOf(LauncherUiState())
        private set
    private val app = application
    val github = com.example.limbuszhcn.tracking.GitHubProjectRepository(
        app.filesDir.toPath().resolve("project-tracker/github.json"))
    private val gameCompatibility = com.example.limbuszhcn.container.GameLaunchCompatibility(app)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "limbus-launcher") }
    private val versionWorker = Executors.newFixedThreadPool(2) { task -> Thread(task, "limbus-game-version") }
    private val releaseCheck = com.example.limbuszhcn.tracking.LaunchReleaseCheck(
        Executors.newSingleThreadExecutor { task -> Thread(task, "limbus-release-check") },
        { measureStartup("release_metadata") { github.releaseForLaunch() } })
    private val updatePreferences = app.getSharedPreferences("game-updates", 0)
    private val preparationPreferences = app.getSharedPreferences("resource-preparation", 0)
    private val aiUpdatePreferences = app.getSharedPreferences("ai-upstream-update", 0)
    private val translationCache get() = app.filesDir.toPath().resolve("translation-cache")
    private val translationSources by lazy { TranslationSources(translationCache) }
    private val aiSettings by lazy { AiSettingsStore(app) }
    private val aiCancelled = java.util.concurrent.atomic.AtomicBoolean()
    private val modelsWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "bybus-models") }
    private val aiSettingsWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "bybus-ai-settings") }
    private val coverageWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "bybus-coverage") }
    private val coverageRequest = java.util.concurrent.atomic.AtomicLong()
    private var modelsTask: java.util.concurrent.Future<*>? = null
    private val modelsRequest = java.util.concurrent.atomic.AtomicLong()
    private val gameUpdates = GameUpdateSource.entries.associateWith { source ->
        com.example.limbuszhcn.update.GameUpdateRepository(app.filesDir.toPath().resolve("game-update-${source.key}.properties"), source = source)
    }
    private val diagnosticExport = DiagnosticExportState(app.filesDir.toPath().resolve("diagnostics"))
    private var exportSaveQueued = false
    private val packageUpdates = GamePackageInstaller(app)
    private var preparedUpdate: PreparedGameUpdate? = null
    private val runtime by lazy {
        AndroidContainerRuntimeFactory.create(app, app.filesDir.toPath().resolve("container-workspace"))
    }
    private fun installer(metadata: () -> String = github::releaseForLaunch, onProgress: (String) -> Unit = {}) = ContainerPatchInstaller(
        app.filesDir.toPath().resolve("translation-cache"), runtime.storage(),
        installedApkPaths = {
            AndroidInstalledGameImporter(app.packageManager, File(app.filesDir, "container-imports"))
                .copyInstalledGame().splitApkPaths
        },
        releaseMetadata = metadata,
        onProgress = onProgress
    )

    init {
        worker.execute {
            runCatching { com.example.limbuszhcn.storage.OperationLock.run(app.filesDir.toPath()) { packageUpdates.recover() } }
                .onSuccess { pending -> publish { it.copy(gameInstallPending = pending) } }
                .onFailure { PersistentDiagnosticLog.error(TAG, "Recover package update", it) }
        }
        refresh()
        queueDiagnosticSave(recovering = true)
    }

    private fun publish(change: (LauncherUiState) -> LauncherUiState) {
        main.post { state = change(state) }
    }

    private fun stage(label: String, step: Int = -1) = publish { it.copy(stage = label, launchStep = step) }

    private fun perform(label: String, allowPackageUpdate: Boolean = false,
        preserveFailure: Boolean = false, task: () -> String) {
        if (state.busy || exportSaveQueued) return
        if (!allowPackageUpdate && (state.gameInstallPending || state.gameInstallReview != null)) {
            state = state.copy(updatesRequest = nextUpdatesRequest(state))
            return
        }
        state = state.beginOperation(label, preserveFailure)
        worker.execute {
            val start = System.nanoTime()
            val result = runCatching { com.example.limbuszhcn.storage.OperationLock.run(app.filesDir.toPath()) {
                check(!com.example.limbuszhcn.migration.MigrationAccess.preferences(app).getBoolean("pending", false)) {
                    "旧数据迁移尚未完成，请先继续导入"
                }
                task()
            } }
            result.exceptionOrNull()?.let { PersistentDiagnosticLog.error(TAG, label, it) }
            PersistentDiagnosticLog.info(TAG, "Task finished stage=$label elapsedMs=${(System.nanoTime() - start) / 1_000_000} success=${result.isSuccess}")
            publish { it.finishOperation(result.getOrElse { error -> error.userMessage() },
                failed = result.isFailure, preserveFailure = preserveFailure) }
        }
    }

    private fun readGameStatus(): Pair<ContainerStatus, Long?> {
        val installed = runCatching { AndroidInstalledGameImporter.installedVersionCode(app.packageManager) }.getOrNull()
        val container = runtime.status().withInstalledVersion(installed)
        val name = runCatching { AndroidInstalledGameImporter.installedVersionName(app.packageManager) }.getOrNull()
        val source = if (installed != null) GameStores.installedSource(app) else GameInstallSource()
        val preferred = GameUpdateSource.fromKey(updatePreferences.getString("preferred", null)) ?: GameStores.sourceFor(source)
        val canResume = runtime.hasRunningGame()
        publish { it.copy(container = container, installedVersion = installed, installedVersionName = name,
            installSource = source, preferredGameSource = preferred, canResumeGame = canResume) }
        return container to installed
    }

    private fun publishSources(message: String? = null) {
        val sources = translationSources.read().copy(message = message)
        val config = aiSettings.config(); val keyPresent = aiSettings.hasKey()
        publish {
            val changed = sources.selected.id != it.sources.selected.id || sources.selected.revision != it.sources.selected.revision ||
                sources.officialDownloaded != it.sources.officialDownloaded
            if (changed) coverageRequest.incrementAndGet()
            it.copy(sources = sources, coverage = if (changed) CoverageState() else it.coverage,
                ai = it.ai.copy(config = config, hasKey = keyPresent))
        }
    }

    private fun prepareTranslation(patch: ContainerPatchInstaller, repair: Boolean = false): PatchInstallSummary {
        coverageRequest.incrementAndGet()
        publish { it.copy(coverage = CoverageState()) }
        val source = translationSources.read().selected
        val result = SourceActivation(translationCache).run(source.id, source.revision.takeUnless { source.isOfficial }) {
            if (!source.isOfficial) {
                val prepared = translationSources.prepare(source)
                patch.activateImported(prepared.directory, prepared.manifest, repair)
            } else if (repair) runCatching { patch.verifyCachedForLaunch(forceRebuild = true) }.getOrElse {
                if (it is java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted) throw it
                patch.installFromGitHub(RELEASE_URL, forceRebuild = true)
            } else runCatching { patch.installFromGitHub(RELEASE_URL) }.getOrElse { error ->
                if (error is java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted) throw error
                val cached = runCatching { patch.verifyCachedForLaunch() }
                cached.getOrNull()?.also { PersistentDiagnosticLog.warn(TAG, "Update unavailable; verified cached translation", error) }
                    ?: throw IllegalStateException("${error.updateFailureLabel()}；本地汉化未通过完整校验", cached.exceptionOrNull() ?: error)
            }
        }
        runCatching { translationSources.pruneUnused() }.onFailure {
            PersistentDiagnosticLog.warn(TAG, "Unable to clean obsolete custom translations", it)
        }
        return result
    }

    fun selectTranslationSource(id: String) = perform("切换汉化源") {
        stopGame()
        translationSources.select(id)
        preparationPreferences.edit().remove("pending").apply()
        publish { it.copy(translation = null, resourcePreparation = null) }
        publishSources("已切换，下次启动使用此汉化源")
        "汉化源已切换，游戏资源与账号保留"
    }

    fun importTranslation(uri: Uri, replaceId: String?) = perform("导入汉化包") {
        stopGame()
        val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.take(80) else null
        } ?: "本地汉化.zip"
        app.contentResolver.openInputStream(uri).use { input ->
            translationSources.import(requireNotNull(input) { "无法读取汉化包" }, name.take(64), replaceId)
        }
        publish { it.copy(translation = null, resourcePreparation = null) }
        publishSources("已导入，下次启动使用此汉化包")
        "汉化包已导入"
    }

    fun renameTranslation(id: String, name: String) = perform("修改汉化名称") {
        require(id != "zero") { "默认来源名称保持不变" }
        translationSources.rename(id, name); publishSources(); "名称已保存"
    }

    fun setTranslationIcon(id: String, uri: Uri?) = perform("设置汉化图标") {
        val png = uri?.let {
            val bytes = java.io.ByteArrayOutputStream()
            app.contentResolver.openInputStream(it).use { input ->
                TranslationSources.copyBounded(requireNotNull(input), bytes, 16L * 1024 * 1024)
            }
            TranslationIcon.normalize(bytes.toByteArray())
        }
        translationSources.setIcon(id, png); publishSources(); "图标已保存"
    }

    fun removeTranslation(id: String) = perform("卸载汉化文件") {
        removeSelectedTranslation(id)
    }

    fun removeAiTranslation(id: String) = perform("删除 AI 汉化") {
        val sources = translationSources.read()
        require(sources.sources.any { it.id == id && it.kind == "ai" }) { "此 AI 汉化已不存在" }
        if (sources.selectedId == id) removeSelectedTranslation(id)
        else {
            translationSources.remove(id)
            translationSources.pruneUnused()
            publishSources("AI 汉化已删除，当前汉化保留")
        }
        publish { it.copy(ai = it.ai.copy(progress = AiProgress(), message = "AI 汉化已删除", failed = false)) }
        "AI 汉化已删除"
    }

    private fun removeSelectedTranslation(id: String): String {
        val source = translationSources.read().selected
        require(source.id == id) { "当前汉化源已改变，请重新选择" }
        stopGame()
        SourceActivation(translationCache).recover()
        installer().uninstallLatest()
        try {
            SourceActivation(translationCache).discardDerived()
            if (source.isOfficial) com.example.limbuszhcn.update.TranslationRepository(translationCache).prune(emptySet())
            else translationSources.remove(id)
            translationSources.pruneUnused()
        } finally {
            preparationPreferences.edit().remove("pending").apply()
            publish { it.copy(translation = null, resourcePreparation = null) }
            publishSources()
        }
        val message = if (source.isOfficial) "汉化文件已卸载，下次使用此来源时会重新下载"
            else "汉化源已删除，已切回都市零协会"
        publishSources(message)
        return message
    }

    fun exportTranslation(id: String, destination: Uri) = perform("导出汉化包", allowPackageUpdate = true) {
        try {
            val source = requireNotNull(translationSources.read().sources.find { it.id == id }) { "汉化源已不存在" }
            val prepared = if (source.isOfficial) {
                val cached = com.example.limbuszhcn.update.TranslationRepository(translationCache).verifiedForLaunch(null)
                PreparedTranslation(cached.outputDirectory, cached.manifest)
            } else translationSources.prepare(source)
            app.contentResolver.openOutputStream(destination, "wt").use { output ->
                TranslationBackup.write(prepared, requireNotNull(output) { "无法写入所选位置" })
            }
        } catch (error: Exception) {
            runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, destination) }
            throw error
        }
        publishSources("汉化包已导出，可用于备份或在其他设备导入")
        "汉化包已导出"
    }

    fun loadAiModels(endpoint: String, key: String) {
        if (state.ai.models.loading) return
        val request = modelsRequest.incrementAndGet()
        state = state.copy(ai = state.ai.copy(models = AiModelsState(request, endpoint, loading = true)))
        modelsTask = modelsWorker.submit {
            val result = runCatching {
                val credential = aiModelsKey(endpoint, aiSettings.config().endpoint, key) {
                    if (aiSettings.hasKey()) aiSettings.key() else ""
                }
                AiModelsClient().list(endpoint, credential)
            }
            publish {
                if (modelsRequest.get() != request) it else it.copy(ai = it.ai.copy(models =
                    AiModelsState(request, endpoint, values = result.getOrDefault(emptyList()),
                        error = result.exceptionOrNull()?.let { error ->
                            if (error is IllegalArgumentException || error is IllegalStateException)
                                error.message ?: "获取模型失败，请稍后重试或手动填写"
                            else "获取模型失败，请稍后重试或手动填写"
                        })))
            }
        }
    }

    fun inspectCoverage() {
        if (state.busy || state.coverage.loading) return
        val request = coverageRequest.incrementAndGet()
        state = state.copy(coverage = CoverageState(loading = true))
        coverageWorker.execute {
            val result = runCatching {
                val storage = runtime.storage()
                require(storage.japaneseResources().isNotEmpty()) { "还没有本地游戏文本。请先从 BYBUS 进入游戏完成资源下载，再回来检查。" }
                val pair = com.example.limbuszhcn.storage.OperationLock.run(app.filesDir.toPath()) {
                    val source = translationSources.read().selected
                    val base = if (source.isOfficial) {
                        val extracted = com.example.limbuszhcn.update.TranslationRepository(translationCache).verifiedForLaunch(null)
                        PreparedTranslation(extracted.outputDirectory, extracted.manifest)
                    } else translationSources.prepare(source)
                    source to base
                }
                TranslationCoverage.scan(storage, pair.second, pair.first.name).also { report ->
                    PersistentDiagnosticLog.info(TAG, "Coverage files=${report.files} total=${report.total} covered=${report.covered} " +
                        "missing=${report.missing} review=${report.review} shared=${report.shared} excluded=${report.excluded} unreadable=${report.unreadable}")
                }
            }
            publish {
                if (coverageRequest.get() != request) it else it.copy(coverage = CoverageState(
                    report = result.getOrNull(), error = result.exceptionOrNull()?.let { error ->
                        if (error is IllegalArgumentException || error is IllegalStateException) error.message
                        else "本地文本读取失败，请在资源下载完成后重新检查"
                    }))
            }
        }
    }

    fun clearAiModels() {
        modelsRequest.incrementAndGet(); modelsTask?.cancel(true); modelsTask = null
        state = state.copy(ai = state.ai.copy(models = AiModelsState()))
    }

    private fun aiSettingsOperation(label: String, task: () -> String) {
        if (state.busy || state.ai.settingsBusy || state.ai.running) return
        state = state.beginAiSettings(label)
        aiSettingsWorker.execute {
            val result = runCatching(task)
            result.exceptionOrNull()?.let { PersistentDiagnosticLog.error(TAG, label, it) }
            publish { it.finishAiSettings(result) }
        }
    }

    fun saveAi(config: AiConfig, key: String, test: Boolean = false) = aiSettingsOperation(if (test) "正在测试翻译" else "正在保存设置") {
        val settings = config.validated()
        if (test) {
            val result = aiSettings.test(settings, key)
            "测试成功：${result.translations.getValue("connection")}。配置尚未保存，游戏汉化未改变。"
        } else {
            aiSettings.save(settings, key); publishSources(); "API 设置已保存"
        }
    }

    fun clearAiKey() = aiSettingsOperation("正在移除 Key") {
        aiSettings.clearKey(); publishSources(); "本机 API Key 已移除"
    }
    fun saveAiPrompt(prompt: String) = aiSettingsOperation("正在保存提示词") {
        aiSettings.savePrompt(prompt); publishSources(); "全局提示词已保存，仅影响之后的翻译请求"
    }

    fun cancelAi() { aiCancelled.set(true); publish { it.copy(ai = it.ai.copy(message = "正在暂停，已完成的译文会保留")) } }

    fun generateAi(config: AiConfig, key: String) = perform("生成 AI 汉化") {
        aiSettings.save(config, key); publishSources(); stopGame()
        aiCancelled.set(false)
        publish { it.copy(ai = it.ai.copy(running = true, failed = false, message = "正在读取游戏原文", progress = AiProgress())) }
        try {
            val selected = translationSources.read().selected
            // Continuing a generated source must preserve its previous translated batches.
            val base = selected
            val prepared = if (config.missingOnly) {
                if (base.isOfficial) {
                    val repository = com.example.limbuszhcn.update.TranslationRepository(translationCache, github::releaseForLaunch)
                    val extracted = runCatching { repository.checkLatest(RELEASE_URL, null) }.getOrElse { error ->
                        if (error is java.util.concurrent.CancellationException) throw error
                        repository.verifiedForLaunch(null)
                    }
                    PreparedTranslation(extracted.outputDirectory, extracted.manifest)
                } else translationSources.prepare(base)
            } else null
            val reference = if (base.isOfficial && prepared != null) prepared else runCatching {
                com.example.limbuszhcn.update.TranslationRepository(translationCache).verifiedForLaunch(null)
            }.getOrNull()?.let { PreparedTranslation(it.outputDirectory, it.manifest) }
            val result = AiTranslationJob(translationCache, runtime.storage(), config,
                AiTranslationClient(config, aiSettings.key()), aiCancelled::get) { progress ->
                publish { it.copy(ai = it.ai.copy(progress = progress, message = progress.message)) }
            }.run(base.baseId ?: base.id, prepared, base.id.takeIf { base.kind == "ai" }?.removePrefix("ai-"), reference = reference)
            if (result.progress.completed + result.progress.reused > 0) translationSources.addGenerated(result.archive, base.baseId ?: base.id, result.identity,
                if (config.missingOnly) "${base.name.take(48)} · AI 补全" else "AI 汉化",
                if (config.missingOnly && base.isOfficial) prepared?.manifest?.requestedVersion?.toLongOrNull() else base.officialVersion)
            publish { it.copy(translation = null, resourcePreparation = null,
                ai = it.ai.copy(running = false, message = result.progress.message, progress = result.progress)) }
            publishSources(if (result.progress.completed + result.progress.reused > 0) "AI 汉化已保存，可撤回上次生成" else "所选范围没有需要 AI 处理的文本")
            result.progress.message
        } catch (e: Exception) {
            val cancelled = e is java.util.concurrent.CancellationException
            publish { it.copy(ai = it.ai.copy(running = false, failed = !cancelled,
                message = if (cancelled) "已暂停，继续时会复用已完成的译文" else "翻译未完成：${e.userMessage()}")) }
            if (cancelled) "已暂停，译文缓存已保留" else throw e
        }
    }

    fun undoAi() = perform("撤回 AI 翻译") {
        require(!state.ai.running) { "请先暂停翻译" }
        stopGame()
        translationSources.undoGenerated()
        preparationPreferences.edit().remove("pending").apply()
        publish { it.copy(translation = null, resourcePreparation = null,
            ai = it.ai.copy(progress = AiProgress(), failed = false, message = "已撤回上次生成，下次启动使用恢复的汉化")) }
        publishSources("已恢复上次生成前的汉化")
        "已撤回上次 AI 生成"
    }

    private fun offerAiUpdate(source: TranslationSource, metadata: String, launchAfter: Boolean, force: Boolean = false): Boolean {
        val version = com.example.limbuszhcn.update.GitHubTranslationReleaseClient(RELEASE_URL).parseRelease(metadata).version
        if (!AiUpstreamUpdate.shouldOffer(source, version, aiUpdatePreferences.getLong("offeredVersion", 0), force)) return false
        val offer = AiUpstreamOffer(source.id, source.revision, version, source.officialVersion, metadata,
            aiSettings.config(), launchAfter)
        publish { it.copy(aiUpstreamOffer = offer) }
        return true
    }

    fun chooseAiUpdate(choice: AiUpdateChoice?) {
        if (state.busy || state.ai.settingsBusy) return
        val offer = state.aiUpstreamOffer ?: return
        state = state.copy(aiUpstreamOffer = null)
        aiUpdatePreferences.edit().putLong("offeredVersion",
            maxOf(offer.version, aiUpdatePreferences.getLong("offeredVersion", 0))).apply()
        if (choice == null) return
        val decision = AiUpstreamDecision(offer, choice)
        if (offer.launchAfter) launch(update = decision)
        else if (choice != AiUpdateChoice.Current) perform("更新汉化资源") {
            stopGame()
            val message = applyAiUpdate(decision)
            val summary = prepareTranslation(installer({ offer.metadata }))
            if (translationSources.read().selected.isOfficial) check(summary.version == offer.version.toString()) { "零协新版未激活，请重新检查" }
            publish { it.copy(translation = if (summary.indexActivated) summary else null, resourcePreparation = summary.takeUnless { summary.indexActivated }) }
            message
        }
    }

    /** Download and validate first; no source is replaced until the chosen operation succeeds. */
    private fun applyAiUpdate(decision: AiUpstreamDecision): String {
        val offer = decision.offer
        val source = translationSources.read().selected
        AiUpstreamUpdate.requireCurrent(offer, source)
        if (decision.choice == AiUpdateChoice.Current) return "继续使用当前汉化"
        if (decision.choice == AiUpdateChoice.Supplement) {
            require(aiSettings.hasKey()) { "请先在 AI 翻译中保存 API 设置" }
            AiUpstreamUpdate.supplementConfig(offer.config)
        }
        stage("更新都市零协会汉化", 1)
        val extracted = com.example.limbuszhcn.update.TranslationRepository(translationCache,
            releaseMetadata = { offer.metadata }, onProgress = { stage(it, 1) }).checkLatest(RELEASE_URL, null)
        check(extracted.manifest.requestedVersion == offer.version.toString()) { "汉化版本发生变化，请重新检查" }
        val base = PreparedTranslation(extracted.outputDirectory, extracted.manifest)
        if (decision.choice == AiUpdateChoice.Official) {
            translationSources.select("zero")
            publishSources("已更新都市零协会，旧 AI 包保留")
            return "已更新都市零协会"
        }
        aiCancelled.set(false)
        val supplement = decision.choice == AiUpdateChoice.Supplement
        val config = if (supplement) AiUpstreamUpdate.supplementConfig(offer.config) else AiConfig()
        publish { it.copy(aiPreparingLaunch = supplement,
            ai = it.ai.copy(running = true, failed = false, progress = AiProgress(), message = "正在核对已有 AI 译文")) }
        try {
            stage("复用已有 AI 译文", 1)
            val previous = translationSources.prepare(source)
            val reusable = AiTranslationReuse.collect(translationCache, source, previous, runtime.storage(), aiCancelled::get)
            val identity = AiTranslationJob.digest("upstream-v1|${source.id}|${source.revision}|${offer.version}|${config.endpoint}|${config.model}|${AiTranslationJob.digest(config.prompt)}").take(32)
            val result = AiTranslationJob(translationCache, runtime.storage(), config,
                AiTranslationClient(config, if (supplement) aiSettings.key() else ""), aiCancelled::get) { progress ->
                publish { it.copy(ai = it.ai.copy(progress = progress, message = progress.message)) }
            }.run("zero", base, identity, reusable, reuseOnly = !supplement)
            if (aiCancelled.get()) throw java.util.concurrent.CancellationException("已取消本次启动")
            if (result.progress.completed + result.progress.reused > 0)
                translationSources.addGenerated(result.archive, "zero", identity, "都市零协会 · AI 补全", offer.version)
            else translationSources.select("zero")
            val message = "零协已更新 · 复用 ${result.progress.reused} 条 · 新译 ${result.progress.completed} 条 · 未覆盖 ${result.remaining} 条"
            publish { it.copy(translation = null, resourcePreparation = null,
                ai = it.ai.copy(progress = result.progress, message = message)) }
            publishSources(message)
            return message
        } catch (error: Exception) {
            val cancelled = error is java.util.concurrent.CancellationException
            publish { it.copy(ai = it.ai.copy(failed = !cancelled,
                message = if (cancelled) "已取消本次启动，已有汉化保留" else "更新未完成：${error.userMessage()}")) }
            throw error
        } finally {
            publish { it.copy(aiPreparingLaunch = false, ai = it.ai.copy(running = false)) }
        }
    }

    fun checkGameUpdate(force: Boolean = false) {
        if (state.gameUpdate.loading) return
        state = state.copy(gameUpdate = GameUpdateCatalog(state.gameUpdate.channels.mapValues { it.value.copy(loading = true) }))
        gameUpdates.forEach { (source, repository) -> versionWorker.execute {
            val result = runCatching { repository.refresh(force) }.getOrElse {
                repository.cached().copy(loading = false, issue = "此渠道暂未刷新")
            }
            publish { it.copy(gameUpdate = GameUpdateCatalog(it.gameUpdate.channels + (source to result))) }
        } }
    }

    fun selectGameSource(source: GameUpdateSource) {
        updatePreferences.edit().putString("preferred", source.key).apply()
        state = state.copy(preferredGameSource = source)
    }

    fun requestGameUpdates() {
        // A restored Compose screen may retain the previous process's handled request id.
        state = state.copy(updatesRequest = nextUpdatesRequest(state))
        checkGameUpdate()
    }

    fun openGoogleSettings() = perform("准备 Google 账号与付款") {
        val virtualRuntime = runtime as? com.example.limbuszhcn.container.VirtualAppContainerRuntime
            ?: error("当前容器不支持 Google 账号设置")
        virtualRuntime.openGoogleSettings()
        "商店服务已启用，请在容器 Google 设置中登录账号"
    }

    fun checkGooglePayment(enable: Boolean = false) = perform(if (enable) "启用付款服务" else "检查付款设置") {
        val result = runCatching {
            val virtual = runtime as? com.example.limbuszhcn.container.VirtualAppContainerRuntime
                ?: error("当前容器不支持 Google 账号设置")
            virtual.googlePaymentState(waitForRegistration = true)
        }
        publish { it.copy(googlePayment = result.getOrNull(), googlePaymentIssue = result.exceptionOrNull()?.userMessage()) }
        val payment = result.getOrThrow()
        if (!payment.registered) "Google 设备注册尚未完成，请检查网络后重试"
        else if (enable) "付款服务已启用"
        else "付款设置已检查"
    }

    private fun nextUpdatesRequest(value: LauncherUiState) =
        nextUiRequestId(value.updatesRequest, android.os.SystemClock.elapsedRealtimeNanos())

    fun refresh(preserveFailure: Boolean = false) = perform("正在检测游戏", allowPackageUpdate = true,
        preserveFailure = preserveFailure) {
        SourceActivation(translationCache).recover()
        publishSources()
        val (container, installed) = readGameStatus()
        val nativeIssue = if (installed != null) runCatching { gameCompatibility.requireApproved() }
            .exceptionOrNull()?.message ?: "" else ""
        val patch = installer()
        val selectedSource = translationSources.read().selected
        var active = if (SourceActivation(translationCache).owner() == selectedSource.id)
            runCatching { patch.activeInstallSummary() }.getOrNull()?.takeIf {
                selectedSource.isOfficial || it.version == "local-${selectedSource.revision}"
            } else null
        // A return from the explicit download mode prepares the cached translation without
        // terminating a potentially still-running resource download or starting another game.
        val preparation = if (preparationPreferences.getBoolean("pending", false) && container.gameImported &&
            translationSources.read().selected.isOfficial) {
            runCatching { prepareTranslation(patch, repair = true) }.getOrNull().also {
                active = patch.activeInstallSummary()
                if (active != null) preparationPreferences.edit().remove("pending").apply()
            }
        } else null
        val compatibility = DeviceCompatibility(app).report()
        val runtimeStatus = receipt().status()
        if (state.googlePayment != null) {
            val payment = runCatching { (runtime as? com.example.limbuszhcn.container.VirtualAppContainerRuntime)?.googlePaymentState() }
            publish { it.copy(googlePayment = payment.getOrNull(), googlePaymentIssue = payment.exceptionOrNull()?.userMessage()) }
        }
        val saved = runCatching {
            com.example.limbuszhcn.container.LocalContainerRuntime(app.filesDir.toPath().resolve("container-workspace")).compactSnapshots()
        }.getOrElse { error -> PersistentDiagnosticLog.warn(TAG, "Snapshot reuse skipped", error); 0L }
        if (saved > 0) PersistentDiagnosticLog.info(TAG, "Deduplicated committed APK snapshots savedBytes=$saved")
        publish { it.copy(translation = active, resourcePreparation = if (active != null) null else preparation ?: it.resourcePreparation,
            compatibility = compatibility, runtimeStatus = runtimeStatus,
            nativeCompatibilityIssue = nativeIssue.takeIf(String::isNotEmpty)) }
        container.message
    }

    fun preparePackageUpdate(uris: List<Uri>) {
        if (uris.isEmpty()) return
        state = state.copy(updatesRequest = nextUpdatesRequest(state))
        perform("读取安装包") {
            val prepared = packageUpdates.prepare(uris) { stage(it) }
            preparedUpdate = prepared
            publish { it.copy(gameInstallReview = prepared.review) }
            "安装包已校验，请确认后交给系统安装"
        }
    }

    fun installPackageUpdate() = perform("准备系统安装", allowPackageUpdate = true) {
        require(app.packageManager.canRequestPackageInstalls()) { "请在系统设置中允许 BYBUS 安装应用" }
        stopGame()
        if (packageUpdates.hasSession()) packageUpdates.resume()
        else {
            val prepared = requireNotNull(preparedUpdate) { "请重新选择安装包" }
            // From this point a failure requires reselecting; no stale review may reference deleted staging files.
            preparedUpdate = null
            publish { it.copy(gameInstallReview = null) }
            try { packageUpdates.commit(prepared) }
            catch (error: Exception) { packageUpdates.clearStaging(); throw error }
        }
        publish { it.copy(gameInstallPending = true) }
        "请完成系统安装确认，游戏资源会保留"
    }

    fun cancelPackageUpdate() = perform("取消安装", allowPackageUpdate = true) {
        packageUpdates.cancel()
        preparedUpdate = null
        publish { it.copy(gameInstallReview = null, gameInstallPending = false, gameInstallConfirmation = null) }
        "已取消，原游戏和资源未删除"
    }

    fun installationResult(intent: Intent) {
        if (!packageUpdates.accepts(intent)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            state = state.copy(gameInstallPending = true, gameInstallConfirmation = confirmation,
                updatesRequest = nextUpdatesRequest(state))
            return
        }
        worker.execute {
            val result = runCatching {
                com.example.limbuszhcn.storage.OperationLock.run(app.filesDir.toPath()) {
                    packageUpdates.finish()
                    readGameStatus()
                }
            }
            preparedUpdate = null
            publish { it.copy(gameInstallPending = false, gameInstallReview = null, gameInstallConfirmation = null,
                failed = status !in setOf(PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED) || result.isFailure,
                message = if (status == PackageInstaller.STATUS_SUCCESS && result.isSuccess)
                    "系统安装完成，下次启动会同步游戏并保留已有资源"
                else when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "已取消安装，已有资源保留"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "可用空间不足，请释放空间后重新选择安装包"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "与已安装的游戏冲突，请核对安装包签名和版本"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "此安装包与当前设备不兼容"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "系统阻止了安装，请检查安装权限"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "安装包未通过系统校验，请重新获取完整包"
                    else -> "系统安装未完成，请重新选择文件；已有资源保留"
                },
                updatesRequest = nextUpdatesRequest(it)) }
        }
    }

    fun installConfirmationOpened() { state = state.copy(gameInstallConfirmation = null) }

    private fun prepareGame(force: Boolean = false) {
        stage("检查已安装游戏", 0)
        val (current, installed) = readGameStatus()
        requireNotNull(installed) { "未检测到边狱巴士，请选择更新渠道或完整安装包" }
        if (!force && current.gameImported && current.importedVersionCode == installed &&
            !current.requiresInstalledGameResync) return
        stage("同步游戏，保留已有数据", 0)
        val imported = AndroidInstalledGameImporter(app.packageManager, File(app.filesDir, "container-imports")).copyInstalledGame()
        runtime.importGame(GameImportRequest(imported.splitApkPaths, imported.versionCode, "installed"))
        readGameStatus()
    }

    private fun receipt() = com.example.limbuszhcn.container.RuntimeReceipt(app.filesDir.toPath().resolve("translation-cache/translation-index"))

    private fun stopGame() {
        runtime.stopGame()
        publish { it.copy(canResumeGame = false) }
    }

    fun launch(prepareResources: Boolean = false, update: AiUpstreamDecision? = null) {
        // A Play/DNS outage must not stall the existing translation/launch path.
        checkGameUpdate(force = true)
        perform("准备启动") {
            stage("返回游戏", 2)
            if ((update == null || update.choice == AiUpdateChoice.Current) && runtime.resumeGameIfRunning()) {
                publish { it.copy(canResumeGame = true) }
                return@perform "已返回游戏"
            }
            publish { it.copy(canResumeGame = false) }
            stage("检查游戏兼容性", 0)
            val localCandidate = com.example.limbuszhcn.update.TranslationRepository(
                app.filesDir.toPath().resolve("translation-cache")).hasLaunchCandidate()
            val selectedSource = translationSources.read().selected
            if (update != null) AiUpstreamUpdate.requireCurrent(update.offer, selectedSource)
            val metadata = if (update == null && (selectedSource.isOfficial || selectedSource.kind == "ai"))
                releaseCheck.start(if (selectedSource.kind == "ai" || localCandidate) 4_000 else 15_000) else null
            if (update == null && selectedSource.kind == "ai") {
                val latest = runCatching { requireNotNull(metadata).await() }.getOrElse { error ->
                    if (error is java.util.concurrent.CancellationException) throw error
                    PersistentDiagnosticLog.warn(TAG, "AI upstream notice unavailable; verify selected local pack", error)
                    null
                }
                val offered = latest != null && runCatching { offerAiUpdate(selectedSource, latest, launchAfter = true) }.getOrElse { error ->
                    PersistentDiagnosticLog.warn(TAG, "Invalid AI upstream notice; verify selected local pack", error); false
                }
                if (offered)
                    return@perform "发现零协更新，请选择本次使用的汉化"
            }
            val approvedBuild = measureStartup("compatibility") { gameCompatibility.requireApproved() }
            measureStartup("game_sync") { prepareGame() }
            measureStartup("stop_previous_game") { stopGame() }
            val updateMessage = update?.let(::applyAiUpdate)
            val patch = installer({ update?.offer?.metadata ?: metadata?.await() ?: error("本地汉化不请求零协更新") }) { stage(it, 1) }
            stage("校验汉化文件", 1)
            val summary = prepareTranslation(patch)
            if (update != null && update.choice != AiUpdateChoice.Current && translationSources.read().selected.isOfficial)
                check(summary.version == update.offer.version.toString()) { "零协新版未激活，请重新检查" }
            val active = if (summary.indexActivated) summary else patch.activeInstallSummary()
            val mode = translationLaunchMode(active, prepareResources)
            publish { it.copy(translation = active, resourcePreparation = summary.takeIf { active == null }) }
            if (mode == TranslationLaunchMode.NeedsResources) {
                preparationPreferences.edit().putBoolean("pending", true).apply()
                publish { it.copy(resourcePreparationRequest = nextUiRequestId(it.resourcePreparationRequest,
                    android.os.SystemClock.elapsedRealtimeNanos())) }
                return@perform "汉化资源未通过校验，请重新检查"
            }
            if (mode == TranslationLaunchMode.Localized) preparationPreferences.edit().remove("pending").apply()
            else preparationPreferences.edit().putBoolean("pending", true).apply()
            stage("启动容器内游戏", 2)
            gameCompatibility.requireUnchanged(approvedBuild)
            receipt().beginLaunch()
            val runtimeStatus = receipt().status()
            publish { it.copy(runtimeStatus = runtimeStatus) }
            measureStartup("container_launch") { runtime.launchGame() }
            val canResume = runtime.hasRunningGame()
            publish { it.copy(canResumeGame = canResume) }
            "${updateMessage ?: summary.description()}；游戏已打开"
        }
    }

    fun updateTranslation() = perform("校验与更新汉化资源") {
        val source = translationSources.read().selected
        if (source.kind == "ai") {
            if (offerAiUpdate(source, releaseCheck.start(15_000).await(), launchAfter = false, force = true))
                return@perform "请选择更新后的汉化方式"
            return@perform "零协暂无更新，可在 AI 翻译中继续补译"
        }
        require(readGameStatus().first.gameImported) { "请先启动一次游戏以准备容器" }
        stopGame()
        val patch = installer()
        val summary = prepareTranslation(patch)
        val active = if (summary.indexActivated) summary else patch.activeInstallSummary()
        publish { it.copy(translation = active) }
        summary.description()
    }

    fun repair() = perform("正在修复") {
        fun progress(step: Int, text: String) {
            stage(text)
            publish { it.copy(repair = RepairState(RepairPhase.Running, step, text)) }
        }
        val result = runCatching {
            progress(0, "正在重新同步游戏")
            stopGame()
            prepareGame(force = true)
            progress(1, "正在校验中文字体")
            com.lody.virtual.helper.compat.LimbusTranslationAssets.prepare(app)
            progress(2, "正在重建汉化文件")
            val patch = installer(onProgress = { progress(2, it) })
            val summary = prepareTranslation(patch, repair = true)
            check(summary.liveResourcesReady) { "汉化文件尚未准备完成，请联网后重试" }
            preparationPreferences.edit().remove("pending").apply()
            val active = patch.activeInstallSummary()
            publish { it.copy(translation = active, resourcePreparation = null) }
            "文件修复完成，回到首页启动游戏即可检查效果。"
        }
        publish { it.copy(repair = RepairState(
            if (result.isSuccess) RepairPhase.Complete else RepairPhase.Failed,
            if (result.isSuccess) 3 else it.repair.step,
            result.getOrElse { error -> error.userMessage() })) }
        result.getOrThrow()
    }

    fun clearGameResources() = perform("正在清除游戏资源") {
        val result = runCatching {
            publish { it.copy(clearing = DataClearState(running = true)) }
            check(readGameStatus().first.available) { "容器暂不可用，请稍后重试" }
            stopGame()
            installer().uninstallLatest()
            preparationPreferences.edit().remove("pending").apply()
            publish { it.copy(translation = null, resourcePreparation = null, repair = RepairState()) }
            val cleared = runtime.storage().clearDownloadedResources()
            if (cleared.files == 0L) "没有需要清除的游戏资源。"
            else "游戏资源已清除，释放 ${formatResourceBytes(cleared.bytes)}。下次进入游戏需要重新下载。"
        }
        publish { it.copy(clearing = DataClearState(failed = result.isFailure,
            message = result.getOrElse { error -> "清理未完成：${error.userMessage()}。可以重试，账号未清除。" })) }
        result.getOrThrow()
    }

    fun exportDiagnostics() = perform("正在生成诊断包", allowPackageUpdate = true) {
        // An explicit new export must describe the latest failure, not a stale picker retry.
        runCatching { diagnosticExport.complete() }.onFailure { diagnosticExport.discardMarker() }
        val archive = DebugLogExporter(app).export(readGameStatus().first, installer().activeInstallSummary())
            .also { diagnosticExport.begin(it.toPath()) }
        publish { it.copy(exportRequest = archive) }
        "诊断包已生成，请选择保存位置"
    }

    fun exportPickerOpened() { state = state.copy(exportRequest = null) }

    fun saveDiagnostics(destination: Uri?) {
        if (exportSaveQueued) return
        val selected = runCatching {
            destination?.let {
                runCatching { app.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            // Persist the result before queuing: startup recovery must not swallow it.
            diagnosticExport.selected(destination?.toString())
        }
        if (selected.isFailure) {
            destination?.let { releaseDiagnosticGrant(it) }
            runCatching { diagnosticExport.discardMarker() }
            showError(requireNotNull(selected.exceptionOrNull()))
            return
        }
        queueDiagnosticSave()
    }

    fun exportPickerFailed(error: Throwable) {
        state = state.copy(exportRequest = null)
        // Keep the archive so a later tap can retry the picker.
        showError(error)
    }

    private fun queueDiagnosticSave(recovering: Boolean = false) {
        if (!recovering) {
            exportSaveQueued = true
            state = state.copy(busy = true, stage = "正在保存诊断包", failed = false)
        }
        worker.execute {
            val result = runCatching {
                com.example.limbuszhcn.storage.OperationLock.run(app.filesDir.toPath()) {
                    val pending = diagnosticExport.read() ?: return@run null
                    if (!pending.cancelled && pending.destination == null) return@run null
                    publish { it.copy(busy = true, stage = diagnosticExportStage(pending.cancelled)) }
                    if (pending.cancelled) {
                        diagnosticExport.complete()
                        "已取消导出诊断包"
                    } else {
                        val uri = Uri.parse(requireNotNull(pending.destination))
                        try {
                            app.contentResolver.openOutputStream(uri, "wt").use { output ->
                                requireNotNull(output) { "无法打开所选保存位置，请重新导出" }
                                pending.archive.toFile().inputStream().use { it.copyTo(output) }
                            }
                            diagnosticExport.complete()
                            "诊断包已保存"
                        } finally {
                            // A failed save returns to the picker too; do not retain
                            // write access to a destination that is no longer recorded.
                            releaseDiagnosticGrant(uri)
                        }
                    }
                }
            }
            result.exceptionOrNull()?.let {
                PersistentDiagnosticLog.error(TAG, "Save diagnostics", it)
                runCatching { diagnosticExport.retry() }.onFailure { diagnosticExport.discardMarker() }
            }
            publish {
                exportSaveQueued = false
                if (result.isSuccess && result.getOrNull() == null) it
                else it.copy(busy = false, failed = result.isFailure,
                    message = result.getOrElse { error -> error.userMessage() }.orEmpty())
            }
        }
    }

    private fun releaseDiagnosticGrant(uri: Uri) {
        runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    fun showError(error: Throwable) { state = state.copy(failed = true, message = error.userMessage()) }

    companion object {
        @Volatile private var instance: LaunchCoordinator? = null
        fun get(application: Application): LaunchCoordinator = instance ?: synchronized(this) {
            instance ?: LaunchCoordinator(application).also { instance = it }
        }
        private const val TAG = "LimbusLauncher"
        private const val RELEASE_URL = "https://api.github.com/repos/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/latest"
    }
}

private fun PatchInstallSummary.description(): String = when {
    liveResourcesReady -> "汉化文件已准备，游戏加载文本时自动应用"
    indexActivated && reusedIndex -> "汉化 $version 已校验，复用现有索引"
    indexActivated -> "汉化 $version 索引已保存，有效资源 $availableSourceFiles/$candidateSourceFiles"
    previousIndexPreserved -> "${pendingReason ?: "资源尚未完整"}；继续使用现有汉化"
    startupIndexAvailable -> "启动页索引可用，完整汉化包仍需校验"
    else -> "汉化包尚未准备完成，请重新检查"
}

private fun Throwable.updateFailureLabel(): String = when (this) {
    is com.example.limbuszhcn.tracking.ProjectFetchException -> when (issue) {
        com.example.limbuszhcn.tracking.ProjectIssue.RateLimited -> "GitHub 限流等待中"
        com.example.limbuszhcn.tracking.ProjectIssue.InvalidData -> "汉化发布信息格式异常"
        else -> "更新连接失败"
    }
    is java.io.InterruptedIOException -> "更新检查超时"
    is com.example.limbuszhcn.update.TranslationProtocolException -> "汉化发布信息格式异常"
    is com.example.limbuszhcn.update.DownloadValidationException -> "汉化包校验失败"
    is com.example.limbuszhcn.update.ArchiveValidationException -> "汉化包内容无效"
    is java.nio.file.FileSystemException -> "汉化文件读写失败"
    is java.io.IOException -> "更新连接失败"
    else -> "汉化更新未完成"
}
