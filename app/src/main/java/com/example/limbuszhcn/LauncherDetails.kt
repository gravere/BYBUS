package com.example.limbuszhcn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun LauncherDetails(page: String, state: LauncherUiState,
    onGameStore: () -> Unit,
    onAppUpdate: () -> Unit,
    onSync: () -> Unit, onRestore: () -> Unit, onExport: () -> Unit, onSettings: () -> Unit,
    onGoogleSettings: () -> Unit,
    onRefresh: () -> Unit, onAbout: () -> Unit) {
    val enabled = !state.busy
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (page) {
            "tools" -> {
                DetailSection("版本与资源") {
                    SheetAction(Glyph.Refresh, "更新", subtitle = "应用与汉化资源", navigation = true, onClick = onAppUpdate)
                    HorizontalDivider(color = C.Line)
                    SheetAction(Glyph.Import, "游戏更新", navigation = true, onClick = onGameStore)
                }
                DetailSection("管理") {
                    SheetAction(Glyph.Import, "同步游戏", enabled && state.installedVersion != null,
                        subtitle = "保留已下载的游戏资源", onClick = onSync)
                    HorizontalDivider(color = C.Line)
                    SheetAction(Glyph.Battery, "后台运行", enabled, navigation = true, onClick = onSettings)
                    HorizontalDivider(color = C.Line)
                    SheetAction(Glyph.Account, "Google 账号与付款", enabled, navigation = true,
                        subtitle = "管理容器账号与应用内购买", onClick = onGoogleSettings)
                    HorizontalDivider(color = C.Line)
                    SheetAction(Glyph.File, "导出诊断", enabled, onClick = onExport)
                    HorizontalDivider(color = C.Line)
                    SheetAction(Glyph.Info, "使用说明与版本", navigation = true, onClick = onAbout)
                }
                DetailSection("汉化设置") {
                    SheetAction(Glyph.Stop, "停用汉化", enabled && state.translation != null,
                        subtitle = "保留资源，下次启动会重新准备", onClick = onRestore)
                }
            }
            "status" -> RuntimeStatusPane(state, onGameStore, onRefresh)
            "error" -> {
                Text(state.message, color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                SheetAction(Glyph.Refresh, "重新检测", enabled, onClick = onRefresh)
                SheetAction(Glyph.File, "导出诊断", enabled, onClick = onExport)
            }
            else -> {
                Text(androidx.compose.ui.res.stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text("汉化文本由都市零协会制作。", color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text("开始使用", style = MaterialTheme.typography.titleMedium)
                Text("首次启动后，在游戏中登录并下载资源。完成后返回这里，再启动一次即可。",
                    color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                Text("之后从本应用进入游戏，每次启动前检查游戏版本与汉化更新。",
                    color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                Text("更新与数据", style = MaterialTheme.typography.titleMedium)
                Text("系统安装的游戏可从不同渠道更新，返回后自动识别。保留系统安装包即可，大资源只需在本应用内下载。容器单独保存登录和资源，覆盖升级会保留。",
                    color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                Text("请勿卸载原版。卸载本应用或清除本应用数据会删除容器。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                Text("游戏新版本可能需要新的兼容修复。", color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = C.Line)
                DetailRow("版本", androidx.compose.ui.res.stringResource(R.string.app_version_name))
                Text("非官方社区启动器 · LocalizeLimbusCompany", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun SheetAction(icon: Glyph, title: String, enabled: Boolean = true, subtitle: String? = null,
    external: Boolean = false, navigation: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 56.dp).padding(vertical = 10.dp).graphicsLayer { alpha = if (enabled) 1f else .4f },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, color = C.Muted, style = MaterialTheme.typography.bodySmall) }
        }
        if (external || navigation) LauncherGlyph(if (external) Glyph.External else Glyph.Chevron, C.Muted, Modifier.size(17.dp))
    }
}

@Composable
internal fun DetailSection(title: String, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = C.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            action?.invoke()
        }
        Surface(color = C.Background, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), content = content)
        }
    }
}

@Composable
internal fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (maxWidth < 232.dp * scale) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(label, color = C.Muted, style = MaterialTheme.typography.bodySmall)
                Text(value, color = C.Ink, style = MaterialTheme.typography.bodyMedium)
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Text(label, color = C.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp * scale))
            Text(value, color = C.Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}
