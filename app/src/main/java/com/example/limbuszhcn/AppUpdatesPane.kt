package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.selfupdate.AppUpdateState
import com.example.limbuszhcn.selfupdate.releaseNoteItems
import kotlinx.coroutines.delay
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun AppUpdatesPane(state: AppUpdateState, launcher: LauncherUiState, onCheck: () -> Unit,
    onOpenPage: () -> Unit, onTranslationUpdate: () -> Unit) {
    val release = state.release
    val available = release?.newerThan(state.installedCode) == true
    val retryAt = state.retryAtElapsed
    val retryRemaining = updateRetryRemaining(retryAt)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("BYBUS", style = MaterialTheme.typography.titleLarge)
            Text("${androidx.compose.ui.res.stringResource(R.string.app_version_name)} · 构建 ${state.installedCode}",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (available) {
            DetailSection("新版本") {
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow("版本", "${release.versionName} · 构建 ${release.versionCode}")
                    val delta = state.delta
                    val reuse = state.reuse
                    DetailRow("更新方式", if (!state.downloadPlanned) "自动选择" else if (reuse != null || delta != null) "增量更新" else "完整安装包")
                    DetailRow("预计下载", if (!state.downloadPlanned) "下载前计算" else String.format(java.util.Locale.ROOT, "%.1f MB", (reuse?.downloadBytes ?: delta?.asset?.size ?: release.apk.size) / 1_000_000.0))
                    if (reuse != null || delta != null) Text("自动复用已有内容；不可用时改为完整下载。", color = C.Muted,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (release != null && release.versionCode >= state.installedCode) {
            val points = remember(release.notes) { releaseNoteItems(release.notes) }
            if (points.isNotEmpty()) {
                Text("更新内容", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        state.error?.let {
            val seconds = (retryRemaining + 999) / 1000
            val error = if (retryAt == null) it else if (seconds > 0)
                "GitHub 请求受限，${seconds / 60} 分 ${seconds % 60} 秒后可重试。也可查看发行页面。"
                else "等待已结束，可重新检查更新。"
            Text(error, color = if (retryAt != null && seconds == 0L) C.Muted else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        DetailSection("应用版本") {
            SheetAction(Glyph.Apps, "检查应用更新", !state.busy && retryRemaining == 0L, onClick = onCheck)
            HorizontalDivider(color = C.Line)
            SheetAction(Glyph.ReleaseTag, "GitHub 发行页面", external = true, onClick = onOpenPage)
        }
        DetailSection("汉化资源") {
            SheetAction(Glyph.Translation, "检查汉化更新", !launcher.busy && launcher.container?.gameImported == true,
                subtitle = if (launcher.busy && launcher.stage == "检查零协更新并校验汉化文件") launcher.message
                    else launcher.translation?.let { "已安装 · ${it.version}" } ?: "都市零协会",
                onClick = onTranslationUpdate)
        }
        if (!launcher.busy && launcher.stage == "检查零协更新并校验汉化文件") {
            Text(launcher.message, color = if (launcher.failed) MaterialTheme.colorScheme.error else C.Muted,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Keep transfer feedback beside its action, outside long release notes. */
@Composable
internal fun AppUpdateControls(state: AppUpdateState, launcher: LauncherUiState,
    onDownload: () -> Unit, onInstall: () -> Unit, onCancel: () -> Unit) {
    val retryRemaining = updateRetryRemaining(state.retryAtElapsed)
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "应用更新操作" }) {
        HorizontalDivider(color = C.Line)
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.retryAtElapsed != null && retryRemaining == 0L) "可以重新检查更新" else state.message,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (state.busy) state.progress?.let {
                    Text("${it.coerceIn(0, 100)}%", color = C.Accent, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (state.busy) {
                val progressModifier = Modifier.fillMaxWidth().semantics { contentDescription = "应用更新进度" }
                state.progress?.let {
                    LinearProgressIndicator(progress = { it.coerceIn(0, 100) / 100f }, modifier = progressModifier,
                        color = C.Accent, trackColor = C.Line)
                } ?: LinearProgressIndicator(progressModifier, color = C.Accent, trackColor = C.Line)
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("取消更新") }
            } else if (state.release?.newerThan(state.installedCode) == true) {
                Button(onClick = if (state.ready) onInstall else onDownload,
                    enabled = (state.ready || retryRemaining == 0L) && (!state.ready || !launcher.busy),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                    Text(if (state.ready) "安装更新" else "下载更新")
                }
            }
        }
    }
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
