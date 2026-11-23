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
    val delta: AppDelta? = null, val retryAtElapsed: Long? = null, val reuse: AppReusePlan? = null,
    val knownVersionCode: Long = 0, val downloadPlanned: Boolean = false, val checking: Boolean = false,
    val checkFailed: Boolean = false, val automaticUpdates: Boolean = false) {
    val updateAvailable: Boolean get() = maxOf(knownVersionCode, release?.versionCode ?: 0) > installedCode

    fun withPublishedVersion(code: Long): AppUpdateState {
        if (checkFailed) return copy(error = null, checkFailed = false, retryAtElapsed = null,
            message = if (code > installedCode) "有新版本可用" else "已是最新版本").withPublishedVersion(code)
        if (release?.versionCode == code) return copy(knownVersionCode = code)
        val withdrawn = ready && release != null && release.versionCode > code
        return copy(knownVersionCode = code, release = null, checked = false, ready = false,
            progress = null, delta = null, reuse = null, downloadPlanned = false,
            error = if (withdrawn) null else error, message = when {
                withdrawn -> "已下载的版本已撤回"
                error != null -> message
                code > installedCode -> "有新版本可用"
                else -> "已是最新版本"
            })
    }
}

/** A separate worker: app update requests never block the game preparation queue. */
internal class AppUpdateController(private val app: Application) {
    private val repository = AppUpdateRepository(File(app.cacheDir, "app-update"))
    private val installer = AppUpdateInstaller(app)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "bybus-app-update") }
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val preferences = app.getSharedPreferences("bybus-app-update", 0)
    private var notice = AppUpdateNotice(preferences.getLong("noticeVersion", 0),
        preferences.getLong("noticeCheckedAt", 0), preferences.getLong("noticeAttemptedAt", 0))
    private val checkPolicy = AppUpdateCheckPolicy(SystemClock::elapsedRealtime, System::currentTimeMillis,
        if (preferences.getInt("retrySchema", 0) == 3)
            UpdateRateLimitWindow(preferences.getLong("rateRecordedAt", 0), preferences.getLong("rateRetryAt", 0)) else null
    ) { saved ->
        preferences.edit().remove("retryAt").apply {
            if (saved == null) remove("retrySchema").remove("rateRecordedAt").remove("rateRetryAt")
            else putInt("retrySchema", 3).putLong("rateRecordedAt", saved.recordedAt).putLong("rateRetryAt", saved.retryAt)
        }.apply()
    }
    private var task: Future<*>? = null
    private var noticeTask: Future<*>? = null
    private var checkingNotice = false
    private var installAfterNotice = false
    private val noticeSession = AppUpdateNoticeSession()
    private val automaticPolicy = AutomaticUpdatePolicy()
    private var automaticEligible = false
    private var automaticOperation = false
    private var automaticFailure = false
    private var automaticNoticeVerified = false
    var openingGeneration by mutableStateOf(0L)
        private set
    private var readyFile: File? = null
    private var closed = false
    var state by mutableStateOf(AppUpdateState(AppUpdatePackage.installedCode(app), knownVersionCode = notice.versionCode,
        automaticUpdates = preferences.getBoolean("automaticUpdates", false)))
        private set
    var installRequest by mutableStateOf<File?>(null)
        private set

    init {
        preferences.edit().remove("retryAt").apply {
            if (preferences.getInt("retrySchema", 0) != 3)
                remove("retrySchema").remove("rateRecordedAt").remove("rateRetryAt")
        }.apply()
        worker.submit { installer.observe() }
    }

    fun homeFrameCommitted() {
        if (!closed) worker.submit { installer.observe(homeFrame = true) }
    }

    fun installerIntent(file: File): android.content.Intent = installer.intent(file)

    fun onAppOpened() {
        noticeSession.opened()
        automaticNoticeVerified = false
        automaticPolicy.opened()
        if (automaticFailure) {
            automaticFailure = false
            state = state.copy(error = null)
        }
        openingGeneration++
    }

    fun setAutomaticUpdates(enabled: Boolean) {
        preferences.edit().putBoolean("automaticUpdates", enabled).apply()
        state = state.copy(automaticUpdates = enabled)
        automaticPolicy.opened()
        if (!enabled) deferAutomaticUpdate() else {
            if (automaticFailure) { automaticFailure = false; state = state.copy(error = null) }
            noticeSession.opened()
            automaticNoticeVerified = false
            openingGeneration++
            checkForNotice()
            maybeAutomaticUpdate()
        }
    }

    fun updateAutomaticContext(eligible: Boolean) {
        automaticEligible = eligible
        if (!eligible) deferAutomaticUpdate() else maybeAutomaticUpdate()
    }

    private fun deferAutomaticUpdate() {
        if (!automaticOperation || !state.busy) return
        generation.incrementAndGet()
        task?.cancel(true)
        automaticOperation = false
        automaticPolicy.defer()
        if (state.checking) {
            noticeSession.opened()
            openingGeneration++
        }
        state = state.copy(busy = false, checking = false, progress = null, error = null,
            message = "自动下载已暂停，可手动下载")
    }

    private fun maybeAutomaticUpdate() {
        if (closed || noticeSession.shouldCheck(notice, System.currentTimeMillis())) return
        when (automaticPolicy.next(state.automaticUpdates, automaticEligible, state.installedCode,
            state.knownVersionCode, state.release?.versionCode,
            state.busy || checkingNotice || installRequest != null, state.ready, state.error != null,
            automaticNoticeVerified)) {
            // A newer publication invalidates the metadata reused by the manual check window.
            AutomaticUpdatePolicy.Action.Check -> startCheck(force = true, automatic = true)
            AutomaticUpdatePolicy.Action.Download -> startDownload(automatic = true)
            AutomaticUpdatePolicy.Action.None -> Unit
        }
    }

    fun nextNoticeDelay(): Long = maxOf(noticeSession.nextDelay(notice, System.currentTimeMillis()),
        checkPolicy.retryAtElapsed?.let { it - SystemClock.elapsedRealtime() } ?: 0,
        if (state.busy || checkingNotice || installRequest != null) 60_000L else 0)

    fun checkForNotice() {
        if (closed || checkingNotice || state.busy || installRequest != null ||
            !noticeSession.shouldCheck(notice, System.currentTimeMillis())) return
        if (checkPolicy.beforeCheck(force = true) == UpdateCheckDecision.Wait) return
        noticeSession.attempted()
        saveNotice(notice.attempted(System.currentTimeMillis()))
        checkingNotice = true
        val id = generation.get()
        noticeTask = worker.submit {
            val result = try { Result.success(repository.checkNotice()) } catch (error: Exception) { Result.failure(error) }
            main.post {
                checkingNotice = false
                val installRequested = installAfterNotice
                installAfterNotice = false
                // A user request started meanwhile owns the state. Never replace its progress or result.
                if (closed || id != generation.get() || state.busy) return@post
                result.fold(onSuccess = { code ->
                    automaticNoticeVerified = true
                    checkPolicy.succeeded()
                    saveNotice(notice.checked(code, System.currentTimeMillis()))
                    android.util.Log.i("BYBUSUpdate", "Notice checked: $code")
                    state = state.withPublishedVersion(code)
                    if (!state.ready) readyFile = null
                }, onFailure = {
                    automaticNoticeVerified = false
                    if (it is Exception) checkPolicy.failed(it)
                })
                if (installRequested && state.ready) install()
                else maybeAutomaticUpdate()
            }
        }
    }

    private fun saveNotice(value: AppUpdateNotice) {
        notice = value
        preferences.edit().putLong("noticeVersion", value.versionCode).putLong("noticeCheckedAt", value.checkedAt)
            .putLong("noticeAttemptedAt", value.attemptedAt).apply()
    }

    fun check(force: Boolean = false) = startCheck(force, automatic = false)

    private fun startCheck(force: Boolean, automatic: Boolean) {
        if (closed || state.busy) return
        when (checkPolicy.beforeCheck(force)) {
            UpdateCheckDecision.Cached -> if (state.release != null) return
            UpdateCheckDecision.Wait -> { showServerWait(); return }
            UpdateCheckDecision.Check -> Unit
        }
        readyFile = null
        installRequest = null
        noticeSession.attempted()
        saveNotice(notice.attempted(System.currentTimeMillis()))
        val id = generation.incrementAndGet()
        automaticOperation = automatic
        automaticFailure = false
        automaticNoticeVerified = false
        state = state.copy(busy = true, checking = true, ready = false, progress = null, message = "正在检查版本", error = null, retryAtElapsed = null)
        task = worker.submit {
            try {
                val release = repository.check(force)
                val installed = AppUpdatePackage.installedCode(app)
                if (!release.newerThan(installed)) repository.clear()
                val reuse = if (release.newerThan(installed))
                    repository.selectReuse(release, File(app.applicationInfo.sourceDir)) else null
                val delta = if (release.newerThan(installed) && reuse == null)
                    repository.selectDelta(release, installed, File(app.applicationInfo.sourceDir)) else null
                publish(id) {
                    automaticNoticeVerified = true
                    checkPolicy.succeeded()
                    saveNotice(notice.checked(release.versionCode, System.currentTimeMillis()))
                    state = AppUpdateState(installed, checked = true, release = release, delta = delta, reuse = reuse,
                        knownVersionCode = notice.versionCode, downloadPlanned = true,
                        automaticUpdates = state.automaticUpdates,
                        message = if (release.newerThan(installed)) "有新版本可用" else "已是最新版本")
                    automaticOperation = false
                    maybeAutomaticUpdate()
                }
            } catch (error: Exception) {
                publish(id) { failed(error) }
            }
        }
    }

    fun download() = startDownload(automatic = false)

    private fun startDownload(automatic: Boolean) {
        val release = state.release ?: return
        if (closed || state.busy || !release.newerThan(state.installedCode)) return
        if (checkPolicy.beforeCheck(force = true) == UpdateCheckDecision.Wait) { showServerWait(); return }
        if (release.minSdk > android.os.Build.VERSION.SDK_INT) {
            state = state.copy(error = "此版本需要 Android API ${release.minSdk} 或更高版本")
            return
        }
        val id = generation.incrementAndGet()
        automaticOperation = automatic
        automaticFailure = false
        val planned = state.downloadPlanned
        val installedCode = state.installedCode
        val selectedDelta = state.delta
        val selectedReuse = state.reuse
        state = state.copy(busy = true, checking = false, ready = false, progress = null, message = "正在准备更新", error = null, retryAtElapsed = null)
        task = worker.submit {
            try {
                val source = File(app.applicationInfo.sourceDir)
                val reuse = if (planned) selectedReuse else repository.selectReuse(release, source)
                val delta = if (planned) selectedDelta else if (reuse == null)
                    repository.selectDelta(release, installedCode, source) else null
                publish(id) { state = state.copy(reuse = reuse, delta = delta, downloadPlanned = true) }
                val file = repository.download(release, source, delta, reuse) { transfer ->
                    publish(id) { state = state.copy(progress = transfer.percent, message = transfer.message, delta = transfer.delta, reuse = transfer.reuse) }
                }
                publish(id) { state = state.copy(progress = null, message = "正在校验安装包") }
                AppUpdatePackage.verify(app, file, release)
                publish(id) {
                    automaticOperation = false
                    readyFile = file
                    state = state.copy(busy = false, ready = true, message = "已下载，等待安装")
                }
            } catch (error: Exception) { publish(id) { failed(error) } }
        }
    }

    fun install() {
        val file = readyFile ?: return
        val release = state.release ?: return
        if (closed || state.busy) return
        if (checkingNotice) {
            installAfterNotice = true
            return
        }
        val id = generation.incrementAndGet()
        state = state.copy(busy = true, checking = false, message = "正在准备安装", error = null)
        task = worker.submit {
            try {
                installer.prepare(file, release)
                publish(id) { state = state.copy(busy = false, message = "请在系统页面确认安装"); installRequest = file }
            } catch (error: Exception) { publish(id) { readyFile = null; state = state.copy(ready = false); failed(error) } }
        }
    }

    fun installationOpened() { installRequest = null }
    fun resumeInstallation() {
        val installed = installer.onHostResumed() ?: return
        val target = state.release?.versionCode
        if (target != null && installed >= target) {
            readyFile = null
            installRequest = null
            state = state.copy(installedCode = installed, busy = false, ready = false, progress = null,
                error = null, message = "已完成更新")
        } else if (state.ready) state = state.copy(message = "尚未完成更新，安装包已保留")
    }
    fun installationError() {
        installer.failed()
        state = state.copy(error = "暂时无法打开系统安装程序，可在 GitHub 发行页面下载")
    }
    fun releasePageError() {
        state = state.copy(error = "暂时无法打开浏览器，请安装或启用浏览器后重试")
    }
    fun installationPermissionNeeded() {
        state = state.copy(message = "允许安装后，返回此页点击「安装更新」")
    }
    fun cancel() {
        automaticPolicy.pause()
        automaticOperation = false
        installAfterNotice = false
        generation.incrementAndGet()
        task?.cancel(true)
        state = state.copy(busy = false, checking = false, progress = null, message = "已取消，可重试", error = null)
    }
    private fun publish(id: Long, action: () -> Unit) {
        main.post { if (!closed && id == generation.get()) action() }
    }
    private fun failed(error: Exception) {
        automaticFailure = automaticOperation
        automaticOperation = false
        if (error is java.util.concurrent.CancellationException) {
            state = state.copy(busy = false, checking = false, progress = null, message = "已取消，可重试", error = null)
            return
        }
        checkPolicy.failed(error)
        val message = when (error) {
            is AppUpdateStorageException -> error.message!!
            is java.net.SocketTimeoutException -> "连接超时，请重试或查看 GitHub 发行页面"
            is java.net.UnknownHostException -> "暂时无法连接 GitHub，请检查网络后重试"
            is IllegalArgumentException, is IllegalStateException, is UpdateHttpException -> error.message ?: "更新检查未完成"
            else -> "更新暂未完成，请重试或查看 GitHub 发行页面"
        }
        state = state.copy(busy = false, checking = false, checkFailed = state.checking, progress = null, error = message, message = "更新未完成",
            retryAtElapsed = checkPolicy.retryAtElapsed)
    }
    private fun showServerWait() {
        state = state.copy(error = "GitHub 暂时限制了请求", message = "等待 GitHub 恢复请求", retryAtElapsed = checkPolicy.retryAtElapsed)
    }
    fun close() { closed = true; generation.incrementAndGet(); task?.cancel(true); noticeTask?.cancel(true); worker.shutdownNow() }
}
