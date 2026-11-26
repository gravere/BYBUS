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
    onAppUpdate: () -> Unit, onTranslation: () -> Unit,
    onExport: () -> Unit, onSettings: () -> Unit,
    onMigration: () -> Unit, onRepair: () -> Unit, onClearData: () -> Unit,
    onGoogleSettings: () -> Unit,
    onRefresh: () -> Unit, onAbout: () -> Unit, onAi: () -> Unit = {}, onCoverage: () -> Unit = {}, onProject: () -> Unit = {},
    onDesktopIcon: () -> Unit = {}) {
    val enabled = !state.busy
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (page) {
            "tools" -> {
                DetailActionSection("汉化") {
                    SheetAction(Glyph.Translation, "汉化源与更新", navigation = true, onClick = onTranslation)
                    DetailActionDivider()
                    SheetAction(Glyph.Ai, "AI 翻译", navigation = true, onClick = onAi)
                    DetailActionDivider()
                    SheetAction(Glyph.Search, "汉化覆盖", navigation = true, onClick = onCoverage)
                    DetailActionDivider()
                    SheetAction(Glyph.Globe, "翻译项目", navigation = true, onClick = onProject)
                }
                DetailActionSection("游戏与账号") {
                    SheetAction(Glyph.Gamepad, "游戏更新", navigation = true, onClick = onGameStore)
                    DetailActionDivider()
                    SheetAction(Glyph.Account, "Google 账号与付款", enabled, navigation = true, onClick = onGoogleSettings)
                    DetailActionDivider()
                    SheetAction(Glyph.Battery, "后台运行", navigation = true, onClick = onSettings)
                    DetailActionDivider()
                    SheetAction(Glyph.Import, "数据迁移", navigation = true, onClick = onMigration)
                }
                DetailActionSection("应用与维护") {
                    SheetAction(Glyph.Image, "桌面图标", navigation = true, onClick = onDesktopIcon)
                    DetailActionDivider()
                    SheetAction(Glyph.Upgrade, "应用更新", showUpdate = appUpdateAvailable, navigation = true, onClick = onAppUpdate)
                    DetailActionDivider()
                    SheetAction(Glyph.Repair, "修复", navigation = true, onClick = onRepair)
                    DetailActionDivider()
                    SheetAction(Glyph.Delete, "清除数据", navigation = true, onClick = onClearData)
                    DetailActionDivider()
                    SheetAction(Glyph.File, "导出诊断", enabled, onClick = onExport)
                    DetailActionDivider()
                    SheetAction(Glyph.Info, "使用说明与版本", navigation = true, onClick = onAbout)
                }
            }
            "status" -> RuntimeStatusPane(state, onGameStore, onTranslation, onRefresh)
            "error" -> {
                Text(state.message, color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                DetailActionSection("操作") {
                    SheetAction(Glyph.Refresh, "重新检测", enabled, onClick = onRefresh)
                    DetailActionDivider()
                    SheetAction(Glyph.Repair, "修复", enabled, navigation = true, onClick = onRepair)
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
    external: Boolean = false, navigation: Boolean = false, showUpdate: Boolean = false, destructive: Boolean = false, onClick: () -> Unit) {
    val opacity by androidx.compose.animation.core.animateFloatAsState(if (enabled) 1f else .4f,
        androidx.compose.animation.core.tween(140), label = "row enabled")
    // The containing group owns the rounded outline; the entire row owns its feedback.
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { if (showUpdate) stateDescription = "有 BYBUS 新版本" }
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp).graphicsLayer { alpha = opacity },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (destructive) LauncherGlyph(icon, C.Error, Modifier.size(38.dp).padding(8.dp)) else IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = if (destructive) C.Error else C.Ink, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
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
    val angle by androidx.compose.animation.core.animateFloatAsState(if (expanded) 270f else 90f,
        androidx.compose.animation.core.tween(160), label = "disclosure direction")
    Surface(color = C.Background, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(16.dp).rotate(angle))
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
        }
        Text(androidx.compose.ui.res.stringResource(R.string.app_version_name), color = C.Ink,
            style = MaterialTheme.typography.labelMedium)
    }
    GuideSection("开始使用", listOf(
        "安装官方游戏即可，不用先打开它。回到 BYBUS，点「启动游戏」。",
        "BYBUS 准备汉化后会打开游戏。在游戏里登录、下载资源，汉化随资源加载生效。",
        "以后从 BYBUS 启动即可。需要自行具备能连接游戏、GitHub 或所用 AI 服务的网络。"))
    GuideSection("返回游戏与管理", listOf(
        "游戏运行时，点桌面 BYBUS 图标会返回游戏。",
        "想调整设置：长按桌面图标，选择「管理 BYBUS」。它打开启动器首页，方便换汉化、查看任务和更新。",
        "首页正在处理的按钮可打开当前任务。AI 翻译可暂停、继续；取消会删除本次尚未发布的译文。"))
    GuideSection("汉化与 AI 翻译", listOf(
        "「汉化源与更新」可更新零协资源、导入本地包，以及切换、改名或删除汉化包。",
        "导入后检查已下载文本，可选零协补齐、AI 补译或保持原样。零协只补空缺；「导入包设置」可恢复原包或添加更新链接。",
        "「汉化覆盖」检查当前游戏语言：未覆盖是没有匹配译文，待核对是译文仍含日文或韩文。可按剧情、战斗等分类，勾选需要翻译的条目。",
        "「AI 翻译」可补充当前包，也可从零翻译。先填写服务地址、密钥和模型，再选范围、数量与提示词；费用由你的 API 服务商收取。",
        "生成的 AI 包可查看、删除或重译条目，也能检查原包更新，采用原包的新译文。汉化包或游戏文本变动后再检查覆盖。"))
    GuideSection("应用与游戏更新", listOf(
        "「应用更新」更新 BYBUS；「游戏更新」查看游戏版本并打开安装来源。系统里的官方游戏更新后，BYBUS 会同步安装文件。",
        "保留官方游戏安装包。大型游戏资源只在 BYBUS 内下载一次，日常更新仅补充游戏需要的新资源。"))
    GuideSection("账号、后台与翻译项目", listOf(
        "可在游戏登录页添加 Google 账号，也可从「Google 账号与付款」管理容器内账号。商店服务默认开启。",
        "切到后台容易被系统关闭时，查看「后台运行」中的系统设置入口。",
        "首页翻译项目可跟随当前汉化源，也可固定显示；都市零协会可查看 GitHub 更新和翻译进度。"))
    GuideSection("数据与维护", listOf(
        "「数据迁移」可导入、导出游戏资源 ZIP。账号与 API 密钥不包含在资源包中。",
        "已 Root 且原版下载过资源时，可选「从原版导入（Root）」。复制需要额外空间，原版和已有文件都会保留。",
        "「桌面图标」可切换内置样式，也可用自己的图片添加桌面入口。自选图片不会替换应用列表中的原图标。",
        "汉化或安装文件异常时用「修复」，会保留账号和已下载资源。仍有问题可「导出诊断」反馈。",
        "「清除数据」会删除游戏下载资源，下次需重新下载。覆盖更新 BYBUS 会保留数据，卸载或系统清除应用数据会删除它们。"))
}

@Composable
private fun GuideSection(title: String, items: List<String>) {
    DetailSection(title) {
        Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { Text(it, color = C.Ink, style = MaterialTheme.typography.bodyMedium) }
        }
    }
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
