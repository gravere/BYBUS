package com.example.limbuszhcn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun LauncherDetails(page: String, state: LauncherUiState,
    appUpdateAvailable: Boolean = false,
    onGameStore: () -> Unit,
    onAppUpdate: () -> Unit,
    onSync: () -> Unit, onRestore: () -> Unit, onExport: () -> Unit, onSettings: () -> Unit,
    onMigration: () -> Unit,
    onGoogleSettings: () -> Unit,
    onRefresh: () -> Unit, onAbout: () -> Unit) {
    val enabled = !state.busy
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (page) {
            "tools" -> {
                DetailActionSection("版本与资源") {
                    SheetAction(Glyph.Upgrade, "更新", showUpdate = appUpdateAvailable, navigation = true, onClick = onAppUpdate)
                    DetailActionDivider()
                    SheetAction(Glyph.Gamepad, "游戏更新", navigation = true, onClick = onGameStore)
                }
                DetailActionSection("管理") {
                    SheetAction(Glyph.Sync, "同步游戏", enabled && state.installedVersion != null,
                        onClick = onSync)
                    DetailActionDivider()
                    SheetAction(Glyph.Import, "数据迁移", enabled, navigation = true, onClick = onMigration)
                    DetailActionDivider()
                    SheetAction(Glyph.Battery, "后台运行", enabled, navigation = true, onClick = onSettings)
                    DetailActionDivider()
                    SheetAction(Glyph.Account, "Google 账号与付款", enabled, navigation = true,
                        onClick = onGoogleSettings)
                    DetailActionDivider()
                    SheetAction(Glyph.File, "导出诊断", enabled, onClick = onExport)
                    DetailActionDivider()
                    SheetAction(Glyph.Info, "使用说明与版本", navigation = true, onClick = onAbout)
                }
                DetailActionSection("汉化设置") {
                    SheetAction(Glyph.Stop, "停用汉化", enabled && state.translation != null,
                        onClick = onRestore)
                }
            }
            "status" -> RuntimeStatusPane(state, onGameStore, onRefresh)
            "error" -> {
                Text(state.message, color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                DetailActionSection("操作") {
                    SheetAction(Glyph.Refresh, "重新检测", enabled, onClick = onRefresh)
                    DetailActionDivider()
                    SheetAction(Glyph.File, "导出诊断", enabled, onClick = onExport)
                }
            }
            else -> AboutPane()
        }
    }
}

@Composable
internal fun SheetAction(icon: Glyph, title: String, enabled: Boolean = true, subtitle: String? = null,
    external: Boolean = false, navigation: Boolean = false, showUpdate: Boolean = false, onClick: () -> Unit) {
    // The containing group owns the rounded outline; the entire row owns its feedback.
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { if (showUpdate) stateDescription = "有 BYBUS 新版本" }
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp).graphicsLayer { alpha = if (enabled) 1f else .4f },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                if (showUpdate) UpdateDot()
            }
            subtitle?.let { Text(it, color = C.Muted, style = MaterialTheme.typography.bodySmall) }
        }
        if (external || navigation) LauncherGlyph(if (external) Glyph.External else Glyph.Chevron, C.Muted, Modifier.size(17.dp))
    }
}

@Composable
internal fun DetailCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = C.Background, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun DetailDisclosure(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(color = C.Background, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(16.dp).rotate(if (expanded) 270f else 90f))
            }
            ExpandableDetails(expanded, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}

@Composable
private fun AboutPane() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LauncherGlyph(Glyph.Mark, C.Accent, Modifier.size(32.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(androidx.compose.ui.res.stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Text("非官方社区启动器", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Text(androidx.compose.ui.res.stringResource(R.string.app_version_name), color = C.Muted,
            style = MaterialTheme.typography.labelMedium)
    }
    DetailSection("开始使用") {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("进入游戏，登录并下载资源。", "下载完成后，返回 BYBUS 再启动一次。", "以后从这里启动，自动检查汉化更新。").forEachIndexed { index, text ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text("0${index + 1}", color = C.Accent, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 3.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    Text("保留系统安装的游戏。卸载 BYBUS 或清除数据会删除资源与登录信息。",
        color = C.Muted, style = MaterialTheme.typography.bodySmall)
    DetailDisclosure("游戏更新与数据保存") {
        Text("游戏可从原安装渠道更新，返回后自动识别；大资源只需在 BYBUS 内下载。",
            color = C.Muted, style = MaterialTheme.typography.bodyMedium)
        Text("覆盖更新 BYBUS 会保留数据。游戏新版本可能需要新的兼容修复。",
            color = C.Muted, style = MaterialTheme.typography.bodyMedium)
    }
    Text("汉化文本：都市零协会\nLocalizeLimbusCompany", color = C.Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun UpdateDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(C.Error))
}

@Composable
internal fun DetailSection(title: String, action: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = C.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            action?.invoke()
        }
        Surface(color = C.Background, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(contentPadding), content = content)
        }
    }
}

/** Navigation/action groups keep feedback edge-to-edge and inset only their content. */
@Composable
internal fun DetailActionSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    DetailSection(title, contentPadding = PaddingValues(0.dp), content = content)
}

@Composable
internal fun DetailActionDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = C.Line)
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
