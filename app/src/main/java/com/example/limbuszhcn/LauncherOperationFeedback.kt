package com.example.limbuszhcn

/** Lifecycle refreshes update device facts without erasing an actionable failure. */
internal fun LauncherUiState.beginOperation(label: String, preserveFailure: Boolean): LauncherUiState =
    copy(busy = true, launchStep = -1,
        stage = if (preserveFailure && failed) stage else label,
        message = if (preserveFailure && failed) message else label,
        failed = preserveFailure && failed)

internal fun LauncherUiState.finishOperation(message: String, failed: Boolean,
    preserveFailure: Boolean): LauncherUiState = copy(busy = false, launchStep = -1,
    failed = if (preserveFailure && this.failed) true else failed,
    message = if (preserveFailure && this.failed) this.message else message)

internal fun nextUiRequestId(previous: Long, clock: Long): Long = maxOf(previous + 1, clock)

internal fun diagnosticExportStage(cancelled: Boolean): String =
    if (cancelled) "取消导出" else "正在保存诊断包"

internal fun completedOperationFeedback(stage: String): String? = when (stage) {
    "检查零协更新并校验汉化文件" -> "汉化检查完成"
    "重新同步游戏", "同步游戏，保留已有数据" -> "游戏已同步"
    "正在清除游戏资源" -> "游戏资源清理完成"
    "正在保存诊断包" -> "诊断包已保存"
    "取消导出" -> "已取消导出"
    else -> null
}
