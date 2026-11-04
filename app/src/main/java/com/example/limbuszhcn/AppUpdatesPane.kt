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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.selfupdate.AppUpdateState
import com.example.limbuszhcn.selfupdate.releaseNoteItems
import kotlinx.coroutines.delay
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun UpdateTabs(selected: UpdateTab, appBusy: Boolean, translationBusy: Boolean, onSelect: (UpdateTab) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        SourceTabs(UpdateTab.entries, selected, label = {
            when (it) {
                UpdateTab.App -> if (appBusy) "BYBUS · 更新中" else "BYBUS"
                UpdateTab.Translation -> if (translationBusy) "汉化资源 · 检查中" else "汉化资源"
            }
        }, description = "更新类别", onSelect = onSelect)
    }
}

internal enum class UpdateTab { App, Translation }

@Composable
internal fun AppUpdatesPane(state: AppUpdateState, onCheck: () -> Unit, onOpenPage: () -> Unit) {
    val release = state.release
    val available = release?.newerThan(state.installedCode) == true
    val retryAt = state.retryAtElapsed
    val retryRemaining = updateRetryRemaining(retryAt)
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LauncherGlyph(Glyph.Mark, C.Accent, Modifier.size(36.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("BYBUS", style = MaterialTheme.typography.titleLarge)
                Text("当前 ${androidx.compose.ui.res.stringResource(R.string.app_version_name)}",
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onCheck, enabled = !state.busy && retryRemaining == 0L) {
                LauncherGlyph(Glyph.Refresh, if (state.busy || retryRemaining > 0) C.Muted else C.Ink,
                    Modifier.size(21.dp), "检查应用更新")
            }
            IconButton(onClick = onOpenPage) {
                LauncherGlyph(Glyph.External, C.Muted, Modifier.size(20.dp), "GitHub 发行页面")
            }
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
    }
}

@Composable
internal fun TranslationUpdatesPane(state: LauncherUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(Glyph.Translation, accent = true)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("都市零协会", style = MaterialTheme.typography.titleMedium)
                Text("边狱巴士 · 简体中文", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Surface(color = C.Background, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本地汉化", color = C.Muted, style = MaterialTheme.typography.labelMedium)
                Text(state.translation?.version ?: "尚未准备", style = MaterialTheme.typography.headlineSmall)
                Text(if (state.translation != null) "已保存，下次启动继续使用"
                    else if (state.container?.gameImported == true) "检查更新后下载汉化资源"
                    else "先在首页准备游戏，再检查汉化资源", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun TranslationUpdateControls(state: LauncherUiState, onCheck: () -> Unit) {
    val checking = state.busy && state.stage == "检查零协更新并校验汉化文件"
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = C.Line)
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.stage == "检查零协更新并校验汉化文件") {
                Text(state.message, color = if (state.failed) C.Error else C.Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth(), color = C.Accent, trackColor = C.Line)
            Button(onClick = onCheck, enabled = !state.busy && state.container?.gameImported == true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                Text(if (checking) "正在检查汉化" else "检查汉化更新")
            }
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
