package com.example.limbuszhcn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun RuntimeStatusPane(state: LauncherUiState, onGameUpdates: () -> Unit, onTranslation: () -> Unit, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.busy) DetailCard {
            OperationProgress(state)
        }
        DetailSection("游戏与汉化", contentPadding = PaddingValues(0.dp)) {
            Row(Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "游戏更新与安装", onClick = onGameUpdates)
                .semantics { contentDescription = "游戏更新与安装" }
                .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                DetailRow("游戏版本", state.installedVersionName ?: state.installedVersion?.toString() ?: "未安装",
                    modifier = Modifier.weight(1f))
                LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(15.dp))
            }
            DetailActionDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailRow("安装来源", state.installSource.label)
                DetailRow("游戏同步", if (state.container?.importedVersionCode == state.installedVersion && state.installedVersion != null &&
                    state.container?.requiresInstalledGameResync == false) "已同步" else "启动时同步")
                DetailRow("兼容性", when {
                    state.installedVersion == null -> "等待安装"
                    state.compatibility == null -> "正在检测"
                    !state.compatibility.isSupported -> "设备暂不支持"
                    state.nativeCompatibilityIssue != null -> "当前构建待适配"
                    else -> "当前构建已匹配"
                })
                state.nativeCompatibilityIssue?.let { Text(it, color = C.Error, style = MaterialTheme.typography.bodySmall) }
            }
            DetailActionDivider()
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "汉化源与更新", onClick = onTranslation)
                .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                DetailRow("本地汉化", if (state.sources.selected.isOfficial) state.translation?.version ?: "未准备"
                    else state.sources.selected.label, modifier = Modifier.weight(1f))
                LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(15.dp))
            }
            DetailActionDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val source = state.sources.selected
                val prepared = state.translation ?: state.resourcePreparation
                DetailRow("汉化来源", source.name)
                if (source.kind == "ai") source.aiEntryCount?.let { DetailRow("AI 译文", "$it 条") }
                state.packageTimeLabel(source)?.let { DetailRow("文件时间", it) }
                DetailRow("资源状态", when {
                    prepared?.liveResourcesReady == true -> "已准备，随游戏资源加载"
                    prepared?.indexActivated == true -> "已准备，下次启动使用"
                    prepared?.startupIndexAvailable == true -> "启动页已准备"
                    !source.isOfficial -> "已导入，启动时校验"
                    else -> "尚未准备，启动时下载"
                })
                DetailRow("更新方式", when {
                    source.isOfficial -> "启动前自动检查"
                    source.kind == "ai" && (source.independent || source.baseId != null && source.baseId != "zero") -> "按需管理和继续 AI 翻译"
                    source.kind == "ai" -> "检查零协更新或继续补译"
                    else -> "导入新包替换"
                })
            }
        }
        state.compatibility?.let { device ->
            DetailSection("设备") {
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetailRow("型号", device.deviceLabel)
                    DetailRow("系统", device.androidLabel)
                    if (!device.isSupported) Text(device.recommendation, color = C.Error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        DetailSection("上次启动", action = {
            ActionIconButton(Glyph.Refresh, "重新检测", onRefresh, busy = state.busy)
        }) {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val record = state.runtimeStatus
                Text(record.result, style = MaterialTheme.typography.titleSmall)
                record.startedAtMillis?.let {
                    DetailRow("启动时间", java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(it)))
                    DetailRow("汉化文本", record.translation)
                    DetailRow("中文字体", record.font)
                }
                record.issue?.let { Text(it, color = C.Error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (state.busy || state.failed) DetailSection("当前操作") {
            Text(if (state.busy) state.stage else state.message,
                color = if (state.failed) C.Error else C.Ink, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}
