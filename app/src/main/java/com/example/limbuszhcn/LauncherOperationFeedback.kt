package com.example.limbuszhcn

internal fun LauncherUiState.withMigrationTask(task: com.example.limbuszhcn.migration.MigrationState?): LauncherUiState =
    if (task?.busy == true || task?.pending == true) copy(busy = true, taskPage = "migration", launchStep = -1,
        stage = task.message, message = task.message) else this

internal fun LauncherUiState.taskDestination(): String =
    if (ai.running) "ai-translation" else taskPage ?: if (ai.hasPendingTask) "ai-translation" else "status"

/** Lifecycle refreshes update device facts without erasing an actionable failure. */
internal fun LauncherUiState.beginOperation(label: String, preserveFailure: Boolean, startedAt: Long = 0): LauncherUiState =
    copy(busy = true, launchStep = -1, workProgress = WorkProgress(startedAt = startedAt),
        stage = if (preserveFailure && failed) stage else label,
        message = if (preserveFailure && failed) message else label,
        failed = preserveFailure && failed)

internal fun LauncherUiState.finishOperation(message: String, failed: Boolean,
    preserveFailure: Boolean): LauncherUiState = copy(busy = false, launchStep = -1, taskPage = null,
    failed = if (preserveFailure && this.failed) true else failed,
    message = if (preserveFailure && this.failed) this.message else message)

internal fun nextUiRequestId(previous: Long, clock: Long): Long = maxOf(previous + 1, clock)

internal fun diagnosticExportStage(cancelled: Boolean): String =
    if (cancelled) "取消导出" else "正在保存诊断包"

/** Settings probes cannot overwrite a game operation, error or launch readiness. */
internal fun LauncherUiState.beginAiSettings(label: String): LauncherUiState =
    copy(ai = ai.copy(settingsBusy = true, failed = false, message = label, testResult = null))

internal fun LauncherUiState.finishAiSettings(result: Result<String>): LauncherUiState = copy(ai = ai.copy(
    settingsBusy = false, failed = result.isFailure, message = result.getOrElse { error ->
        when (error) {
            is java.util.regex.PatternSyntaxException, is LinkageError -> "文本处理失败，请更新应用后重试"
            is java.net.SocketTimeoutException -> "请求超时，请检查网络后重试"
            is java.io.IOException -> "无法连接翻译服务，请检查网络和服务地址"
            is IllegalArgumentException, is IllegalStateException -> error.message ?: "测试未完成，请检查设置"
            else -> "测试未完成，请检查服务设置或导出诊断"
        }
    }))

internal data class OperationFeedback(val title: String, val symbol: LaunchSymbol = LaunchSymbol.Complete)

internal fun completedOperationFeedback(stage: String): OperationFeedback? = when (stage) {
    "检查零协更新并校验汉化文件" -> OperationFeedback("汉化检查完成")
    "重新同步游戏", "同步游戏，保留已有数据" -> OperationFeedback("游戏已同步")
    "正在清除游戏资源" -> OperationFeedback("游戏资源清理完成")
    "正在保存诊断包" -> OperationFeedback("诊断包已保存")
    "取消导出" -> OperationFeedback("已取消导出", LaunchSymbol.Cancelled)
    else -> null
}
