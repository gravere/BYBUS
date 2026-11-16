package com.example.limbuszhcn

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.limbuszhcn.ui.theme.LauncherColors as C

internal data class TranslationActions(
    val select: (String) -> Unit = {}, val import: (String?) -> Unit = {},
    val icon: (String) -> Unit = {}, val resetIcon: (String) -> Unit = {},
    val rename: (String, String) -> Unit = { _, _ -> }, val remove: (String) -> Unit = {},
    val export: (TranslationSource) -> Unit = {},
    val saveAi: (AiConfig, String, Boolean) -> Unit = { _, _, _ -> },
    val generateAi: (AiConfig, String) -> Unit = { _, _ -> }, val cancelAi: () -> Unit = {}, val clearKey: () -> Unit = {},
    val loadModels: (String, String) -> Unit = { _, _ -> }, val clearModels: () -> Unit = {}
)

@Composable
internal fun TranslationSourceIcon(source: TranslationSource, modifier: Modifier = Modifier) {
    val image by produceState<Pair<String?, ImageBitmap>?>(null, source.icon) {
        value = withContext(Dispatchers.IO) { source.icon?.substringBefore('#')?.let {
            runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap()?.let { bitmap -> source.icon to bitmap } }.getOrNull()
        } }
    }
    Box(modifier.size(40.dp), contentAlignment = Alignment.Center) {
        val current = image?.takeIf { it.first == source.icon }?.second
        if (current != null) Image(current, source.name, Modifier.fillMaxSize())
        else if (source.isOfficial) ZeroAssociationIcon(Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(C.AccentSoft),
            contentAlignment = Alignment.Center) {
            LauncherGlyph(if (source.kind == "ai") Glyph.Ai else Glyph.Translation, C.Accent, Modifier.size(23.dp))
        }
    }
}

@Composable
internal fun TranslationSourcesPane(state: LauncherUiState, actions: TranslationActions, onProject: () -> Unit = {}, onAi: () -> Unit) {
    val selected = state.sources.selected
    var select by remember { mutableStateOf<String?>(null) }
    var remove by remember(selected.id) { mutableStateOf(false) }
    var rename by remember(selected.id) { mutableStateOf(false) }
    var resetIcon by remember(selected.id) { mutableStateOf(false) }
    var name by remember(selected.id) { mutableStateOf(selected.name) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TranslationSourceIcon(selected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(selected.name, style = MaterialTheme.typography.titleMedium)
                Text(if (selected.isOfficial) state.translation?.version ?: if (state.sources.officialDownloaded) "已下载" else "启动时下载"
                    else selected.label, color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        DetailActionSection("汉化源") {
            Column(Modifier.selectableGroup()) {
            state.sources.sources.forEachIndexed { index, source ->
                if (index > 0) DetailActionDivider()
                val action = if (source.isOfficial) Modifier.clickable(role = Role.Button, onClick = onProject)
                    else Modifier.selectable(selected = selected.id == source.id, enabled = !state.busy,
                        role = Role.RadioButton, onClick = { if (selected.id != source.id) select = source.id })
                Row(Modifier.fillMaxWidth().then(action).heightIn(min = 64.dp).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TranslationSourceIcon(source)
                    Column(Modifier.weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.titleSmall)
                        Text(source.label, color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.animation.Crossfade(selected.id == source.id, modifier = Modifier.size(20.dp),
                        animationSpec = androidx.compose.animation.core.tween(140), label = "source selection") { checked ->
                        if (checked) LauncherGlyph(Glyph.Check, C.Accent, Modifier.fillMaxSize())
                    }
                    if (source.isOfficial) LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(17.dp), "查看零协项目状态")
                }
            }
            }
        }
        DetailActionSection("添加") {
            SheetAction(Glyph.Import, "导入汉化包", !state.busy, onClick = { actions.import(null) })
            DetailActionDivider()
            SheetAction(Glyph.Ai, "AI 翻译", navigation = true, onClick = onAi)
        }
        DetailActionSection("管理当前汉化源") {
            SheetAction(Glyph.Image, "更换图标", !state.busy, onClick = { actions.icon(selected.id) })
            if (selected.icon != null) {
                DetailActionDivider()
                SheetAction(Glyph.Refresh, "恢复默认图标", !state.busy, onClick = { resetIcon = true })
            }
            if (!selected.isOfficial) {
                DetailActionDivider()
                SheetAction(Glyph.Edit, "修改名称", !state.busy, onClick = { name = selected.name; rename = true })
                DetailActionDivider()
                SheetAction(Glyph.Sync, "替换文件", !state.busy, onClick = { actions.import(selected.id) })
            }
            if (selected.kind == "ai") {
                DetailActionDivider()
                SheetAction(Glyph.Ai, "继续 AI 翻译", !state.busy, navigation = true, onClick = onAi)
            }
            DetailActionDivider()
            SheetAction(Glyph.Export, "导出汉化包", !state.busy && (!selected.isOfficial || state.sources.officialDownloaded),
                onClick = { actions.export(selected) })
            DetailActionDivider()
            SheetAction(Glyph.Delete, if (selected.isOfficial) "卸载汉化文件" else "删除此汉化源",
                !state.busy && (!selected.isOfficial || state.sources.officialDownloaded || state.translation != null),
                onClick = { remove = true })
        }
        state.sources.message?.let { Text(it, color = C.Muted, style = MaterialTheme.typography.bodySmall) }
        if (state.failed && !state.busy) Text(state.message, color = C.Error, style = MaterialTheme.typography.bodyMedium)
    }
    state.sources.sources.firstOrNull { it.id == select }?.let { source ->
        TranslationSourceSwitchDialog(source, !state.busy, { select = null }) {
            select = null; actions.select(source.id)
        }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text(if (selected.isOfficial) "卸载汉化文件？" else "删除此汉化源？") },
        text = { Text(if (selected.isOfficial) "将关闭游戏并移除零协汉化文件，保留来源入口，下次使用时会重新下载。游戏资源、账号和其他汉化源会保留。"
            else "将关闭游戏并删除“${selected.name}”，切回都市零协会。重新使用需再次导入；游戏资源、账号和其他汉化源会保留。") },
        confirmButton = { TextButton({ remove = false; actions.remove(selected.id) }, enabled = !state.busy,
            colors = ButtonDefaults.textButtonColors(contentColor = C.Error)) { Text(if (selected.isOfficial) "卸载" else "删除") } },
        dismissButton = { TextButton({ remove = false }) { Text("取消") } })
    if (resetIcon) AlertDialog(onDismissRequest = { resetIcon = false }, title = { Text("恢复默认图标？") },
        text = { Text("将移除此来源的自定义图标，汉化内容会保留。") },
        confirmButton = { TextButton({ resetIcon = false; actions.resetIcon(selected.id) }, enabled = !state.busy) { Text("恢复") } },
        dismissButton = { TextButton({ resetIcon = false }) { Text("取消") } })
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("汉化名称") },
        text = { OutlinedTextField(name, { if (it.length <= 64) name = it }, singleLine = true) },
        confirmButton = { TextButton({ rename = false; actions.rename(selected.id, name) }, enabled = !state.busy && name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton({ rename = false }) { Text("取消") } })
}

@Composable
internal fun TranslationSourceSwitchDialog(source: TranslationSource, enabled: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("切换汉化源？") },
        text = { Text("将关闭游戏，下次启动使用“${source.name}”。游戏资源和账号会保留。") },
        confirmButton = { TextButton(onConfirm, enabled = enabled) { Text("切换") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
