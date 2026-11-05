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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.limbuszhcn.MainActivity
import com.example.limbuszhcn.container.*
import com.example.limbuszhcn.gamefs.GameResourceTransfer
import com.example.limbuszhcn.storage.OperationLock
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import io.github.gravere.bybus.migration.core.MigrationArchive
import java.io.File
import java.io.InterruptedIOException
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

internal data class MigrationState(val busy: Boolean = false, val copied: Long = 0, val total: Long = 0,
    val done: Boolean = false, val message: String = "正在读取旧版数据…", val ready: Boolean = false,
    val pending: Boolean = false, val hasLegacy: Boolean = false)

internal class MigrationModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var input: ParcelFileDescriptor? = null
    private val runtime by lazy {
        AndroidContainerRuntimeFactory.create(context, context.filesDir.toPath().resolve("container-workspace"))
    }
    private fun resourceTransfer(progress: (com.example.limbuszhcn.gamefs.ResourceTransferProgress) -> Unit = {}) =
        GameResourceTransfer(runtime.storage(), context.filesDir.toPath().resolve("resource-transfers"), 1L,
            cancelled = { Thread.currentThread().isInterrupted }, progress = progress)
    var state by mutableStateOf(MigrationState(pending = MigrationAccess.preferences(context).getBoolean("pending", false)))
        private set
    init { inspect() }
    private fun publish(value: MigrationState) { handler.post { state = value } }
    private fun inspect() {
        state = state.copy(busy = true)
        executor.execute {
            runCatching {
                if (MigrationAccess.preferences(context).getBoolean("resource_pending", false)) {
                    OperationLock.run(context.filesDir.toPath()) {
                        stopOwnedContainerProcesses()
                        resourceTransfer().recoverInterruptedImports()
                        clearResourcePending()
                    }
                }
                val ours = MigrationAccess.sourceIsOurs(context)
                if (!ours || !MigrationAccess.ready(context)) {
                    publish(MigrationState(hasLegacy = ours,
                        pending = MigrationAccess.preferences(context).getBoolean("pending", false),
                        message = if (ours) "请保留旧版，先在旧版检查更新并完成一次覆盖更新，然后回来重新检测。"
                            else "可从旧 BYBUS 直接迁移，或选择其他兼容容器导出的资源包。"))
                    return@execute
                }
                context.contentResolver.acquireContentProviderClient(MigrationArchive.AUTHORITY).use { client ->
                    val info = checkNotNull(client?.call("summary", null, null))
                    check(info.getInt("schema") == 1)
                    publish(MigrationState(ready = true, hasLegacy = true, total = info.getLong("bytes"),
                        pending = MigrationAccess.preferences(context).getBoolean("pending", false),
                        message = "将导入旧版的游戏资源与账号配置，替换新版中的同名数据。旧版数据仍保留，资源版本无需相同。"))
                }
            }.onFailure { publish(state.copy(busy = false, ready = false, message = it.message ?: "暂时无法读取旧版数据")) }
        }
    }
    fun refresh() { if (!state.busy) inspect() }
    fun start(prepareTarget: Boolean) {
        if (state.busy || !state.ready) return
        state = state.copy(busy = true, done = false, message = "正在准备游戏…")
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
                                    publish(MigrationState(true, copied, total, message = "正在导入，请保持此页面打开", ready = true, pending = true))
                                }
                            }
                        }
                        input = null
                        // Restart only our container processes so account services reload the committed files.
                        stopOwnedContainerProcesses()
                        TranslationRedirectGate(context).close()
                        check(MigrationAccess.preferences(context).edit().putBoolean("complete", true).putBoolean("pending", false).commit())
                        // The local commit is authoritative. A remote UI acknowledgement must not
                        // turn a completed import into a failed one or require copying it again.
                        runCatching { client.call("complete", null, null) }
                        publish(MigrationState(copied = bytes, total = bytes, done = true, ready = true,
                            message = "数据已导入。确认新版运行正常后再卸载旧版。游戏会校验并补齐资源；Google 账号可能需要重新授权。"))
                    }
                }
            }.onFailure {
                runCatching { input?.close() }; input = null
                publish(state.copy(busy = false, pending = MigrationAccess.preferences(context).getBoolean("pending", false),
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
        check(MigrationAccess.preferences(context).edit().remove("resource_pending").putBoolean("pending", false).commit()) {
            "无法保存导入状态，请释放空间后重新检测"
        }
    }

    fun importResources(uri: Uri, prepareTarget: Boolean) {
        if (state.busy || state.pending) return
        state = state.copy(busy = true, done = false, copied = 0, total = 0, message = "正在准备导入资源…")
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
                                    message = if (update.phase == "正在合并资源") "正在合并资源 · ${update.bytes}/${update.total} 个文件"
                                        else update.phase))
                            }.importResources(stream)
                        }
                        input = null
                        TranslationRedirectGate(context).close()
                        clearResourcePending()
                        publish(MigrationState(done = true, copied = result.bytes, total = result.bytes,
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
                publish(state.copy(busy = false, pending = MigrationAccess.preferences(context).getBoolean("pending", false),
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
    override fun onCleared() { runCatching { input?.close() }; executor.shutdownNow() }
}

open class MigrationActivity : ComponentActivity() {
    protected open fun prepareTarget(): Boolean = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val model = ViewModelProvider(this)[MigrationModel::class.java]
        setContent {
            LimbusZhCNTheme {
                val state = model.state
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        model.importResources(uri, prepareTarget())
                    }
                }
                BackHandler(state.busy || state.pending) { }
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            Text("数据迁移", style = MaterialTheme.typography.headlineMedium)
                            Text(if (state.done) "数据已就位" else "把已有资源带过来", style = MaterialTheme.typography.titleMedium)
                            Card(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(state.message, style = MaterialTheme.typography.bodyLarge)
                                    if (state.total > 0 && !state.message.startsWith("正在合并资源")) Text("${android.text.format.Formatter.formatFileSize(this@MigrationActivity, state.copied)} / " +
                                        android.text.format.Formatter.formatFileSize(this@MigrationActivity, state.total),
                                        style = MaterialTheme.typography.labelLarge)
                                    if (state.busy && state.copied == 0L) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    else if (state.total > 0 && (state.busy || state.done)) LinearProgressIndicator(
                                        progress = (state.copied.toDouble()/state.total).toFloat().coerceIn(0f,1f), modifier = Modifier.fillMaxWidth())
                                }
                            }
                            if (state.done) Button(onClick = ::openMain, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("进入 BYBUS") }
                            else if (state.ready) Button(onClick = { model.start(prepareTarget()) }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (state.busy) "正在迁移" else "一键迁移旧 BYBUS") }
                            else if (!state.busy && state.hasLegacy) {
                                Button(onClick = {
                                    packageManager.getLaunchIntentForPackage(MigrationArchive.LEGACY)?.let(::startActivity)
                                }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("打开旧版 BYBUS") }
                            }
                            if (!state.busy && !state.done) {
                                TextButton(onClick = model::refresh) { Text("重新检测") }
                                if (!state.pending) {
                                    OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("导入资源包") }
                                    Text("支持 limbusZhCN 及兼容容器迁出的资源 ZIP。只补充资源，不导入账号或替换已有文件；版本不同也可使用。",
                                        style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = ::openMain) { Text("暂不导入") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).putExtra("skip_legacy_migration", true))
        finish()
    }
}
