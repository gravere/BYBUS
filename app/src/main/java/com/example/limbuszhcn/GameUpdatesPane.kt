package com.example.limbuszhcn

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.update.GameStores
import com.example.limbuszhcn.update.GameUpdateSource
import com.example.limbuszhcn.update.GameUpdateState
import com.example.limbuszhcn.update.GameVersion
import java.text.DateFormat
import java.util.Date
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun GameUpdatesPane(state: LauncherUiState, onCheck: () -> Unit,
    onSelect: (GameUpdateSource) -> Unit, onPage: (String) -> Unit, onOriginalStore: () -> Unit,
    onImport: () -> Unit, onInstall: () -> Unit, onCancel: () -> Unit) {
    val selected = state.preferredGameSource ?: GameStores.sourceFor(state.installSource) ?: GameUpdateSource.GooglePlay
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (state.busy) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.stage, color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.failed) Text(state.message, color = C.Error, style = MaterialTheme.typography.bodySmall)
        InstallConfirmation(state, onInstall, onCancel)

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 320.dp * LocalDensity.current.fontScale) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    DetailRow("本机版本", state.installedVersionName ?: "未安装")
                    DetailRow("安装来源", state.installSource.label)
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("本机版本", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    Text(state.installedVersionName ?: "未安装", style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End) {
                    Text("安装来源", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    Text(state.installSource.label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End)
                }
            }
        }
        if (state.nativeCompatibilityIssue != null) {
            Text("当前游戏版本需要适配，账号与资源已保留。", color = C.Error, style = MaterialTheme.typography.bodySmall)
        }

        Surface(color = C.Background, shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("更新渠道", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onCheck, enabled = !state.gameUpdate.loading,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "刷新渠道版本" }) {
                        if (state.gameUpdate.loading) CircularProgressIndicator(Modifier.size(20.dp), color = C.Accent, strokeWidth = 2.dp)
                        else LauncherGlyph(Glyph.Refresh, C.Accent, Modifier.size(20.dp))
                    }
                }
                SourceTabs(GameUpdateSource.entries, selected, { it.label }, "更新渠道", onSelect, trackColor = C.Panel)
                Crossfade(selected, animationSpec = tween(180), label = "channel details") { source ->
                    ChannelVersion(state.gameUpdate.channels.getValue(source), state.installedVersionName)
                }
                // The label and navigation target use the same selection; querying never changes it.
                Button(onClick = { onPage(selected.page) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                    Crossfade(selected, animationSpec = tween(180), modifier = Modifier.weight(1f), label = "store action") { source ->
                        Text("前往 ${source.label}", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(12.dp))
                    LauncherGlyph(Glyph.External, C.OnPrimary, Modifier.size(18.dp))
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("其他方式", color = C.Muted, style = MaterialTheme.typography.labelMedium)
            SheetAction(Glyph.Folder, "从文件更新", !state.busy && !state.gameInstallPending && state.gameInstallReview == null,
                subtitle = "APK、分包、XAPK / APKS", navigation = true, onClick = onImport)
            HorizontalDivider(color = C.Line)
            SheetAction(Glyph.Globe, "APKPure 网页", external = true, onClick = { onPage(GameStores.APKPURE_PAGE) })
            if (state.installSource.canOpen && GameStores.sourceFor(state.installSource) == null) {
                HorizontalDivider(color = C.Line)
                SheetAction(Glyph.Store, "原安装商店 · ${state.installSource.label}", external = true, onClick = onOriginalStore)
            }
        }
        Text("保留系统安装包，游戏资源继续在 BYBUS 内下载。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChannelVersion(result: GameUpdateState, installed: String?) {
    val checked = remember(result.checkedAt) {
        result.checkedAt.takeIf { it > 0 }?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        }
    }
    val comparison = GameVersion.compare(result.latestVersion, installed)
    val status = when {
        result.loading -> "检查中"
        result.issue != null -> "暂未刷新"
        result.latestVersion == null -> "尚未查询"
        comparison == 0 -> "与本机一致"
        comparison != null && comparison > 0 -> "有新版本"
        comparison != null && comparison < 0 -> "本机版本较新"
        else -> "渠道版本"
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(result.latestVersion ?: "—", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Text(status, color = C.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(max = 108.dp))
        }
        Text(when {
            result.loading -> "正在获取渠道版本…"
            result.issue != null -> "查询失败，可前往商店查看"
            checked != null -> "检查于 $checked"
            else -> "刷新以获取版本，或直接前往商店"
        }, color = C.Muted, style = MaterialTheme.typography.bodySmall)
        if (result.issue != null && checked != null) {
            Text("上次记录 $checked", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (!result.loading && result.issue == null && comparison != null && comparison > 0) {
            Text("新版本的兼容性仍需确认", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InstallConfirmation(state: LauncherUiState, onInstall: () -> Unit, onCancel: () -> Unit) {
    val review = state.gameInstallReview
    if (review == null && !state.gameInstallPending) return
    Surface(color = C.AccentSoft, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (review != null) "安装 ${review.version}" else "系统安装待完成", style = MaterialTheme.typography.titleMedium)
            if (review != null) {
                Text("${review.apkCount} 个文件 · ${android.text.format.Formatter.formatFileSize(LocalContext.current, review.bytes)}",
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
                Text("签名与兼容性检查通过，保留账号和资源。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onInstall, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp)) {
                Text(if (review != null) "交给系统安装" else "继续安装确认")
            }
            TextButton(onClick = onCancel, enabled = !state.busy, modifier = Modifier.align(Alignment.End)) { Text("取消本次安装") }
        }
    }
}
