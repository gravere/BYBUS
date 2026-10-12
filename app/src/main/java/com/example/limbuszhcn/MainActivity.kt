package com.example.limbuszhcn

import android.os.Bundle
import android.os.Process
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import com.example.limbuszhcn.container.AndroidContainerRuntimeFactory
import com.example.limbuszhcn.container.AndroidInstalledGameImporter
import com.example.limbuszhcn.container.ContainerPatchInstaller
import com.example.limbuszhcn.container.ContainerRuntime
import com.example.limbuszhcn.container.ContainerStatus
import com.example.limbuszhcn.container.GameImportRequest
import com.example.limbuszhcn.container.LocalizeSnapshotExporter
import com.example.limbuszhcn.container.MicrogContainerConfig
import com.example.limbuszhcn.container.TranslationRedirectGate
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import java.io.File

/**
 * 汉化器主界面，负责准备容器、从 GitHub Releases 更新汉化并启动容器内游戏。
 */
class MainActivity : ComponentActivity() {
    private val containerRuntime: ContainerRuntime by lazy {
        AndroidContainerRuntimeFactory.create(applicationContext, filesDir.toPath().resolve("container-workspace"))
    }

    /**
     * 创建主界面并初始化容器运行时，同时迁移旧版本遗留的中转服务配置。
     *
     * @param savedInstanceState Android 保存的 Activity 状态；首次创建时为 `null`。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        enableEdgeToEdge()
        getSharedPreferences("limbus_zhcn", MODE_PRIVATE).edit()
            .remove(LEGACY_KEY_RELAY_BASE_URL).remove(LEGACY_KEY_TOKEN)
            .remove(LEGACY_KEY_TRANSLATION_CHANNEL).remove(KEY_GITHUB_RELEASE_URL).apply()
        val model = androidx.lifecycle.ViewModelProvider(this)[LauncherViewModel::class.java]
        // Saved Compose state already knows whether this request was handled or dismissed.
        if (savedInstanceState == null && intent?.action == com.example.limbuszhcn.update.GameStores.UPDATE_ACTION)
            model.requestGameUpdates()
        launchContainerGameFromDebugIntent(intent)
        setContent {
            val state = model.state
            val appUpdater = model.appUpdater
            val appInstaller = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                appUpdater.installationReturned()
            }
            val packagePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments(), model::preparePackageUpdate)
            val resumed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.runtime.DisposableEffect(lifecycle) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
                    resumed.value = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            androidx.compose.runtime.LaunchedEffect(state.gameInstallConfirmation, resumed.value) {
                if (resumed.value) state.gameInstallConfirmation?.let { confirmation ->
                    model.installConfirmationOpened()
                    runCatching { startActivity(confirmation) }.onFailure(model::showError)
                }
            }
            androidx.compose.runtime.LaunchedEffect(appUpdater.installRequest, resumed.value) {
                if (resumed.value) appUpdater.installRequest?.let { file ->
                    appUpdater.installationOpened()
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.app-updates", file)
                        appInstaller.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .apply { clipData = android.content.ClipData.newRawUri("BYBUS", uri) })
                    }.onFailure { appUpdater.installationError() }
                }
            }
            val exportPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip"), model::saveDiagnostics
            )
            androidx.compose.runtime.LaunchedEffect(state.exportRequest) {
                state.exportRequest?.let { archive ->
                    model.exportPickerOpened()
                    exportPicker.launch(archive.name)
                }
            }
            LimbusZhCNTheme {
                LauncherScreen(
                    state = state,
                    projectState = model.projectState,
                    appUpdateState = appUpdater.state,
                    onCheckAppUpdate = { appUpdater.check(force = true) },
                    onDownloadAppUpdate = appUpdater::download,
                    onCancelAppUpdate = appUpdater::cancel,
                    onInstallAppUpdate = {
                        if (!packageManager.canRequestPackageInstalls()) {
                            appUpdater.installationPermissionNeeded()
                            runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName"))) }.onFailure { appUpdater.installationError() }
                        } else appUpdater.install()
                    },
                    onOpenAppRelease = {
                        runCatching { openProjectPage(com.example.limbuszhcn.selfupdate.AppReleases.PAGE) }
                            .onFailure { appUpdater.installationError() }
                    },
                    onLaunch = model::launch,
                    onRefresh = model::refresh,
                    onUpdate = model::updateTranslation,
                    onCheckGameUpdate = model::checkGameUpdate,
                    onGameStore = model::requestGameUpdates,
                    onSelectGameSource = model::selectGameSource,
                    onOpenGamePage = { url -> runCatching { com.example.limbuszhcn.update.GameStores.openPage(this, url) }.onFailure(model::showError) },
                    onOpenInstallSource = { runCatching { com.example.limbuszhcn.update.GameStores.openSource(this, state.installSource) }.onFailure(model::showError) },
                    onImportGame = { packagePicker.launch(arrayOf("*/*")) },
                    onInstallGame = {
                        if (!packageManager.canRequestPackageInstalls()) {
                            runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName"))) }.onFailure(model::showError)
                        } else model.installPackageUpdate()
                    },
                    onCancelGameInstall = model::cancelPackageUpdate,
                    onSync = model::syncGame,
                    onRestore = model::restore,
                    onExport = model::exportDiagnostics,
                    onGoogleSettings = model::openGoogleSettings,
                    onRefreshProject = { model.refreshProject(force = true) },
                    onSelectProjectSource = model::selectProjectSource,
                    onOpenLink = { url ->
                        if (url in setOf(com.example.limbuszhcn.tracking.ZeroProject.URL,
                                com.example.limbuszhcn.tracking.ZeroProject.SNAPSHOT_URL,
                                com.example.limbuszhcn.tracking.ZeroGitHub.RELEASES)) {
                            runCatching { openProjectPage(url) }
                                .onFailure {
                                    android.widget.Toast.makeText(this, "暂时无法打开浏览器", android.widget.Toast.LENGTH_SHORT).show()
                                }
                        }
                    },
                    onSettings = {
                        runCatching { startActivity(DeviceCompatibility(applicationContext).settingsIntent()) }
                            .onFailure(model::showError)
                    }
                )
            }
        }
    }

    private fun openProjectPage(url: String) {
        val page = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = packageManager.resolveActivity(page, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val target = resolved?.activityInfo
        if (resolved != null && resolved.match > 0 && target != null && target.exported && target.packageName != packageName) {
            page.component = android.content.ComponentName(target.packageName, target.name)
            if (runCatching { startActivity(page) }.isSuccess) return
        }
        startActivity(Intent.createChooser(Intent(page).setComponent(null), "打开项目页面"))
    }

    /** Returning from the guest refreshes its last launch receipt. */
    override fun onResume() {
        super.onResume()
        androidx.lifecycle.ViewModelProvider(this)[LauncherViewModel::class.java].refresh()
    }

    /** 处理复用现有 Activity 时收到的受限调试 Intent。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "MainActivity.onNewIntent pid=${Process.myPid()} taskId=$taskId intent=${intent.describeForLog()} stack=${shortStack()}")
        launchContainerGameFromDebugIntent(intent)
        if (intent.action == com.example.limbuszhcn.update.GameStores.UPDATE_ACTION)
            androidx.lifecycle.ViewModelProvider(this)[LauncherViewModel::class.java].requestGameUpdates()
    }

    /**
     * 销毁主界面并记录任务状态，便于定位宿主与虚拟游戏之间的生命周期切换。
     */
    override fun onDestroy() {
        Log.i(TAG, "MainActivity.onDestroy pid=${Process.myPid()} taskId=$taskId finishing=$isFinishing changingConfigurations=$isChangingConfigurations")
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_LAUNCH_CONTAINER_GAME = "launch_container_game"
        private const val EXTRA_SYNC_INSTALLED_GAME = "sync_installed_game"
        private const val EXTRA_EXPORT_LOCALIZE_SNAPSHOT = "export_localize_snapshot"
        private const val EXTRA_REFRESH_TRANSLATION_REDIRECTS = "refresh_translation_redirects"
        private const val EXTRA_ENABLE_TRANSLATION_REDIRECTS = "enable_translation_redirects"
        private const val EXTRA_MICROG_APK_PATHS = "microg_apk_paths"
        private const val EXTRA_MICROG_APK_URLS = "microg_apk_urls"
        private const val KEY_GITHUB_RELEASE_URL = "github_release_url"
        private const val LEGACY_KEY_RELAY_BASE_URL = "relay_base_url"
        private const val LEGACY_KEY_TOKEN = "token"
        private const val LEGACY_KEY_TRANSLATION_CHANNEL = "translation_channel"
        private const val TAG = "LimbusZhCN"
    }

    private fun launchContainerGameFromDebugIntent(intent: Intent?) {
        if (BuildConfig.BUILD_TYPE != "debug" || intent == null || !intent.hasDebugContainerAction()) {
            return
        }
        Thread {
            runCatching {
                com.example.limbuszhcn.storage.OperationLock.run(filesDir.toPath()) {
                if (intent.getBooleanExtra(EXTRA_SYNC_INSTALLED_GAME, false) || intent.getBooleanExtra(EXTRA_REFRESH_TRANSLATION_REDIRECTS, false)) containerRuntime.stopGame()
                configureMicrogFromDebugIntent(intent)
                if (intent.getBooleanExtra(EXTRA_SYNC_INSTALLED_GAME, false)) {
                    Log.i(TAG, "Debug intent syncing installed game before launch")
                    val installed = AndroidInstalledGameImporter(
                        packageManager = packageManager,
                        importDirectory = File(filesDir, "container-imports")
                    ).copyInstalledGame()
                    val result = containerRuntime.importGame(
                        GameImportRequest(
                            splitApkPaths = installed.splitApkPaths,
                            versionCode = installed.versionCode,
                            source = "installed"
                        )
                    )
                    Log.i(TAG, "Debug intent synced installed game version=${installed.versionCode} splits=${result.importedSplits}")
                }
                if (intent.getBooleanExtra(EXTRA_EXPORT_LOCALIZE_SNAPSHOT, false)) {
                    val storage = containerRuntime.storage()
                    require(storage is com.example.limbuszhcn.container.ContainerWorkspaceStorage) {
                        "Localize snapshot export requires container workspace storage"
                    }
                    val result = LocalizeSnapshotExporter(applicationContext, storage).exportSnapshot()
                    Log.i(
                        TAG,
                        "Debug intent exported Localize snapshot zip=${result.zipFile.absolutePath} " +
                            "summary=${result.summaryFile.absolutePath} json=${result.jsonFileCount} bytes=${result.totalBytes}"
                    )
                }
                if (intent.getBooleanExtra(EXTRA_REFRESH_TRANSLATION_REDIRECTS, false)) {
                    val summary = ContainerPatchInstaller(
                        cacheRoot = filesDir.toPath().resolve("translation-cache"),
                        storage = containerRuntime.storage(),
                        installedApkPaths = {
                            AndroidInstalledGameImporter(packageManager, File(filesDir, "container-imports"))
                                .copyInstalledGame().splitApkPaths
                        }
                    ).refreshActiveRedirects()
                    Log.i(TAG, "Debug intent refreshed translation redirects version=${summary.version} files=${summary.writtenFiles}")
                }
                if (intent.getBooleanExtra(EXTRA_LAUNCH_CONTAINER_GAME, false)) {
                    Log.i(TAG, "Debug intent launching container game")
                    containerRuntime.launchGame()
                }
                if (intent.getBooleanExtra(EXTRA_ENABLE_TRANSLATION_REDIRECTS, false)) {
                    require(!intent.getBooleanExtra(EXTRA_LAUNCH_CONTAINER_GAME, false)) {
                        "Enable translation redirects only after the running game finishes resource validation"
                    }
                    val pid = TranslationRedirectGate(applicationContext).openForRunningGame()
                    Log.i(TAG, "Debug intent enabled translation redirects for pid=$pid")
                }
                }
            }.onSuccess {
                recordDebugIntentResult("SUCCESS")
            }.onFailure { error ->
                recordDebugIntentResult("FAILURE", error)
                Log.e(TAG, "Debug intent launch failed", error)
            }
        }.start()
    }

    private fun recordDebugIntentResult(status: String, error: Throwable? = null) {
        runCatching {
            // ColorOS 可能在高日志量时丢弃应用日志。额外写入一个仅调试包可读取的结果文件，
            // 让 ADB/Android Studio 即使遇到日志流控也能取得完整故障链。
            File(filesDir, "debug-intent-result.txt").writeText(
                buildString {
                    appendLine("status=$status")
                    appendLine("time=${System.currentTimeMillis()}")
                    if (error != null) {
                        appendLine("type=${error.javaClass.name}")
                        appendLine("message=${error.message.orEmpty()}")
                        append(error.stackTraceToString())
                    }
                }
            )
        }.onFailure { writeError ->
            Log.e(TAG, "Unable to persist debug intent result", writeError)
        }
    }

    private fun configureMicrogFromDebugIntent(intent: Intent) {
        val rawUrls = intent.getStringExtra(EXTRA_MICROG_APK_URLS)
        if (rawUrls != null) {
            val urls = rawUrls.split('|').map(String::trim).filter(String::isNotEmpty)
            MicrogContainerConfig(applicationContext).configureFromUrls(urls)
            Log.i(TAG, "Downloaded container microG apkCount=${urls.size}")
            return
        }
        val rawPaths = intent.getStringExtra(EXTRA_MICROG_APK_PATHS) ?: return
        val sourceApks = rawPaths
            .split(File.pathSeparatorChar)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::File)
        MicrogContainerConfig(applicationContext).configure(sourceApks)
        Log.i(TAG, "Configured container microG apkCount=${sourceApks.size}")
    }
}

private fun Intent.hasDebugContainerAction(): Boolean =
    getBooleanExtra("launch_container_game", false) ||
        getBooleanExtra("sync_installed_game", false) ||
        getBooleanExtra("export_localize_snapshot", false) ||
        getBooleanExtra("refresh_translation_redirects", false) ||
        getBooleanExtra("enable_translation_redirects", false)

private fun Intent?.describeForLog(): String =
    if (this == null) {
        "null"
    } else {
        "action=$action component=$component package=$`package` flags=0x${flags.toString(16)} data=$data type=$type categories=$categories extras=${extras?.keySet()}"
    }

private fun shortStack(): String =
    Throwable().stackTrace
        .drop(1)
        .take(6)
        .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

internal fun Throwable.userMessage(): String {
    val chain = generateSequence(this) { it.cause }.toList()
    return chain.joinToString(separator = "\n") { error ->
        val message = error.message?.takeIf { it.isNotBlank() }
        if (message == null) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
    }.take(900)
}

internal fun ContainerStatus.withInstalledVersion(installedVersionCode: Long?): ContainerStatus {
    if (installedVersionCode == null) {
        return copy(message = "未检测到边狱巴士，请选择更新渠道或完整安装包")
    }
    if (!gameImported || importedVersionCode == null) {
        return copy(message = "已检测到边狱巴士 v$installedVersionCode；点击启动后自动导入")
    }
    if (installedVersionCode == importedVersionCode) {
        return copy(message = "$message,已匹配当前安装版本")
    }
    return copy(
        message = "系统已安装版本 v$installedVersionCode,容器内版本 v$importedVersionCode,启动时同步并保留资源"
    )
}
