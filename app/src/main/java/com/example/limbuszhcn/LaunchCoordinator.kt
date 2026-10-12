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

internal data class LauncherUiState(
    val container: ContainerStatus? = null,
    val installedVersion: Long? = null,
    val installedVersionName: String? = null,
    val nativeCompatibilityIssue: String? = null,
    val gameUpdate: GameUpdateCatalog = GameUpdateCatalog(),
    val installSource: GameInstallSource = GameInstallSource(),
    val preferredGameSource: GameUpdateSource? = null,
    val updatesRequest: Long = 0,
    val gameInstallReview: GameInstallReview? = null,
    val gameInstallPending: Boolean = false,
    val gameInstallConfirmation: Intent? = null,
    val translation: PatchInstallSummary? = null,
    val compatibility: DeviceCompatibilityReport? = null,
    val runtimeStatus: String = "尚未收到游戏运行时确认；当前游戏版本的兼容性未验证",
    val busy: Boolean = false,
    val stage: String = "正在读取状态",
    val launchStep: Int = -1,
    val message: String = "正在检测手机上的边狱巴士",
    val failed: Boolean = false,
    val exportRequest: File? = null
)

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
    private val gameUpdates = GameUpdateSource.entries.associateWith { source ->
        com.example.limbuszhcn.update.GameUpdateRepository(app.filesDir.toPath().resolve("game-update-${source.key}.properties"), source = source)
    }
    private var pendingArchive: File? = null
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
    }

    private fun publish(change: (LauncherUiState) -> LauncherUiState) {
        main.post { state = change(state) }
    }

    private fun stage(label: String, step: Int = -1) = publish { it.copy(stage = label, launchStep = step) }

    private fun perform(label: String, allowPackageUpdate: Boolean = false, task: () -> String) {
        if (state.busy || pendingArchive != null) return
        if (!allowPackageUpdate && (state.gameInstallPending || state.gameInstallReview != null)) {
            state = state.copy(updatesRequest = state.updatesRequest + 1)
            return
        }
        state = state.copy(busy = true, stage = label, message = label, failed = false, launchStep = -1)
        worker.execute {
            val start = System.nanoTime()
            val result = runCatching { com.example.limbuszhcn.storage.OperationLock.run(app.filesDir.toPath(), task) }
            result.exceptionOrNull()?.let { PersistentDiagnosticLog.error(TAG, label, it) }
            PersistentDiagnosticLog.info(TAG, "Task finished stage=$label elapsedMs=${(System.nanoTime() - start) / 1_000_000} success=${result.isSuccess}")
            publish { it.copy(busy = false, launchStep = -1, failed = result.isFailure,
                message = result.getOrElse { error -> error.userMessage() }) }
        }
    }

    private fun readGameStatus(): Pair<ContainerStatus, Long?> {
        val installed = runCatching { AndroidInstalledGameImporter.installedVersionCode(app.packageManager) }.getOrNull()
        val container = runtime.status().withInstalledVersion(installed)
        val name = runCatching { AndroidInstalledGameImporter.installedVersionName(app.packageManager) }.getOrNull()
        val source = if (installed != null) GameStores.installedSource(app) else GameInstallSource()
        val preferred = GameUpdateSource.fromKey(updatePreferences.getString("preferred", null)) ?: GameStores.sourceFor(source)
        publish { it.copy(container = container, installedVersion = installed, installedVersionName = name,
            installSource = source, preferredGameSource = preferred) }
        return container to installed
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
        state = state.copy(updatesRequest = maxOf(state.updatesRequest + 1, android.os.SystemClock.elapsedRealtimeNanos()))
        checkGameUpdate()
    }

    fun openGoogleSettings() = perform("准备 Google 账号与付款") {
        val virtualRuntime = runtime as? com.example.limbuszhcn.container.VirtualAppContainerRuntime
            ?: error("当前容器不支持 Google 账号设置")
        virtualRuntime.openGoogleSettings()
        "请在容器 Google 设置中登录账号并开启应用内购买"
    }

    fun refresh() = perform("正在检测游戏", allowPackageUpdate = true) {
        val (container, installed) = readGameStatus()
        val nativeIssue = if (installed != null) runCatching { gameCompatibility.requireApproved() }
            .exceptionOrNull()?.message ?: "" else ""
        val active = runCatching { installer().activeInstallSummary() }.getOrNull()
        val compatibility = DeviceCompatibility(app).report()
        val runtimeStatus = receipt().description()
        val saved = runCatching {
            com.example.limbuszhcn.container.LocalContainerRuntime(app.filesDir.toPath().resolve("container-workspace")).compactSnapshots()
        }.getOrElse { error -> PersistentDiagnosticLog.warn(TAG, "Snapshot reuse skipped", error); 0L }
        if (saved > 0) PersistentDiagnosticLog.info(TAG, "Deduplicated committed APK snapshots savedBytes=$saved")
        publish { it.copy(translation = active, compatibility = compatibility, runtimeStatus = runtimeStatus,
            nativeCompatibilityIssue = nativeIssue.takeIf(String::isNotEmpty)) }
        container.message
    }

    fun preparePackageUpdate(uris: List<Uri>) {
        if (uris.isEmpty()) return
        state = state.copy(updatesRequest = state.updatesRequest + 1)
        perform("读取安装包") {
            val prepared = packageUpdates.prepare(uris) { stage(it) }
            preparedUpdate = prepared
            publish { it.copy(gameInstallReview = prepared.review) }
            "安装包已校验，请确认后交给系统安装"
        }
    }

    fun installPackageUpdate() = perform("准备系统安装", allowPackageUpdate = true) {
        require(app.packageManager.canRequestPackageInstalls()) { "请在系统设置中允许 BYBUS 安装应用" }
        runtime.stopGame()
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
                updatesRequest = state.updatesRequest + 1)
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
                updatesRequest = it.updatesRequest + 1) }
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

    fun launch() {
        // A Play/DNS outage must not stall the existing translation/launch path.
        checkGameUpdate(force = true)
        perform("准备启动") {
            stage("检查游戏兼容性", 0)
            val localCandidate = com.example.limbuszhcn.update.TranslationRepository(
                app.filesDir.toPath().resolve("translation-cache")).hasLaunchCandidate()
            val metadata = releaseCheck.start(if (localCandidate) 4_000 else 15_000)
            val approvedBuild = measureStartup("compatibility") { gameCompatibility.requireApproved() }
            measureStartup("game_sync") { prepareGame() }
            measureStartup("stop_previous_game") { runtime.stopGame() }
            val patch = installer(metadata::await) { stage(it, 1) }
            stage("检查零协更新并校验汉化文件", 1)
            val update = runCatching { patch.installFromGitHub(RELEASE_URL) }
            val summary = update.getOrElse { error ->
                // Validate only when needed; a failed check never discards a previously usable index.
                if (error is java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted) throw error
                val cached = runCatching { patch.verifyCachedForLaunch() }
                cached.getOrNull()?.also {
                    PersistentDiagnosticLog.warn(TAG, "Update unavailable; revalidated cached translation", error)
                } ?: throw IllegalStateException("${error.updateFailureLabel()}；本地汉化未通过完整校验", cached.exceptionOrNull() ?: error)
            }
            val active = if (summary.indexActivated) summary else patch.activeInstallSummary()
            publish { it.copy(translation = active) }
            stage("启动容器内游戏", 2)
            gameCompatibility.requireUnchanged(approvedBuild)
            receipt().beginLaunch()
            val runtimeStatus = receipt().description()
            publish { it.copy(runtimeStatus = runtimeStatus) }
            measureStartup("container_launch") { runtime.launchGame() }
            if (update.isFailure) "${update.exceptionOrNull()!!.updateFailureLabel()}；${summary.description()}；游戏已打开"
            else "${summary.description()}；游戏已打开"
        }
    }

    fun updateTranslation() = perform("检查零协更新并校验汉化文件") {
        require(readGameStatus().first.gameImported) { "请先启动一次游戏以准备容器" }
        runtime.stopGame()
        val patch = installer()
        val summary = patch.installFromGitHub(RELEASE_URL)
        val active = if (summary.indexActivated) summary else patch.activeInstallSummary()
        publish { it.copy(translation = active) }
        summary.description()
    }

    fun syncGame() = perform("重新同步游戏") {
        prepareGame(force = true)
        "游戏已同步，点击主按钮启动汉化版"
    }

    fun restore() = perform("正在停用汉化") {
        require(readGameStatus().first.gameImported) { "请先导入游戏" }
        runtime.stopGame()
        val result = installer().uninstallLatest()
        publish { it.copy(translation = null) }
        "已停用汉化 ${result.version}"
    }

    fun exportDiagnostics() = perform("正在生成诊断包") {
        val archive = DebugLogExporter(app).export(readGameStatus().first, installer().activeInstallSummary())
        publish { pendingArchive = archive; it.copy(exportRequest = archive) }
        "诊断包已生成，请选择保存位置"
    }

    fun exportPickerOpened() { state = state.copy(exportRequest = null) }

    fun saveDiagnostics(destination: Uri?) {
        val archive = pendingArchive ?: return
        pendingArchive = null
        perform(if (destination == null) "取消导出" else "正在保存诊断包") {
            try {
                if (destination == null) "已取消导出诊断包"
                else {
                    app.contentResolver.openOutputStream(destination, "w").use { output ->
                        requireNotNull(output) { "无法打开所选保存位置" }
                        archive.inputStream().use { it.copyTo(output) }
                    }
                    "诊断包已保存"
                }
            } finally { archive.delete() }
        }
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
    indexActivated && reusedIndex -> "汉化 $version 已校验，复用现有索引"
    indexActivated -> "汉化 $version 索引已保存，有效资源 $availableSourceFiles/$candidateSourceFiles"
    previousIndexPreserved -> "${pendingReason ?: "资源尚未完整"}；继续使用现有汉化"
    else -> "汉化包已保存，请在游戏中下载完整资源，退出后再从本应用启动"
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
