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
internal fun RuntimeStatusPane(state: LauncherUiState, onGameUpdates: () -> Unit, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                DetailRow("本地汉化", state.translation?.version ?: "尚未准备")
                state.nativeCompatibilityIssue?.let { Text(it, color = C.Error, style = MaterialTheme.typography.bodySmall) }
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
            IconButton(onClick = onRefresh, enabled = !state.busy,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "重新检测" }) {
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), color = C.Accent, strokeWidth = 2.dp)
                else LauncherGlyph(Glyph.Refresh, C.Accent, Modifier.size(20.dp))
            }
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
