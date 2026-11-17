package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.example.limbuszhcn.selfupdate.AppUpdateState
import com.example.limbuszhcn.selfupdate.releaseNoteItems
import kotlinx.coroutines.delay
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun AppUpdatesPane(state: AppUpdateState, onCheck: () -> Unit, onOpenPage: () -> Unit) {
    val release = state.release
    val available = release?.newerThan(state.installedCode) == true
    val retryAt = state.retryAtElapsed
    val retryRemaining = updateRetryRemaining(retryAt)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LauncherGlyph(Glyph.Mark, C.Accent, Modifier.size(36.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("BYBUS", style = MaterialTheme.typography.titleLarge)
                Text("当前 ${androidx.compose.ui.res.stringResource(R.string.app_version_name)}",
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
            ActionIconButton(Glyph.Refresh, "检查应用更新", onCheck,
                enabled = !state.busy && retryRemaining == 0L, busy = state.busy && state.checking)
            ActionIconButton(Glyph.External, "GitHub 发行页面", onOpenPage)
        }
        if (available) {
            Surface(color = C.AccentSoft, contentColor = C.OnAccentSoft, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("可更新至 ${release.versionName}", style = MaterialTheme.typography.titleMedium)
                    val delta = state.delta
                    val reuse = state.reuse
                    val size = String.format(java.util.Locale.ROOT, "%.1f MB",
                        (reuse?.downloadBytes ?: delta?.asset?.size ?: release.apk.size) / 1_000_000.0)
                    Text(if (!state.downloadPlanned) "下载前自动计算更新大小"
                        else "${if (reuse != null || delta != null) "增量更新" else "完整安装包"} · $size",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (release != null && release.versionCode >= state.installedCode) {
            val points = remember(release.notes) { releaseNoteItems(release.notes) }
            if (points.isNotEmpty()) {
                DetailSection("更新内容") {
                    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        points.forEach { point ->
                            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("•", color = C.Accent, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.width(12.dp).clearAndSetSemantics {})
                                Text(point, color = C.Muted, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        state.error?.let {
            val seconds = (retryRemaining + 999) / 1000
            val error = if (retryAt == null) it else if (seconds > 0)
                "GitHub 请求受限，${seconds / 60} 分 ${seconds % 60} 秒后可重试。也可查看发行页面。"
                else "等待已结束，可重新检查更新。"
            Text(error, color = if (retryAt != null && seconds == 0L) C.Muted else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun TranslationUpdateControls(state: LauncherUiState, onCheck: () -> Unit, horizontalPadding: Dp = 24.dp) {
    val checking = state.busy && state.stage == "校验与更新汉化资源"
    val showingResult = state.stage == "校验与更新汉化资源"
    UpdateActionCard(message = state.message.takeIf { showingResult }, icon = Glyph.Translation,
        busy = checking, failed = showingResult && state.failed, description = "汉化更新操作",
        progressDescription = "汉化更新进度", horizontalPadding = horizontalPadding,
        action = if (checking) null else if (state.sources.selected.isOfficial || state.sources.selected.kind == "ai") "检查汉化更新" else "校验汉化包",
        actionEnabled = !state.busy && (state.container?.gameImported == true || state.sources.selected.kind == "ai"), onAction = onCheck)
}

/** Keep transfer feedback beside its action, outside long release notes. */
@Composable
internal fun AppUpdateControls(state: AppUpdateState, launcher: LauncherUiState,
    onDownload: () -> Unit, onInstall: () -> Unit, onCancel: () -> Unit, horizontalPadding: Dp = 24.dp) {
    val retryRemaining = updateRetryRemaining(state.retryAtElapsed)
    val hasAction = !state.busy && state.release?.newerThan(state.installedCode) == true
    UpdateActionCard(
        message = if (state.retryAtElapsed != null && retryRemaining == 0L) "可以重新检查更新" else state.message,
        icon = when { state.error != null -> Glyph.Info; state.busy -> Glyph.Progress
            state.ready || state.message == "已是最新版本" -> Glyph.Check; else -> Glyph.Upgrade },
        busy = state.busy, progress = state.progress, failed = state.error != null,
        description = "应用更新操作", progressDescription = "应用更新进度", horizontalPadding = horizontalPadding,
        action = if (hasAction) if (state.ready) "安装更新" else "下载更新" else null,
        actionEnabled = (state.ready || retryRemaining == 0L) && (!state.ready || !launcher.busy),
        onAction = if (state.ready) onInstall else onDownload, onCancel = onCancel)
}

@Composable
private fun updateRetryRemaining(retryAt: Long?): Long {
    fun remaining() = retryAt?.let { (it - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0) } ?: 0L
    val value by produceState(remaining(), retryAt) {
        value = remaining()
        while (value > 0) { delay(minOf(value, 1000)); value = remaining() }
    }
    return value
}
