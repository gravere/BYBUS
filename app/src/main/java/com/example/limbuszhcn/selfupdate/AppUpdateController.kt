package com.example.limbuszhcn.selfupdate

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

internal data class AppUpdateState(val installedCode: Long, val busy: Boolean = false,
    val checked: Boolean = false, val release: AppRelease? = null, val progress: Int? = null,
    val ready: Boolean = false, val message: String = "检查 BYBUS 的新版本", val error: String? = null,
    val delta: AppDelta? = null, val retryAtElapsed: Long? = null)

/** A separate worker: app update requests never block the game preparation queue. */
internal class AppUpdateController(private val app: Application) {
    private val repository = AppUpdateRepository(File(app.cacheDir, "app-update"))
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "bybus-app-update") }
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val preferences = app.getSharedPreferences("bybus-app-update", 0)
    private val checkPolicy = AppUpdateCheckPolicy(SystemClock::elapsedRealtime, System::currentTimeMillis,
        if (preferences.getInt("retrySchema", 0) == 2)
            UpdateRateLimitWindow(preferences.getLong("rateRecordedAt", 0), preferences.getLong("rateRetryAt", 0)) else null
    ) { saved ->
        preferences.edit().remove("retryAt").apply {
            if (saved == null) remove("retrySchema").remove("rateRecordedAt").remove("rateRetryAt")
            else putInt("retrySchema", 2).putLong("rateRecordedAt", saved.recordedAt).putLong("rateRetryAt", saved.retryAt)
        }.apply()
    }
    private var task: Future<*>? = null
    private var readyFile: File? = null
    private var closed = false
    var state by mutableStateOf(AppUpdateState(AppUpdatePackage.installedCode(app)))
        private set
    var installRequest by mutableStateOf<File?>(null)
        private set

    init { preferences.edit().remove("retryAt").apply() }

    fun check(force: Boolean = false) {
        if (closed || state.busy) return
        when (checkPolicy.beforeCheck(force)) {
            UpdateCheckDecision.Cached -> return
            UpdateCheckDecision.Wait -> { showServerWait(); return }
            UpdateCheckDecision.Check -> Unit
        }
        readyFile = null
        installRequest = null
        val id = generation.incrementAndGet()
        state = state.copy(busy = true, ready = false, progress = null, message = "正在检查版本", error = null, retryAtElapsed = null)
        task = worker.submit {
            try {
                val release = repository.check()
                val installed = AppUpdatePackage.installedCode(app)
                if (!release.newerThan(installed)) repository.clear()
                val delta = if (release.newerThan(installed))
                    repository.selectDelta(release, installed, File(app.applicationInfo.sourceDir)) else null
                publish(id) {
                    checkPolicy.succeeded()
                    state = AppUpdateState(installed, checked = true, release = release, delta = delta,
                        message = if (release.newerThan(installed)) "有新版本可用" else "已是最新版本")
                }
            } catch (error: Exception) {
                publish(id) { failed(error) }
            }
        }
    }

    fun download() {
        val release = state.release ?: return
        if (closed || state.busy || !release.newerThan(state.installedCode)) return
        if (checkPolicy.beforeCheck(force = true) == UpdateCheckDecision.Wait) { showServerWait(); return }
        if (release.minSdk > android.os.Build.VERSION.SDK_INT) {
            state = state.copy(error = "此版本需要 Android API ${release.minSdk} 或更高版本")
            return
        }
        val id = generation.incrementAndGet()
        val delta = state.delta
        state = state.copy(busy = true, ready = false, progress = null, message = "正在准备更新", error = null, retryAtElapsed = null)
        task = worker.submit {
            try {
                val file = repository.download(release, File(app.applicationInfo.sourceDir), delta) { transfer ->
                    publish(id) { state = state.copy(progress = transfer.percent, message = transfer.message, delta = transfer.delta) }
                }
                publish(id) { state = state.copy(progress = null, message = "正在校验安装包") }
                AppUpdatePackage.verify(app, file, release)
                publish(id) { readyFile = file; state = state.copy(busy = false, ready = true, message = "已准备好安装") }
            } catch (error: Exception) { publish(id) { failed(error) } }
        }
    }

    fun install() {
        val file = readyFile ?: return
        val release = state.release ?: return
        if (closed || state.busy) return
        val id = generation.incrementAndGet()
        state = state.copy(busy = true, message = "正在准备安装", error = null)
        task = worker.submit {
            try {
                AppUpdatePackage.verify(app, file, release)
                publish(id) { state = state.copy(busy = false, message = "请在系统页面确认安装"); installRequest = file }
            } catch (error: Exception) { publish(id) { readyFile = null; state = state.copy(ready = false); failed(error) } }
        }
    }

    fun installationOpened() { installRequest = null }
    fun installationReturned() {
        if (state.ready) state = state.copy(message = "安装包已保留，可继续安装")
    }
    fun installationError() {
        state = state.copy(error = "暂时无法打开系统安装程序，可在 GitHub 发行页面下载")
    }
    fun installationPermissionNeeded() {
        state = state.copy(message = "允许安装后，返回此页点击「安装更新」")
    }
    fun cancel() {
        generation.incrementAndGet()
        task?.cancel(true)
        state = state.copy(busy = false, progress = null, message = "已取消，可重试", error = null)
    }
    private fun publish(id: Long, action: () -> Unit) {
        main.post { if (!closed && id == generation.get()) action() }
    }
    private fun failed(error: Exception) {
        if (error is java.util.concurrent.CancellationException) {
            state = state.copy(busy = false, progress = null, message = "已取消，可重试", error = null)
            return
        }
        checkPolicy.failed(error)
        val message = when (error) {
            is java.net.SocketTimeoutException -> "连接超时，请重试或查看 GitHub 发行页面"
            is java.net.UnknownHostException -> "暂时无法连接 GitHub，请检查网络后重试"
            is IllegalArgumentException, is IllegalStateException, is UpdateHttpException -> error.message ?: "更新检查未完成"
            else -> "更新暂未完成，请重试或查看 GitHub 发行页面"
        }
        state = state.copy(busy = false, progress = null, error = message, message = "更新未完成",
            retryAtElapsed = checkPolicy.retryAtElapsed)
    }
    private fun showServerWait() {
        state = state.copy(error = "GitHub 暂时限制了请求", message = "等待 GitHub 恢复请求", retryAtElapsed = checkPolicy.retryAtElapsed)
    }
    fun close() { closed = true; generation.incrementAndGet(); task?.cancel(true); worker.shutdownNow() }
}
