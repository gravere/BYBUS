package com.example.limbuszhcn.migration

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.Glyph
import com.example.limbuszhcn.SourceTabs
import com.example.limbuszhcn.ActionIconButton
import com.example.limbuszhcn.IconTile
import com.example.limbuszhcn.LauncherGlyph
import com.example.limbuszhcn.ui.theme.LauncherColors as C

/** Presentation only: source ownership, copying and recovery stay in MigrationModel. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MigrationScreen(state: MigrationState, onBack: () -> Unit, onRefresh: () -> Unit,
    onMigrate: () -> Unit, onSelectFile: () -> Unit, onOpenLegacy: () -> Unit, onFinish: () -> Unit,
    onExport: () -> Unit = {}, onCancelExport: () -> Unit = {},
    onRootImport: () -> Unit = {}, onCancelRootImport: () -> Unit = {}) {
    var exporting by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = C.Background, contentColor = C.Ink) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            val wide = maxWidth >= 840.dp && maxWidth > maxHeight && LocalDensity.current.fontScale <= 1.3f
            val inset = if (maxWidth < 400.dp) 20.dp else 28.dp
            Column(Modifier.widthIn(max = if (wide) 960.dp else 640.dp).fillMaxSize()
                .padding(horizontal = inset)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ActionIconButton(Glyph.Chevron, "返回上一页", onBack,
                        rotation = 180f, modifier = Modifier.clip(CircleShape).background(C.Panel))
                    Text("数据迁移", Modifier.weight(1f).padding(horizontal = 16.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge)
                    if (!state.done) ActionIconButton(Glyph.Refresh, "重新检测", onRefresh,
                        enabled = !state.busy, busy = state.busy && state.operation == MigrationOperation.Inspect)
                }
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(state.busy, state.done, state.failed, state.pending, state.operation, exporting) {
                        scroll.scrollTo(0)
                    }
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)
                        .padding(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (state.busy || state.done) {
                            MigrationTransferCard(state, onFinish, onCancelExport, onCancelRootImport)
                        } else {
                            if (!state.pending) SourceTabs(listOf(false, true), exporting,
                                { if (it) "迁出" else "迁入" }, "迁移方向", { exporting = it })
                            if (state.failed) MigrationFailure(state)
                            state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            if (state.pending) {
                                LegacySourceCard(state, onMigrate, onOpenLegacy)
                            } else if (wide) {
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    ResourceActionCard(state, exporting, if (exporting) onExport else onSelectFile,
                                        Modifier.weight(1f).fillMaxHeight(), stretch = true)
                                    if (exporting) MigrationSteps(true, Modifier.weight(1f).fillMaxHeight())
                                    else RootResourceCard(onRootImport, Modifier.weight(1f).fillMaxHeight(), stretch = true)
                                }
                            } else {
                                ResourceActionCard(state, exporting, if (exporting) onExport else onSelectFile)
                                if (!exporting) RootResourceCard(onRootImport)
                                MigrationSteps(exporting)
                            }
                            if (!state.pending && !exporting && state.hasLegacy) LegacySourceCard(state, onMigrate, onOpenLegacy)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RootResourceCard(onImport: () -> Unit, modifier: Modifier = Modifier, stretch: Boolean = false) {
    MigrationPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Glyph.Folder, accent = true)
            Text("从本机原版导入", style = MaterialTheme.typography.titleMedium)
        }
        Text("适用于已 Root 的设备。复制原版下载过的资源，减少重复下载。",
            style = MaterialTheme.typography.bodyMedium)
        if (stretch) Spacer(Modifier.weight(1f))
        MigrationPrimaryButton("从原版导入（Root）", onImport, Glyph.Import)
    }
}

@Composable
private fun MigrationPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = C.Panel, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
private fun LegacySourceCard(state: MigrationState, onMigrate: () -> Unit, onOpenLegacy: () -> Unit,
    modifier: Modifier = Modifier, stretch: Boolean = false) {
    MigrationPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Glyph.Apps, accent = state.ready)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("从旧版 BYBUS 迁入", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (state.ready) C.Ready else C.Muted))
                    Text(if (state.ready) "可以直接迁移" else if (state.hasLegacy) "需要更新旧版" else "未检测到旧版",
                        color = if (state.ready) C.Ready else C.Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.ready) Text(byteSize(state.total), style = MaterialTheme.typography.headlineSmall)
            Text(when {
                state.ready -> "直接读取这台设备上的旧版数据，包含游戏资源与账号配置。"
                state.hasLegacy -> "先在旧版完成更新，再点右上角重新检测。"
                else -> "上次迁入还未完成。请恢复保留数据的旧版，再重新检测。"
            }, style = MaterialTheme.typography.bodyMedium)
        }
        if (stretch) Spacer(Modifier.weight(1f))
        if (state.ready) {
            MigrationPrimaryButton(if (state.pending) "继续迁入" else "从旧版迁入", onMigrate, Glyph.Import)
        } else if (state.hasLegacy) {
            MigrationPrimaryButton("打开旧版 BYBUS", onOpenLegacy, Glyph.External)
        }
    }
}

@Composable
private fun ResourceActionCard(state: MigrationState, exporting: Boolean, onAction: () -> Unit,
    modifier: Modifier = Modifier, stretch: Boolean = false) {
    MigrationPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(if (exporting) Glyph.Export else Glyph.Import, accent = true)
            Text(if (exporting) "保存游戏资源" else "导入游戏资源", style = MaterialTheme.typography.titleMedium)
        }
        Text(if (exporting) "保存已下载的游戏资源，换设备时直接导入。"
            else "导入已保存的资源包，减少重复下载。", style = MaterialTheme.typography.bodyMedium)
        if (exporting && !state.canExport) Text("先在 BYBUS 中下载游戏资源，再来迁出。",
            style = MaterialTheme.typography.bodyMedium, color = C.Muted)
        if (stretch) Spacer(Modifier.weight(1f))
        MigrationPrimaryButton(if (exporting) "保存资源包" else "选择资源包", onAction,
            if (exporting) Glyph.Export else Glyph.Folder, !exporting || state.canExport)
    }
}

@Composable
private fun MigrationSteps(exporting: Boolean, modifier: Modifier = Modifier) {
    MigrationPanel(modifier) {
        Text(if (exporting) "保存后怎么用" else "资源包导入步骤", style = MaterialTheme.typography.titleMedium)
        val steps = if (exporting) listOf("把保存的 ZIP 传到接收设备。",
            "接收设备安装官方游戏和 BYBUS，在「迁入」中选择这个包。", "登录游戏，按提示补齐更新资源。")
            else listOf("先安装官方游戏，无需打开原版下载资源。", "导入资源包，保留本机已有文件。", "导入后从 BYBUS 启动游戏，补齐版本差异。")
        steps.forEachIndexed { index, text ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${index + 1}", color = C.Accent, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        HorizontalDivider(color = C.Line)
        Text("资源包包含游戏下载的内容与缓存。账号在接收设备重新登录；汉化源在「汉化源与更新」中单独导入。",
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MigrationPrimaryButton(label: String, onClick: () -> Unit, glyph: Glyph, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        LauncherGlyph(glyph, LocalContentColor.current, Modifier.size(20.dp))
    }
}

@Composable
private fun MigrationTransferCard(state: MigrationState, onFinish: () -> Unit, onCancelExport: () -> Unit,
    onCancelRootImport: () -> Unit) {
    val inspecting = state.operation == MigrationOperation.Inspect
    val exporting = state.operation == MigrationOperation.Export
    val hasProgress = state.total > 0 && (state.copied > 0 || state.progressFiles)
    val progress = if (hasProgress) (state.copied.toDouble() / state.total).toFloat().coerceIn(0f, 1f) else 0f
    MigrationPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(C.AccentSoft),
                contentAlignment = Alignment.Center) {
                LauncherGlyph(if (state.done) Glyph.Check else if (inspecting) Glyph.Apps else if (exporting) Glyph.Export else Glyph.Import,
                    C.Accent, Modifier.size(25.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(when {
                    state.done -> if (exporting) "资源包已保存" else "迁入完成"
                    inspecting -> "正在检测"
                    exporting -> "正在迁出"
                    else -> "正在迁入"
                }, style = MaterialTheme.typography.headlineSmall)
            }
        }
        if (state.done) {
            if (state.total > 0) Text(byteSize(state.total), style = MaterialTheme.typography.displaySmall)
            HorizontalDivider(color = C.Line)
            Text(when {
                exporting -> "本机数据已保留。把 ZIP 传到接收设备，在 BYBUS 的「数据迁移 → 迁入」中选择它。"
                state.operation == MigrationOperation.RootResources -> "原版和 BYBUS 已有资源均已保留。回到首页启动游戏，登录账号并补齐版本差异即可。"
                state.operation == MigrationOperation.Resources -> "已有资源和账号已保留。回到首页启动游戏，补齐版本差异即可。"
                else -> "旧版数据已保留。先确认游戏正常，再卸载旧版；账号如需授权，请重新登录。"
            }, style = MaterialTheme.typography.bodyMedium)
            MigrationPrimaryButton("返回 BYBUS", onFinish, Glyph.Check)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                if (hasProgress) {
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Medium)
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = C.Accent, trackColor = C.AccentSoft)
                } else LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = C.Accent, trackColor = C.AccentSoft)
                Text(state.message, style = MaterialTheme.typography.bodyMedium)
                if (hasProgress) Text(if (state.progressFiles) "${state.copied} / ${state.total} 个文件"
                    else "${byteSize(state.copied)} / ${byteSize(state.total)}",
                    style = MaterialTheme.typography.labelMedium, color = C.Muted)
            }
            if (exporting) {
                Text("请保持页面打开，完成后再传输资源包。", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onCancelExport, modifier = Modifier.fillMaxWidth()) { Text("取消迁出") }
            }
            if (state.operation == MigrationOperation.RootResources) {
                OutlinedButton(onClick = onCancelRootImport, modifier = Modifier.fillMaxWidth()) { Text("取消导入") }
            }
        }
    }
}

@Composable
private fun MigrationFailure(state: MigrationState) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LauncherGlyph(Glyph.Info, C.Error, Modifier.padding(top = 2.dp).size(20.dp))
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (state.pending) "迁移尚未完成" else "暂时无法继续", color = C.Error,
                    style = MaterialTheme.typography.titleSmall)
                Text(state.message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun byteSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0))
