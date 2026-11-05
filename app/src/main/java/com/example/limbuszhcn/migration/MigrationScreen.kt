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
import com.example.limbuszhcn.ExpandableDetails
import com.example.limbuszhcn.IconTile
import com.example.limbuszhcn.LauncherGlyph
import com.example.limbuszhcn.ui.theme.LauncherColors as C

/** Presentation only: source ownership, copying and recovery stay in MigrationModel. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MigrationScreen(state: MigrationState, onBack: () -> Unit, onRefresh: () -> Unit,
    onMigrate: () -> Unit, onSelectFile: () -> Unit, onOpenLegacy: () -> Unit, onFinish: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = C.Background, contentColor = C.Ink) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            val wide = maxWidth >= 840.dp && maxWidth > maxHeight && LocalDensity.current.fontScale <= 1.3f
            val inset = if (maxWidth < 400.dp) 20.dp else 28.dp
            Column(Modifier.widthIn(max = if (wide) 960.dp else 640.dp).fillMaxSize()
                .padding(horizontal = inset)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, enabled = !state.busy && !state.pending,
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(C.Panel)) {
                        LauncherGlyph(Glyph.Chevron, if (state.busy || state.pending) C.Line else C.Ink,
                            Modifier.size(21.dp).rotate(180f), "返回上一页")
                    }
                    Text("数据迁移", Modifier.weight(1f).padding(horizontal = 16.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge)
                    if (!state.done) IconButton(onClick = onRefresh, enabled = !state.busy,
                        modifier = Modifier.size(48.dp)) {
                        LauncherGlyph(Glyph.Refresh, if (state.busy) C.Line else C.Muted,
                            Modifier.size(21.dp), "重新检测")
                    }
                }
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(state.busy, state.done, state.failed, state.pending, state.operation) {
                        scroll.scrollTo(0)
                    }
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)
                        .padding(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (state.busy || state.done) {
                            MigrationTransferCard(state, onFinish)
                        } else {
                            Text(if (state.pending) "继续上次迁移" else "选择数据来源",
                                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                            if (state.failed) MigrationFailure(state)
                            if (wide && !state.pending) {
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    LegacySourceCard(state, onMigrate, onOpenLegacy,
                                        Modifier.weight(1f).fillMaxHeight(), stretch = true)
                                    ResourceSourceCard(onSelectFile, Modifier.weight(1f).fillMaxHeight(), stretch = true)
                                }
                            } else {
                                LegacySourceCard(state, onMigrate, onOpenLegacy)
                                if (!state.pending) ResourceSourceCard(onSelectFile)
                            }
                            MigrationNotes(state.pending)
                        }
                    }
                }
            }
        }
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
                Text("旧版 BYBUS", style = MaterialTheme.typography.titleMedium)
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
                state.ready -> "游戏资源与账号配置"
                state.hasLegacy -> "在旧版完成一次覆盖更新，再回来检测。"
                else -> "安装着旧版时，这里会自动识别。"
            }, style = MaterialTheme.typography.bodyMedium, color = C.Muted)
        }
        if (state.ready) Text("新版同名数据将被替换，旧版数据保留。",
            style = MaterialTheme.typography.bodySmall, color = C.Muted)
        if (stretch) Spacer(Modifier.weight(1f))
        if (state.ready) {
            MigrationPrimaryButton(if (state.pending) "继续迁移" else "开始迁移", onMigrate, Glyph.Import)
        } else if (state.hasLegacy) {
            MigrationPrimaryButton("打开旧版 BYBUS", onOpenLegacy, Glyph.External)
        }
    }
}

@Composable
private fun ResourceSourceCard(onSelectFile: () -> Unit, modifier: Modifier = Modifier, stretch: Boolean = false) {
    MigrationPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Glyph.File)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("资源文件", style = MaterialTheme.typography.titleMedium)
                Text("从已导出的 ZIP 导入", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
        }
        Text("补充资源，保留已有文件与账号。", style = MaterialTheme.typography.bodyMedium, color = C.Muted)
        if (stretch) Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = onSelectFile, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = C.AccentSoft, contentColor = C.OnAccentSoft),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            Text("选择资源文件", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            LauncherGlyph(Glyph.Folder, C.OnAccentSoft, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MigrationPrimaryButton(label: String, onClick: () -> Unit, glyph: Glyph) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        LauncherGlyph(glyph, MaterialTheme.colorScheme.onPrimary, Modifier.size(20.dp))
    }
}

@Composable
private fun MigrationTransferCard(state: MigrationState, onFinish: () -> Unit) {
    val inspecting = state.operation == MigrationOperation.Inspect
    val hasProgress = state.total > 0 && (state.copied > 0 || state.progressFiles)
    val progress = if (hasProgress) (state.copied.toDouble() / state.total).toFloat().coerceIn(0f, 1f) else 0f
    MigrationPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(C.AccentSoft),
                contentAlignment = Alignment.Center) {
                LauncherGlyph(if (state.done) Glyph.Check else if (inspecting) Glyph.Apps else Glyph.Import,
                    C.Accent, Modifier.size(25.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(when {
                    state.done -> "数据已就位"
                    inspecting -> "正在检测"
                    state.operation == MigrationOperation.Resources -> "正在导入资源"
                    else -> "正在迁移数据"
                }, style = MaterialTheme.typography.headlineSmall)
                Text(if (state.done) "导入完成" else if (inspecting) "查找可用的旧版数据" else "请保持此页面打开",
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.done) {
            if (state.total > 0) Text(byteSize(state.total), style = MaterialTheme.typography.displaySmall)
            HorizontalDivider(color = C.Line)
            Text(if (state.operation == MigrationOperation.Resources) "已有资源和账号已保留，源文件未改动。"
                else "旧版数据已保留，确认游戏正常后再卸载旧版。", color = C.Muted,
                style = MaterialTheme.typography.bodyMedium)
            Text(if (state.operation == MigrationOperation.Resources) "游戏会校验并补齐资源。"
                else "游戏会补齐资源；账号可能需要重新授权。", color = C.Muted,
                style = MaterialTheme.typography.bodySmall)
            MigrationPrimaryButton("进入 BYBUS", onFinish, Glyph.Arrow)
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
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                if (hasProgress) Text(if (state.progressFiles) "${state.copied} / ${state.total} 个文件"
                    else "${byteSize(state.copied)} / ${byteSize(state.total)}",
                    style = MaterialTheme.typography.labelMedium, color = C.Muted)
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
private fun MigrationNotes(pending: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LauncherGlyph(Glyph.Check, C.Muted, Modifier.size(16.dp))
            Text("来源数据会保留", Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = C.Muted)
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (expanded) "收起说明" else "迁移须知", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.padding(start = 4.dp).size(16.dp).rotate(if (expanded) 270f else 90f))
            }
        }
        ExpandableDetails(expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending) Text("请先完成上次迁移，再启动游戏。", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                Text("旧版迁移包含资源与账号配置；文件导入只补充缺少的资源。", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                Text("资源版本无需相同，进入游戏后会校验更新。", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
        }
    }
}

@Composable
private fun byteSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0))
