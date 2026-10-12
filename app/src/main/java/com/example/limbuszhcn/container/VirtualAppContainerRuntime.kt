package com.example.limbuszhcn.container

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.remote.InstallOptions
import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.measureStartup
import java.nio.file.Files
import java.util.UUID
import com.lody.virtual.client.ipc.VActivityManager
import java.io.File
import java.util.zip.ZipFile
import java.security.MessageDigest

/**
 * 使用 VirtualApp 承载官方 Limbus Company，并将容器能力接入汉化器业务层。
 *
 * @param context 宿主应用上下文。
 * @param fallback 保存导入元数据和本地工作区的基础实现。
 */
class VirtualAppContainerRuntime(
    private val context: Context,
    private val fallback: LocalContainerRuntime
) : ContainerRuntime {
    private val bridge = VirtualAppBridge(context)

    /**
     * 读取容器、游戏导入记录以及当前 APK 来源的综合状态。
     *
     * @return 供启动页决定是否需要重新同步的容器状态。
     */
    override fun status(): ContainerStatus {
        val local = fallback.status()
        return if (bridge.available) {
            // 版本号相同并不代表运行时来源一致。应用更新或旧安装可能让 VirtualApp
            // 继续引用私有 base-1.apk，AppSealing 随后会在 PlayDone 后阻断标题页。
            val requiresSourceResync = bridge.requiresInstalledSourceResync(LocalContainerRuntime.GAME_PACKAGE)
            local.copy(
                available = true,
                message = local.message + if (requiresSourceResync) {
                    "; VirtualApp APK 来源需要重新同步"
                } else {
                    "; 容器初始化完成，游戏运行待验证"
                },
                requiresInstalledGameResync = requiresSourceResync || local.requiresInstalledGameResync
            )
        } else {
            local.copy(
                available = false,
                message = local.message + "; VirtualApp 引擎不可用: ${bridge.unavailableReason}"
            )
        }
    }

    /**
     * 记录导入元数据，并把官方游戏及所需的原生库同步到 VirtualApp。
     *
     * @param request 官方 APK/split 路径、版本和来源信息。
     * @return 实际导入的包名、split 数量与版本信息。
     */
    override fun importGame(request: GameImportRequest): GameImportResult {
        bridge.requireAvailable()
        require(request.source == "installed" || request.splitApkPaths.size == 1) { "手动导入不支持 split APK" }
        stopGame()
        return fallback.importGame(request) { prepared ->
            val result = bridge.installBaseApk(prepared.splitApkPaths.first(), prepared.source == "installed")
            require(result.success && result.packageName == LocalContainerRuntime.GAME_PACKAGE) {
                "容器安装未完成，请重新同步游戏: ${result.error.orEmpty()}"
            }
            bridge.ensureGoogleAppsInstalled()
            val count = bridge.installNativeLibraries(LocalContainerRuntime.GAME_PACKAGE, prepared.splitApkPaths)
            require(count > 0) { "游戏原生库缺失，请重新同步游戏" }
            if (prepared.source == "installed") {
                val current = AndroidInstalledGameImporter.installedVersionCode(context.packageManager)
                require(current == prepared.versionCode) { "同步期间系统更新了游戏，请重试同步" }
                require(!bridge.requiresInstalledSourceResync(LocalContainerRuntime.GAME_PACKAGE)) { "容器 APK 来源校验失败" }
            }
        }
    }

    override fun stopGame() = bridge.stopExistingGameProcess(LocalContainerRuntime.GAME_PACKAGE)

    fun openGoogleSettings() {
        bridge.ensureGoogleAppsInstalled()
        bridge.openGoogleSettings()
    }

    /**
     * 获取指向 VirtualApp 游戏数据目录的汉化资源存储。
     *
     * @return 引擎可用时返回容器存储，否则返回基础实现以便显示诊断信息。
     */
    override fun storage() =
        if (bridge.available) {
            ContainerWorkspaceStorage(bridge.localizeRoot(LocalContainerRuntime.GAME_PACKAGE).toPath())
        } else {
            fallback.storage()
        }

    /**
     * 准备容器存储和 Google 服务后，通过 VirtualApp 入口启动游戏。
     */
    override fun launchGame() {
        Log.i(TAG, "launchGame requested pid=${Process.myPid()}")
        bridge.requireAvailable()
        require(!fallback.status().requiresInstalledGameResync) { "上次游戏同步未完成，请重新同步后启动" }
        // Redirects must not survive into the next resource-validation pass.
        TranslationRedirectGate(context).close()
        ensureGameInstalledInVirtualApp()
        val googlePackages = measureStartup("google_components") { bridge.ensureGoogleAppsInstalled() }
        Log.i(TAG, "launchGame ensured Google packages=$googlePackages")
        bridge.logGamePackageDiagnostics(LocalContainerRuntime.GAME_PACKAGE)
        bridge.ensureGameLocalizeRoot(LocalContainerRuntime.GAME_PACKAGE)
        bridge.logGameLocalizeDiagnostics(LocalContainerRuntime.GAME_PACKAGE)
        // This method is invoked by the host launch worker, before the guest main thread exists.
        measureStartup("font_validation") { com.lody.virtual.helper.compat.LimbusTranslationAssets.prepare(context) }
        measureStartup("activity_handoff") { bridge.launch(LocalContainerRuntime.GAME_PACKAGE) }
    }

    private fun ensureGameInstalledInVirtualApp() {
        // Normal launch prepares the installed game through the single transactional import path.
        // A missing registration must not trigger a second, unmarked source-changing installer here.
        require(bridge.isVirtualAppInstalled(LocalContainerRuntime.GAME_PACKAGE)) {
            "容器游戏记录缺失，请重新同步游戏后启动"
        }
    }

    companion object {
        private const val TAG = "LimbusContainer"

        /**
         * 判断当前 APK 是否已包含 VirtualApp 运行时。
         *
         * @return 能加载 VirtualCore 时返回 `true`。
         */
        fun isEnginePackaged(): Boolean =
            runCatching { Class.forName("com.lody.virtual.client.core.VirtualCore") }.isSuccess
    }
}

private class VirtualAppBridge(
    private val context: Context
) {
    val available: Boolean get() = VirtualCore.get().isStartup && ContainerEngineHealth.initialized
    val unavailableReason: String get() = ContainerEngineHealth.failure ?: "容器尚未完成初始化"

    fun requireAvailable() {
        if (!available) throw ContainerRuntimeUnavailableException(unavailableReason)
    }

    fun installBaseApk(apkPath: String, useSourceLocationApk: Boolean): VirtualInstallResult {
        requireAvailable()
        val options = InstallOptions.makeOptions(useSourceLocationApk, false, InstallOptions.UpdateStrategy.FORCE_UPDATE)
        val result = VirtualCore.get().installPackageSync(apkPath, options)
        return VirtualInstallResult(result.isSuccess, result.packageName, result.error)
    }

    fun launch(packageName: String) {
        requireAvailable()
        // Native 索引在进程初始化时加载；恢复旧任务无法使用刚生成的索引。
        // 等待 VAMS 的延迟终止完成，避免把新 Activity 交给正在退出的旧进程。
        stopExistingGameProcess(packageName)
        removePreviousGameTasks(packageName)
        val intent = VirtualCore.get().getLaunchIntent(packageName, 0)
            ?: throw ContainerRuntimeUnavailableException("VirtualApp 未找到启动入口: $packageName")
        Log.i(TAG, "VirtualApp launch intent=${intent.describeForLog()}")
        requireGameLaunchIntent(intent, packageName)
        val handoff = GameLaunchHandoff(packageName)
        VirtualCore.get().setUiCallback(intent, object : VirtualCore.UiCallback() {
            override fun onAppOpened(actualPackage: String?, userId: Int) = handoff.onAppOpened(actualPackage, userId)
            override fun isLaunched(actualPackage: String?, userId: Int): Boolean = handoff.isOpened(actualPackage, userId)
        })
        startVirtualActivity(intent, packageName)
        handoff.await()
    }

    fun stopExistingGameProcess(packageName: String) {
        val manager = VActivityManager.get()
        fun isRunning(): Boolean = manager.getRunningAppProcesses(packageName, 0)
            .any { it.pid > 0 && it.packageName == packageName }
        if (!isRunning()) return
        manager.killAppByPkg(packageName, 0)
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (isRunning()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw ContainerRuntimeUnavailableException("旧游戏进程仍在退出，请稍后再次启动")
            }
            Thread.sleep(100)
        }
        Log.i(TAG, "Previous virtual game process stopped for fresh translation index")
    }

    /**
     * 删除已停止的游戏 ShadowActivity 任务，保留启动器与 Google 服务任务。
     *
     * @param packageName 容器内的目标游戏包名。
     */
    private fun removePreviousGameTasks(packageName: String) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return
        runCatching {
            activityManager.appTasks.filter { appTask ->
                val taskInfo = appTask.taskInfo
                isVirtualGameTask(
                    packageName = packageName,
                    baseIntentType = taskInfo.baseIntent?.type,
                    baseActivityClassName = taskInfo.baseActivity?.className,
                    topActivityClassName = taskInfo.topActivity?.className
                )
            }.forEach { it.finishAndRemoveTask() }
        }.onFailure { error ->
            // 厂商 ROM 若限制任务查询，仍可继续使用标准 VAMS 启动路径。
            Log.w(TAG, "Unable to remove previous VirtualApp game task package=$packageName", error)
        }
    }

    fun ensureGoogleAppsInstalled(): List<String> {
        requireAvailable()
        val microgConfig = MicrogContainerConfig(context)
        val microgApks = microgConfig.microgApks()
        if (microgApks.isEmpty()) {
            throw ContainerRuntimeUnavailableException(
                "内置 microG 组件不可用，请重新安装完整的汉化器 APK"
            )
        }
        // Validate the whole set before stopping any process or replacing a package. Reading the
        // installed APK avoids PackageManager's intentional guest-facing signature compatibility.
        val components = microgApks.map { apk -> apk to readMicrogIdentity(apk) }
            .sortedBy { MicrogUpgradePolicy.packages.indexOf(it.second.packageName) }
        require(components.map { it.second.packageName } == MicrogUpgradePolicy.packages) {
            "Google 组件不完整，未更改已有数据"
        }
        val previous = components.associate { (_, target) ->
            target.packageName to VirtualCore.get().getInstalledAppInfo(target.packageName, 0)
                ?.let { File(it.apkPath) }
        }
        val changes = components.filter { (_, target) ->
            MicrogUpgradePolicy.needsInstall(target, previous[target.packageName]?.let(::readMicrogIdentity))
        }
        if (changes.isNotEmpty()) {
            stopExistingGameProcess(LocalContainerRuntime.GAME_PACKAGE)
            MicrogUpgradePolicy.packages.forEach(::stopExistingGameProcess)
            val rollback = File(context.filesDir, "microg-upgrade-rollback")
            check(rollback.isDirectory || rollback.mkdirs()) { "无法准备 Google 组件升级" }
            // Fixed paths; only APKs are copied, never account databases or game resources.
            val backups = changes.associate { (_, target) ->
                target.packageName to previous[target.packageName]?.let { original ->
                    File(rollback, "${target.packageName}.apk").also { backup ->
                        original.copyTo(backup, overwrite = true)
                        require(readMicrogIdentity(backup) == readMicrogIdentity(original)) {
                            "无法校验 Google 组件回退文件"
                        }
                    }
                }
            }
            val attempted = mutableListOf<String>()
            try {
                changes.forEach { (apk, target) ->
                    attempted += target.packageName
                    installMicrogComponent(apk, target.packageName)
                    val installed = VirtualCore.get().getInstalledAppInfo(target.packageName, 0)
                        ?: error("Google 组件升级后未找到安装记录")
                    check(readMicrogIdentity(File(installed.apkPath)) == target) { "Google 组件升级校验失败" }
                }
            } catch (error: Exception) {
                // VirtualApp can replace an APK before reporting a failure. Restore every attempted
                // existing component, including the one that failed; never uninstall or clear data.
                attempted.asReversed().forEach { packageName ->
                    backups[packageName]?.let { backup ->
                        runCatching { installMicrogComponent(backup, packageName) }
                            .onFailure { error.addSuppressed(it) }
                    }
                }
                throw error
            }
            backups.values.filterNotNull().forEach { it.delete() }
            rollback.delete() // Succeeds only if empty; no recursive deletion.
        }
        val installedApks = components.map { (_, target) ->
            val info = requireNotNull(VirtualCore.get().getInstalledAppInfo(target.packageName, 0)) {
                "Google 组件安装记录缺失"
            }
            File(info.apkPath)
        }
        microgConfig.markMigrationCompleted(installedApks)
        return components.map { it.second.packageName }
    }

    @Suppress("DEPRECATION")
    private fun readMicrogIdentity(apk: File): MicrogPackageIdentity {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            ?: throw ContainerRuntimeUnavailableException("无法识别 Google 组件安装文件")
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return MicrogPackageIdentity(info.packageName,
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            signers.orEmpty().map { signer ->
                MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }.toSet())
    }

    private fun installMicrogComponent(apk: File, packageName: String) {
        val result = installBaseApk(apk.absolutePath, useSourceLocationApk = false)
        check(result.success && result.packageName == packageName) { "Google 组件安装失败：${result.error.orEmpty()}" }
        installNativeLibraries(packageName, listOf(apk.absolutePath))
        Log.i(TAG, "Installed container Google component package=$packageName")
    }

    fun openGoogleSettings() {
        requireAvailable()
        val packageName = "com.google.android.gms"
        val intent = Intent().setComponent(android.content.ComponentName(packageName,
            "org.microg.gms.ui.MainSettingsActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        require(VirtualCore.get().resolveActivityInfo(intent, 0) != null) { "容器 Google 设置入口不可用" }
        startVirtualActivity(intent, packageName)
    }

    fun localizeRoot(packageName: String): File {
        requireAvailable()
        val appData = VEnvironment.getExternalStorageAppDataDir(0, packageName)
        return appData.resolve("files/Assets/Resources_moved/Localize")
    }

    fun ensureGameLocalizeRoot(packageName: String) {
        val root = localizeRoot(packageName)
        if (root.isDirectory) {
            return
        }
        val created = root.mkdirs()
        Log.i(TAG, "ensureGameLocalizeRoot root=${root.absolutePath} created=$created exists=${root.isDirectory}")
    }

    fun logGamePackageDiagnostics(packageName: String) {
        requireAvailable()
        val installed = runCatching { isVirtualAppInstalled(packageName) }.getOrElse {
            Log.w(TAG, "diagnostics isVirtualAppInstalled failed package=$packageName", it)
            false
        }
        val component = android.content.ComponentName(packageName, "com.unity3d.player.UnityPlayerActivity")
        val activityInfo = runCatching {
            VPackageManager.get().getActivityInfo(component, 0, 0)
        }.onFailure {
            Log.w(TAG, "diagnostics getActivityInfo failed component=$component", it)
        }.getOrNull()
        val launcherCount = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
            VPackageManager.get().queryIntentActivities(intent, intent.resolveType(context), 0, 0).size
        }.onFailure {
            Log.w(TAG, "diagnostics query launcher failed package=$packageName", it)
        }.getOrDefault(-1)
        Log.i(TAG, "diagnostics package=$packageName installed=$installed activityInfo=${activityInfo != null} launcherCount=$launcherCount")
    }

    fun logGameLocalizeDiagnostics(packageName: String) {
        val root = localizeRoot(packageName)
        val remoteList = root.resolve("RemoteLocalizeFileList.json")
        val localizeZip = root.resolve("localize_en.zip")
        val enDir = root.resolve("en")
        val tempDir = root.resolve("Temp/LocalizeTemp_en/LocalizeTemp_en")
        Log.i(
            TAG,
            "localize diagnostics root=${root.absolutePath} rootExists=${root.exists()} " +
                "remoteList=${remoteList.exists()}:${remoteList.length()} " +
                "localizeZip=${localizeZip.exists()}:${localizeZip.length()} " +
                "enDir=${enDir.exists()}:${enDir.listFiles()?.size ?: -1} " +
                "tempDir=${tempDir.exists()}:${tempDir.listFiles()?.size ?: -1}"
        )
    }

    fun installNativeLibraries(packageName: String, apkPaths: List<String>): Int {
        requireAvailable()
        require(apkPaths.isNotEmpty() && apkPaths.all { File(it).isFile }) { "原生库来源 APK 不完整" }
        val target = VEnvironment.getAppLibDirectory(packageName).toPath()
        Files.createDirectories(target.parent)
        val staged = target.resolveSibling(".${target.fileName}.staging-${UUID.randomUUID()}")
        Files.createDirectories(staged)
        var count = 0
        try {
            apkPaths.forEach { path ->
                ZipFile(path).use { zip ->
                    val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }.toList()
                    val abi = Build.SUPPORTED_ABIS.firstOrNull { abi -> entries.any { it.name.startsWith("lib/$abi/") } }
                    require(entries.isEmpty() || abi != null) { "游戏组件没有设备支持的原生 ABI" }
                    entries.filter { abi != null && it.name.startsWith("lib/$abi/") }.forEach { entry ->
                        val file = staged.resolve(entry.name.substringAfterLast('/'))
                        require(!Files.exists(file)) { "不同 APK 包含重复原生库，拒绝覆盖" }
                        zip.getInputStream(entry).use { input ->
                            java.io.FileOutputStream(file.toFile()).use { output ->
                                val copied = input.copyTo(output)
                                require(copied > 0 && (entry.size < 0 || copied == entry.size)) { "原生库复制不完整" }
                                output.fd.sync()
                            }
                        }
                        file.toFile().setReadable(true, false)
                        file.toFile().setExecutable(true, false)
                        count++
                    }
                }
            }
            if (packageName == LocalContainerRuntime.GAME_PACKAGE) {
                require(Files.isRegularFile(staged.resolve("libil2cpp.so"))) { "缺少游戏核心原生库" }
            }
            AtomicFiles.replaceDirectory(staged, target)
            return count
        } finally {
            if (Files.exists(staged)) AtomicFiles.deleteOwnedTree(target.parent, staged)
        }
    }

    fun isVirtualAppInstalled(packageName: String): Boolean = VirtualCore.get().isAppInstalled(packageName)

    /**
     * 判断 VirtualApp 的游戏记录是否仍引用旧的私有 APK，而不是当前系统安装源。
     *
     * 同版本更新不会触发旧的版本号比较，但 AppSealing 会同时核对 APK 路径与包信息，
     * 因此必须把来源路径作为独立的新鲜度条件。读取失败时保守地保持现状，避免仅因
     * 一次反射异常就重复复制体积较大的 split APK。
     */
    fun requiresInstalledSourceResync(packageName: String): Boolean {
        if (!isVirtualAppInstalled(packageName)) {
            return true
        }
        val installedSourceDir = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).sourceDir
        }.onFailure { error ->
            Log.w(TAG, "Unable to read installed game source package=$packageName", error)
        }.getOrNull() ?: return true
        val virtualSourceDir = runCatching {
            VPackageManager.get().getApplicationInfo(packageName, 0, 0)?.sourceDir
        }.onFailure { error ->
            Log.w(TAG, "Unable to read VirtualApp game source package=$packageName", error)
        }.getOrNull() ?: return true
        val needsResync = installedSourceNeedsResync(installedSourceDir, virtualSourceDir)
        Log.i(
            TAG,
            "game source freshness package=$packageName installed=$installedSourceDir " +
                "virtual=$virtualSourceDir requiresResync=$needsResync"
        )
        return needsResync
    }

    private fun installedPackageApkPaths(packageName: String): List<String> =
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            buildList {
                add(info.sourceDir)
                info.splitSourceDirs?.let(::addAll)
            }.filter { it.isNotBlank() }
        }.onFailure { error ->
            Log.w(TAG, "installedPackageApkPaths unavailable package=$packageName", error)
        }.getOrDefault(emptyList())

    private fun startVirtualActivity(intent: Intent, packageName: String) {
        Log.i(TAG, "VActivityManager.startActivity call package=$packageName intent=${intent.describeForLog()}")
        val result = VActivityManager.get().startActivity(intent, 0)
        Log.i(TAG, "VActivityManager.startActivity result=$result package=$packageName intent=${intent.describeForLog()}")
        if (result < 0) {
            throw ContainerRuntimeUnavailableException("VirtualApp 启动失败: result=$result, package=$packageName, intent=$intent")
        }
    }

    private fun requireGameLaunchIntent(intent: Intent, packageName: String) {
        val componentPackage = intent.component?.packageName
        val intentPackage = intent.`package`
        val resolvedPackage = componentPackage ?: intentPackage
        Log.i(TAG, "requireGameLaunchIntent expected=$packageName componentPackage=$componentPackage intentPackage=$intentPackage intent=${intent.describeForLog()}")
        if (resolvedPackage != packageName) {
            throw ContainerRuntimeUnavailableException(
                "拒绝启动非游戏入口: expected=$packageName, actual=${resolvedPackage ?: "null"}, intent=$intent"
            )
        }
    }


}

private data class VirtualInstallResult(
    val success: Boolean,
    val packageName: String?,
    val error: String?
)

private const val TAG = "LimbusContainer"

/**
 * 比较系统安装源与 VirtualApp 记录的 base APK 路径。
 *
 * @param installedSourceDir Google Play 当前安装的 base APK 路径。
 * @param virtualSourceDir VirtualApp 包记录中的 base APK 路径。
 * @return 两者均可判断且路径不一致时返回 `true`。
 */
internal fun installedSourceNeedsResync(installedSourceDir: String?, virtualSourceDir: String?): Boolean =
    !installedSourceDir.isNullOrBlank() &&
        !virtualSourceDir.isNullOrBlank() &&
        File(installedSourceDir).absolutePath != File(virtualSourceDir).absolutePath

/**
 * 判断宿主任务是否是指定虚拟包的 VirtualApp 游戏任务。
 *
 * VirtualApp 把真实目标组件写入 ShadowActivity 基础 Intent 的 MIME type。同时校验
 * ShadowActivity 类名可避免把同宿主的启动器任务误认为游戏。
 *
 * @param packageName 目标虚拟包名。
 * @param baseIntentType 任务基础 Intent 的 MIME type。
 * @param baseActivityClassName 任务根 Activity 类名。
 * @param topActivityClassName 任务顶部 Activity 类名。
 * @return MIME type 指向目标包且任务由 ShadowActivity 承载时返回 `true`。
 */
internal fun isVirtualGameTask(
    packageName: String,
    baseIntentType: String?,
    baseActivityClassName: String?,
    topActivityClassName: String?
): Boolean {
    val targetsPackage = baseIntentType?.startsWith("$packageName/") == true
    val usesShadowActivity = sequenceOf(baseActivityClassName, topActivityClassName)
        .filterNotNull()
        .any { className -> className.startsWith("com.lody.virtual.client.stub.ShadowActivity") }
    return targetsPackage && usesShadowActivity
}

private fun Intent.describeForLog(): String =
    "action=$action component=$component package=$`package` flags=0x${flags.toString(16)} data=$data type=$type categories=$categories extras=${extras?.keySet()}"
