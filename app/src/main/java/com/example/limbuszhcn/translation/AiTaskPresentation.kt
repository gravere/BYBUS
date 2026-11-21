package com.example.limbuszhcn.translation

import java.util.Locale

/** Compact surfaces use stage names, never raw batch messages or exception details. */
internal fun compactAiTaskLabel(message: String): String = when {
    message.contains("停止") || message.contains("暂停") -> "正在暂停"
    message.startsWith("正在翻译") -> "正在翻译"
    message.startsWith("恢复") -> "准备翻译"
    message.contains("等待翻译") || message.contains("等待模型") -> "等待回复"
    message.contains("接收译文") -> "接收译文"
    message.contains("正在思考") -> "模型思考"
    message.contains("发送请求") -> "发送请求"
    message.contains("网络") || message.contains("联网") || message.contains("连接") || message.contains("休眠") -> "等待网络"
    message.contains("保存") && !message.contains("读取") -> "保存译文"
    message.contains("校验") || message.contains("核对本批") -> "核对译文"
    message.contains("读取") || message.contains("准备") || message.contains("参考") || message.contains("范围") || message.contains("所选文本") -> "准备翻译"
    else -> "正在翻译"
}

internal data class AiCancelPrompt(val sessionId: String, val taskId: String?) {
    fun matches(activeSessionId: String?, savedTaskId: String?): Boolean = sessionId == activeSessionId ||
        taskId != null && taskId == savedTaskId
}

internal fun canActOnPausedTask(requestedId: String, task: AiTaskSnapshot?, busy: Boolean): Boolean =
    !busy && requestedId.isNotEmpty() && task?.id == requestedId && task.phase == AiTaskPhase.Paused

internal fun formatTaskDuration(elapsedMillis: Long): String {
    val seconds = elapsedMillis.coerceAtLeast(0) / 1000
    return if (seconds < 3600) String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
