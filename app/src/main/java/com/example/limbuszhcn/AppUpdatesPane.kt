package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.selfupdate.AppUpdateState
import kotlinx.coroutines.delay
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun AppUpdatesPane(state: AppUpdateState, launcher: LauncherUiState, onCheck: () -> Unit,
    onDownload: () -> Unit, onInstall: () -> Unit, onCancel: () -> Unit, onOpenPage: () -> Unit,
    onTranslationUpdate: () -> Unit) {
    val release = state.release
    val available = release?.newerThan(state.installedCode) == true
    val retryAt = state.retryAtElapsed
    fun remaining() = retryAt?.let { (it - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0) } ?: 0L
    val retryRemaining by produceState(remaining(), retryAt) {
        value = remaining()
        while (value > 0) { delay(minOf(value, 1000)); value = remaining() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("BYBUS", style = MaterialTheme.typography.titleLarge)
            Text("${androidx.compose.ui.res.stringResource(R.string.app_version_name)} · 构建 ${state.installedCode}",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Text(if (retryAt != null && retryRemaining == 0L) "可以重新检查更新" else state.message,
            style = MaterialTheme.typography.titleMedium)
        if (state.busy) {
            state.progress?.let {
                LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth(), color = C.Accent, trackColor = C.Line)
                Text("$it%", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            } ?: LinearProgressIndicator(Modifier.fillMaxWidth(), color = C.Accent, trackColor = C.Line)
        }
        if (available) {
            DetailSection("新版本") {
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow("版本", "${release.versionName} · 构建 ${release.versionCode}")
                    val delta = state.delta
                    DetailRow("更新方式", if (delta != null) "增量更新" else "完整安装包")
                    DetailRow("下载大小", String.format(java.util.Locale.ROOT, "%.1f MB", (delta?.asset?.size ?: release.apk.size) / 1_000_000.0))
                    if (delta != null) Text("自动合成安装包；不可用时会改为完整下载。", color = C.Muted,
                        style = MaterialTheme.typography.bodySmall)
                    if (release.notes.isNotBlank()) Text(release.notes, color = C.Muted, style = MaterialTheme.typography.bodyMedium)
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
        if (available) {
            Button(onClick = if (state.ready) onInstall else onDownload,
                enabled = !state.busy && (state.ready || retryRemaining == 0L) && (!state.ready || !launcher.busy),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                Text(if (state.ready) "安装更新" else "下载更新")
            }
            Text("覆盖安装会保留游戏资源、登录和设置。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        DetailSection("应用版本") {
            SheetAction(Glyph.Apps, "检查应用更新", !state.busy && retryRemaining == 0L, onClick = onCheck)
            HorizontalDivider(color = C.Line)
            SheetAction(Glyph.ReleaseTag, "GitHub 发行页面", external = true, onClick = onOpenPage)
        }
        if (state.busy) TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("取消") }
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
