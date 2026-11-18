package com.example.limbuszhcn.migration

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.limbuszhcn.ConfirmedOperation
import com.example.limbuszhcn.OperationConfirmation
import com.example.limbuszhcn.MainActivity
import com.example.limbuszhcn.container.*
import com.example.limbuszhcn.gamefs.GameResourceTransfer
import com.example.limbuszhcn.storage.OperationLock
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import io.github.gravere.bybus.migration.core.MigrationArchive
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

internal object MigrationAccess {
    private const val PREFS = "bybus-independent-migration"
    fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun sourceIsOurs(context: Context): Boolean = context.packageManager.checkSignatures(
        MigrationArchive.LEGACY, context.packageName) == PackageManager.SIGNATURE_MATCH
    fun ready(context: Context): Boolean = sourceIsOurs(context) &&
        context.packageManager.resolveContentProvider(MigrationArchive.AUTHORITY, 0)?.packageName == MigrationArchive.LEGACY
    fun shouldOffer(context: Context): Boolean = preferences(context).getBoolean("pending", false) ||
        !preferences(context).getBoolean("complete", false) && sourceIsOurs(context)
}

internal enum class MigrationOperation { Inspect, Legacy, Resources, Export }

internal data class MigrationState(val busy: Boolean = false, val copied: Long = 0, val total: Long = 0,
    val done: Boolean = false, val message: String = "正在读取旧版数据…", val ready: Boolean = false,
    val pending: Boolean = false, val hasLegacy: Boolean = false,
    val operation: MigrationOperation = MigrationOperation.Inspect, val failed: Boolean = false,
    val progressFiles: Boolean = false, val canExport: Boolean = false, val notice: String? = null) {
    fun shouldInspectOnEntry(restoring: Boolean) = done && !busy && !restoring
}

internal class MigrationModel private constructor(application: Application) {
    private val context = application.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var input: ParcelFileDescriptor? = null
    private val exportCancelled = java.util.concurrent.atomic.AtomicBoolean()
    private val exportState = ResourceExportState(context.filesDir.toPath().resolve("resource-exports"))
    private val runtime by lazy {
        AndroidContainerRuntimeFactory.create(context, context.filesDir.toPath().resolve("container-workspace"))
    }
    private fun resourceTransfer(version: Long = 1L, cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        progress: (com.example.limbuszhcn.gamefs.ResourceTransferProgress) -> Unit = {}): GameResourceTransfer {
        check(runtime.status().available) { "容器暂不可用，已保留导入恢复记录，请重新打开应用后检测" }
        return GameResourceTransfer(runtime.storage(), context.filesDir.toPath().resolve("resource-transfers"), version,
            cancelled = cancelled, progress = progress)
    }
    var state by mutableStateOf(MigrationState(pending = MigrationAccess.preferences(context).getBoolean("pending", false)))
        private set
    init { inspect() }
    private fun publish(value: MigrationState) { handler.post { state = value } }
    private fun inspect() {
        state = state.copy(busy = true, done = false, failed = false, operation = MigrationOperation.Inspect,
            message = "正在检查可迁移的数据…", copied = 0, total = 0, progressFiles = false, notice = null)
        executor.execute {
            runCatching {
                OperationLock.run(context.filesDir.toPath()) { cleanupExport() }
                if (MigrationAccess.preferences(context).getBoolean("resource_pending", false)) {
                    OperationLock.run(context.filesDir.toPath()) {
                        stopOwnedContainerProcesses()
                        resourceTransfer().recoverInterruptedImports()
                        clearResourcePending()
                    }
                }
                val canExport = runtime.status().let { it.available && (it.importedVersionCode ?: 0L) > 0 }
                val ours = MigrationAccess.sourceIsOurs(context)
                if (!ours || !MigrationAccess.ready(context)) {
                    val pending = MigrationAccess.preferences(context).getBoolean("pending", false)
                    publish(MigrationState(hasLegacy = ours, pending = pending, failed = pending, canExport = canExport,
                        message = if (pending && !ours) "上次迁移尚未完成，请恢复保留原数据的旧版后重新检测。暂不能改用资源文件继续。"
                            else if (ours) "请保留旧版，先在旧版检查更新并完成一次覆盖更新，然后回来重新检测。"
                            else "未检测到旧版，可选择已导出的资源文件。"))
                    return@execute
                }
                context.contentResolver.acquireContentProviderClient(MigrationArchive.AUTHORITY).use { client ->
                    val info = checkNotNull(client?.call("summary", null, null))
                    check(info.getInt("schema") == 1)
                    publish(MigrationState(ready = true, hasLegacy = true, total = info.getLong("bytes"), canExport = canExport,
                        pending = MigrationAccess.preferences(context).getBoolean("pending", false),
                        message = "将导入旧版的游戏资源与账号配置，替换新版中的同名数据。旧版数据仍保留，资源版本无需相同。"))
                }
            }.onFailure { publish(state.copy(busy = false, ready = false, failed = true,
                message = it.message ?: "暂时无法读取旧版数据")) }
        }
    }
    fun refresh() { if (!state.busy) inspect() }

    fun pickerFailed(error: Throwable) {
        state = state.copy(failed = true, message = error.message ?: "无法打开文件选择器")
    }

    private fun releaseExportGrant(uri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    private fun deleteExportDocument(uri: Uri) {
        try {
            check(android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                "未完成的资源包暂时无法清理，请连接原保存位置后重新检测"
            }
        } catch (_: java.io.FileNotFoundException) { /* Already removed by the user/provider. */ }
        releaseExportGrant(uri)
    }

    private fun cleanupExport() = exportState.recover { deleteExportDocument(Uri.parse(it)) }

    fun exportResources(uri: Uri) {
        if (state.busy) return
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        exportCancelled.set(false)
        state = state.copy(busy = true, done = false, failed = false, operation = MigrationOperation.Export,
            copied = 0, total = 0, progressFiles = false, notice = null, message = "正在准备迁出资源…")
        executor.execute {
            var writerEntered = false
            val result = runCatching {
                OperationLock.run(context.filesDir.toPath()) {
                    writerEntered = true
                    var recorded = false
                    try {
                        exportState.begin(uri.toString())
                        recorded = true
                        if (exportCancelled.get()) throw CancellationException("已取消迁出")
                        check(!MigrationAccess.preferences(context).getBoolean("pending", false)) { "请先完成上次迁入" }
                        val status = runtime.status()
                        val version = requireNotNull(status.importedVersionCode?.takeIf { it > 0 }) { "请先在 BYBUS 中下载游戏资源" }
                        check(status.available) { "容器暂不可用，请重新检测" }
                        stopOwnedContainerProcesses()
                        if (exportCancelled.get()) throw CancellationException("已取消迁出")
                        val descriptor = checkNotNull(context.contentResolver.openFileDescriptor(uri, "wt")) { "无法写入所选位置" }
                        input = descriptor
                        val copied = ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                            resourceTransfer(version, { exportCancelled.get() || Thread.currentThread().isInterrupted }) { update ->
                                publish(state.copy(copied = update.bytes, total = update.total, message = update.phase))
                            }.exportResources(output)
                        }
                        input = null
                        exportState.complete() // Success only after the ZIP and its output descriptor are closed.
                        releaseExportGrant(uri)
                        copied
                    } catch (error: Exception) {
                        runCatching { input?.close() }; input = null
                        // Cleanup shares the same lock as the writer; another migration window
                        // cannot mistake an in-progress output for an interrupted export.
                        try { if (recorded) cleanupExport() else deleteExportDocument(uri) }
                        catch (cleanup: Exception) { throw java.io.IOException(cleanup.message, error) }
                        // Closing a blocked provider stream for cancellation usually throws IOException.
                        // It is a normal cancellation only after cleanup has actually succeeded.
                        if (exportCancelled.get()) throw CancellationException("已取消迁出")
                        throw error
                    }
                }
            }
            result.onSuccess { copied ->
                publish(state.copy(busy = false, done = true, copied = copied.bytes, total = copied.bytes,
                    message = "资源包已保存"))
            }.onFailure { error ->
                if (!writerEntered) {
                    // The file picker already created an empty document, even if another
                    // process prevented this worker from acquiring the operation lock.
                    val cleanup = runCatching { deleteExportDocument(uri) }.exceptionOrNull()
                    if (cleanup != null) {
                        publish(state.copy(busy = false, failed = true,
                            message = "迁出未开始，空文件未能删除，请到保存位置手动删除：${cleanup.message ?: "无法访问文件"}"))
                        return@onFailure
                    }
                }
                val cancelled = exportCancelled.get() && error is CancellationException
                publish(state.copy(busy = false, failed = !cancelled,
                    notice = if (cancelled) "已取消迁出，本机数据保留。" else null,
                    message = error.message ?: "迁出未完成，请重试"))
            }
        }
    }

    fun cancelExport() {
        if (state.busy && state.operation == MigrationOperation.Export) {
            exportCancelled.set(true)
            runCatching { input?.close() }
            state = state.copy(message = "正在取消迁出…")
        }
    }
    fun start(prepareTarget: Boolean) {
        if (state.busy || !state.ready) return
        state = state.copy(busy = true, done = false, failed = false, copied = 0,
            operation = MigrationOperation.Legacy, progressFiles = false, message = "正在准备游戏…")
        executor.execute {
            runCatching {
                OperationLock.run(context.filesDir.toPath()) {
                    check(MigrationAccess.ready(context)) { "旧版迁移接口已不可用，请保留旧版并重新检测" }
                    val root = File(context.applicationInfo.dataDir).toPath()
                    val needed = context.contentResolver.acquireContentProviderClient(MigrationArchive.AUTHORITY).use { client ->
                        val descriptor = checkNotNull(client?.openFile(Uri.parse("content://${MigrationArchive.AUTHORITY}/inventory"), "r", null))
                        input = descriptor
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { MigrationArchive.requiredSpace(root, it) }
                            .also { input = null }
                    } + 512L * 1024 * 1024
                    check(StatFs(root.toString()).availableBytes >= needed) { "剩余空间不足。请先释放空间；旧数据未改动。" }
                    if (prepareTarget) prepareGame()
                    stopOwnedContainerProcesses()
                    check(MigrationAccess.preferences(context).edit().putBoolean("pending", true).commit()) {
                        "无法保存迁移状态，请释放空间后重试"
                    }
                    context.contentResolver.acquireContentProviderClient(MigrationArchive.AUTHORITY).use { client ->
                        checkNotNull(client)
                        val descriptor = checkNotNull(client.openFile(Uri.parse("content://${MigrationArchive.AUTHORITY}/archive"), "r", null))
                        input = descriptor
                        var lastProgress = 0L
                        val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                            MigrationArchive.read(root, stream) { copied, total ->
                                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("迁移已中断，可重新导入")
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastProgress >= 100 || copied == total) {
                                    lastProgress = now
                                    publish(MigrationState(true, copied, total, message = "正在复制数据", ready = true,
                                        hasLegacy = true, pending = true, operation = MigrationOperation.Legacy))
                                }
                            }
                        }
                        input = null
                        // Restart only our container processes so account services reload the committed files.
                        stopOwnedContainerProcesses()
                        TranslationRedirectGate(context).close()
                        invalidateResourceIndex()
                        check(MigrationAccess.preferences(context).edit().putBoolean("complete", true).putBoolean("pending", false).commit())
                        // The local commit is authoritative. A remote UI acknowledgement must not
                        // turn a completed import into a failed one or require copying it again.
                        runCatching { client.call("complete", null, null) }
                        publish(MigrationState(copied = bytes, total = bytes, done = true, ready = true,
                            hasLegacy = true, operation = MigrationOperation.Legacy,
                            message = "数据已导入。确认新版运行正常后再卸载旧版。游戏会校验并补齐资源；Google 账号可能需要重新授权。"))
                    }
                }
            }.onFailure {
                runCatching { input?.close() }; input = null
                publish(state.copy(busy = false, failed = true, pending = MigrationAccess.preferences(context).getBoolean("pending", false),
                    message = it.message ?: "迁移中断，可重新导入；旧数据仍保留"))
            }
        }
    }

    private fun prepareGame() {
        val installed = AndroidInstalledGameImporter(context.packageManager,
            File(context.filesDir, "container-imports")).copyInstalledGame()
        runtime.importGame(GameImportRequest(installed.splitApkPaths, installed.versionCode, "installed"))
    }

    private fun clearResourcePending() {
        invalidateResourceIndex()
        check(MigrationAccess.preferences(context).edit().remove("resource_pending").putBoolean("pending", false).commit()) {
            "无法保存导入状态，请释放空间后重新检测"
        }
    }

    private fun invalidateResourceIndex() =
        com.example.limbuszhcn.translation.CoverageStore(context.filesDir.toPath().resolve("translation-cache")).resourcesReplaced()

    fun importResources(uri: Uri, prepareTarget: Boolean) {
        if (state.busy || state.pending) return
        state = state.copy(busy = true, done = false, failed = false, copied = 0, total = 0,
            operation = MigrationOperation.Resources, progressFiles = false, message = "正在准备导入资源…")
        executor.execute {
            runCatching {
                OperationLock.run(context.filesDir.toPath()) {
                    check(!MigrationAccess.preferences(context).getBoolean("pending", false)) { "请先完成上次迁移" }
                    try {
                        if (prepareTarget) prepareGame()
                        stopOwnedContainerProcesses()
                        check(MigrationAccess.preferences(context).edit().putBoolean("resource_pending", true)
                            .putBoolean("pending", true).commit()) { "无法保存导入状态，请释放空间后重试" }
                        val descriptor = checkNotNull(context.contentResolver.openFileDescriptor(uri, "r")) { "无法读取所选资源包" }
                        input = descriptor
                        val result = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                            resourceTransfer { update ->
                                publish(state.copy(busy = true, pending = true, copied = update.bytes, total = update.total,
                                    progressFiles = update.phase == "正在合并资源", message = update.phase))
                            }.importResources(stream)
                        }
                        input = null
                        TranslationRedirectGate(context).close()
                        clearResourcePending()
                        publish(MigrationState(done = true, copied = result.bytes, total = result.bytes,
                            operation = MigrationOperation.Resources,
                            message = "已导入 ${result.files} 个资源文件，保留 ${result.keptExisting} 个已有文件。进入游戏后登录并补齐版本差异；原资源包未改动。"))
                    } catch (error: Throwable) {
                        runCatching { input?.close() }; input = null
                        if (MigrationAccess.preferences(context).getBoolean("resource_pending", false)) {
                            // Recover before releasing the launch lock. If recovery fails, keep the
                            // durable guard so a later visit must recover before launching the game.
                            runCatching { resourceTransfer().recoverInterruptedImports(); clearResourcePending() }
                        }
                        throw error
                    }
                }
            }.onFailure {
                publish(state.copy(busy = false, failed = true, pending = MigrationAccess.preferences(context).getBoolean("pending", false),
                    message = it.message ?: "资源导入中断，请重新检测后重试"))
            }
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
    private fun stopOwnedContainerProcesses() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val current = Process.myPid()
        val targets = manager.runningAppProcesses.orEmpty().filter {
            it.uid == Process.myUid() && it.pid != current &&
                it.processName.startsWith(context.packageName + ":")
        }.map { it.pid }.toSet()
        targets.forEach(Process::killProcess)
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (manager.runningAppProcesses.orEmpty().any { it.uid == Process.myUid() && it.pid in targets }) {
            check(SystemClock.elapsedRealtime() < deadline) { "容器仍在退出，请稍后重试迁移" }
            Thread.sleep(100)
        }
    }
    companion object {
        private var instance by mutableStateOf<MigrationModel?>(null)
        fun get(application: Application): MigrationModel = instance ?: MigrationModel(application).also { instance = it }
        fun currentState(): MigrationState? = instance?.state
    }
}

open class MigrationActivity : ComponentActivity() {
    protected open fun prepareTarget(): Boolean = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val model = MigrationModel.get(application)
        if (model.state.shouldInspectOnEntry(restoring = savedInstanceState != null)) model.refresh()
        setContent {
            LimbusZhCNTheme {
                val state = model.state
                var confirmation by rememberSaveable { mutableStateOf<ConfirmedOperation?>(null) }
                var selectedResource by rememberSaveable { mutableStateOf<String?>(null) }
                var exportDestination by rememberSaveable { mutableStateOf<String?>(null) }
                val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                    exportDestination = uri?.toString()
                }
                LaunchedEffect(exportDestination, state.busy) {
                    val destination = exportDestination
                    if (destination != null && !state.busy) {
                        exportDestination = null
                        model.exportResources(Uri.parse(destination))
                    }
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        selectedResource = uri.toString()
                        confirmation = ConfirmedOperation.ImportResources
                    }
                }
                BackHandler { leaveMigration() }
                MigrationScreen(state = state, onBack = ::leaveMigration, onRefresh = model::refresh,
                    onMigrate = { confirmation = ConfirmedOperation.Migrate },
                    onSelectFile = { runCatching { picker.launch(arrayOf("application/zip", "application/octet-stream")) }.onFailure(model::pickerFailed) },
                    onOpenLegacy = {
                        packageManager.getLaunchIntentForPackage(MigrationArchive.LEGACY)?.let(::startActivity)
                    }, onFinish = ::leaveMigration,
                    onExport = { confirmation = ConfirmedOperation.ExportResources }, onCancelExport = model::cancelExport)
                confirmation?.let { operation ->
                    OperationConfirmation(operation, enabled = !state.busy,
                        onDismiss = { confirmation = null; selectedResource = null }) {
                        val resource = selectedResource
                        confirmation = null
                        selectedResource = null
                        if (!state.busy) when (operation) {
                            ConfirmedOperation.Migrate -> model.start(prepareTarget())
                            ConfirmedOperation.ExportResources -> runCatching { exportPicker.launch("BYBUS-resources.zip") }.onFailure(model::pickerFailed)
                            ConfirmedOperation.ImportResources -> resource?.let {
                                val uri = Uri.parse(it)
                                // Selecting a document is not consent to retain access after
                                // cancelling the confirmation. The worker releases this grant.
                                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                model.importResources(uri, prepareTarget())
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
    private fun leaveMigration() {
        if (isTaskRoot) openMain() else finish()
    }
    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).putExtra("skip_legacy_migration", true))
        finish()
    }
}
