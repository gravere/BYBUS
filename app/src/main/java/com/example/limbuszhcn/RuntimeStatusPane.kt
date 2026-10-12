package com.example.limbuszhcn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun RuntimeStatusPane(state: LauncherUiState, onGameUpdates: () -> Unit, onRefresh: () -> Unit) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        DetailSection("游戏与汉化") {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "游戏更新与安装", onClick = onGameUpdates)
                .semantics { contentDescription = "游戏更新与安装" }
                .heightIn(min = 56.dp).padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                DetailRow("游戏版本", state.installedVersionName ?: state.installedVersion?.toString() ?: "未安装",
                    modifier = Modifier.weight(1f))
                LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(15.dp))
            }
            HorizontalDivider(color = C.Line)
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        DetailSection("运行记录", action = {
            IconButton(onClick = onRefresh, enabled = !state.busy,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "重新检测" }) {
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), color = C.Accent, strokeWidth = 2.dp)
                else LauncherGlyph(Glyph.Refresh, C.Accent, Modifier.size(20.dp))
            }
        }) {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.runtimeStatus, color = C.Muted, style = MaterialTheme.typography.bodySmall)
                if (state.busy || state.failed) {
                    Text(if (state.busy) state.stage else state.message, color = if (state.failed) C.Error else C.Muted,
                        style = MaterialTheme.typography.bodySmall)
                } else if (state.message.isNotBlank() && state.message != state.runtimeStatus) {
                    TextButton(onClick = { showDetails = !showDetails }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(if (showDetails) "收起详细记录" else "展开详细记录", style = MaterialTheme.typography.labelMedium)
                    }
                    AnimatedVisibility(showDetails, enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(120))) {
                        Text(state.message, color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
