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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class TranslationActions(
    val select: (String) -> Unit = {}, val import: (String?) -> Unit = {},
    val icon: (String) -> Unit = {}, val resetIcon: (String) -> Unit = {},
    val rename: (String, String) -> Unit = { _, _ -> }, val remove: (String) -> Unit = {},
    val export: (TranslationSource) -> Unit = {},
    val inspectImport: (String) -> Unit = {}, val chooseImportFill: (ImportFillChoice) -> Unit = {},
    val saveUpdateLink: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val restoreImported: (String) -> Unit = {},
    val saveAi: (AiConfig, String, Boolean) -> Unit = { _, _, _ -> },
    val generateAi: (AiConfig, String) -> Unit = { _, _ -> }, val cancelAi: () -> Unit = {},
    val saveKey: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    val selectKey: (String) -> Unit = {}, val clearKey: (String) -> Unit = {},
    val renameKey: (String, String) -> Unit = { _, _ -> }, val reorderKeys: (List<String>) -> Unit = {},
    val pauseAi: () -> Unit = {}, val resumeAi: () -> Unit = {},
    val loadModels: (String, String) -> Unit = { _, _ -> }, val clearModels: () -> Unit = {},
    val inspectCoverage: () -> Unit = {}, val undoAi: (AiUndoPoint) -> Unit = {}, val removeAi: (String) -> Unit = {},
    val chooseAiUpdate: (com.example.limbuszhcn.translation.AiUpdateChoice?) -> Unit = {},
    val savePrompt: (String) -> Unit = {},
    val coverageEntries: CoveragePageReader = { _, _, _, _ -> error("无法读取明细") },
    val coverageCount: CoverageCountReader = { _, _, _, _ -> 0 },
    val generateCoverage: (AiConfig, String, CoverageRequest) -> Unit = { _, _, _ -> },
    val inspectAiEntries: (String?, Boolean) -> Unit = { _, _ -> },
    val downloadAiOriginals: () -> Unit = {},
    val aiEntries: AiEntryReader = { _, _, _, _ -> error("无法读取明细") },
    val generateEntries: (AiConfig, String, AiEntryRequest) -> Unit = { _, _, _ -> },
    val deleteEntries: (AiEntryRequest) -> Unit = {},
    val loadPrice: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val savePrice: (String, String, AiPrice?) -> Unit = { _, _, _ -> },
    val inspectBaseUpdate: (String) -> Unit = {}, val applyBaseUpdate: (AiBaseRequest) -> Unit = {},
    val baseChanges: (AiBaseReport, AiBaseChangeKind?, Int, android.os.CancellationSignal) -> AiBasePage = { _, _, _, _ -> error("明细尚未就绪") }
)

private val packageDateFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

internal fun LauncherUiState.packageTimeLabel(source: TranslationSource): String? {
    val action: String
    val time: Long
    if (source.isOfficial) {
        val activeVersion = if (sources.selected.isOfficial)
            (translation ?: resourcePreparation)?.version?.toLongOrNull() else null
        val recorded = sources.officialDownloadTimes
        time = (if (activeVersion == null) recorded.values.maxOrNull() else recorded[activeVersion]) ?: return null
        action = if (activeVersion == null) "最近下载" else "下载于"
    } else {
        time = source.updatedAtMillis
        action = if (source.kind == "ai") "生成于" else "添加于"
        if (time <= 0) return if (source.kind == "ai") "生成时间未记录" else "添加时间未记录"
    }
    if (time <= 0) return null
    val localTime = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    return "$action ${packageDateFormat.format(localTime)}"
}

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
internal fun TranslationSourcesPane(state: LauncherUiState, actions: TranslationActions, onProject: () -> Unit = {}, onCoverage: () -> Unit = {}, onBaseUpdate: (String) -> Unit = {}, onAi: () -> Unit) {
    val selected = state.sources.selected
    var select by remember { mutableStateOf<String?>(null) }
    var remove by remember(selected.id) { mutableStateOf(false) }
    var rename by remember(selected.id) { mutableStateOf(false) }
    var resetIcon by remember(selected.id) { mutableStateOf(false) }
    var updateLink by remember(selected.id) { mutableStateOf(false) }
    var restoreImported by remember(selected.id) { mutableStateOf(false) }
    var name by remember(selected.id) { mutableStateOf(selected.name) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.busy && state.taskPage == "translation") DetailCard {
            OperationProgress(state)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TranslationSourceIcon(selected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(selected.name, style = MaterialTheme.typography.titleMedium)
                Text(if (selected.isOfficial) state.translation?.version ?: if (state.sources.officialDownloaded) "已下载" else "启动时下载"
                    else selected.label, color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        CoverageSummary(state, onCoverage, actions.inspectCoverage)
        if (selected.kind == "local") DetailActionSection("导入包设置") {
            SheetAction(Glyph.Translation, "补齐未覆盖文本", !state.busy, onClick = { actions.inspectImport(selected.id) })
            DetailActionDivider()
            SheetAction(Glyph.Globe, if (selected.updateLink == null) "添加更新链接" else "管理更新链接", !state.busy,
                onClick = { updateLink = true })
            if (selected.supplement != null) {
                DetailActionDivider()
                SheetAction(Glyph.File, "恢复导入原包", !state.busy, onClick = { restoreImported = true })
            }
        }
        sourceFamilies(state.sources.sources).forEach { family ->
            DetailActionSection(if (family.base == null) family.name else "汉化源") {
                family.base?.let { source -> SourceSelectionRow(source, state, onProject) {
                    if (selected.id != source.id) select = source.id
                } }
                if (family.versions.isNotEmpty()) {
                    if (family.base != null) DetailActionDivider()
                    SourceVersionDisclosure(family.id, "AI 版本 · ${family.versions.size}") {
                        family.versions.forEach { source ->
                            SourceSelectionRow(source, state, onProject) { if (selected.id != source.id) select = source.id }
                        }
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
                SheetAction(Glyph.Ai, "继续 AI 翻译", navigation = true, onClick = onAi)
                if (!selected.independent && selected.baseId != "global") {
                    DetailActionDivider()
                    SheetAction(Glyph.Sync, "更新原包译文", state.canEditAiTranslations, navigation = true, onClick = { onBaseUpdate(selected.id) })
                }
            }
            DetailActionDivider()
            SheetAction(Glyph.Export, "导出汉化包", !state.busy && (!selected.isOfficial || state.sources.officialDownloaded),
                onClick = { actions.export(selected) })
            DetailActionDivider()
            SheetAction(Glyph.Delete, if (selected.isOfficial) "卸载汉化文件" else "删除此汉化源",
                !state.busy && (!selected.isOfficial || state.sources.officialDownloaded || state.translation != null),
                destructive = true, onClick = { remove = true })
        }
        state.sources.message?.let { Text(it, color = C.Muted, style = MaterialTheme.typography.bodySmall) }
        if (state.failed && !state.busy) Text(state.message, color = C.Error, style = MaterialTheme.typography.bodyMedium)
    }
    if (updateLink) SourceUpdateLinkDialog(selected, !state.busy, { updateLink = false }) { url, automatic ->
        updateLink = false; actions.saveUpdateLink(selected.id, url, automatic)
    }
    if (restoreImported) BybusDialog(onDismissRequest = { restoreImported = false }, title = { Text("恢复导入原包？") },
        text = { Text("将关闭游戏，移除此包中零协补齐的内容。导入的原始译文、图标和更新链接会保留。") },
        confirmButton = { TextButton({ restoreImported = false; actions.restoreImported(selected.id) }, enabled = !state.busy) { Text("恢复") } },
        dismissButton = { TextButton({ restoreImported = false }) { Text("取消") } })
    state.sources.sources.firstOrNull { it.id == select }?.let { source ->
        TranslationSourceSwitchDialog(source, !state.busy, { select = null }) {
            select = null; actions.select(source.id)
        }
    }
    if (remove) BybusDialog(destructive = true, onDismissRequest = { remove = false }, title = { Text(if (selected.isOfficial) "卸载汉化文件？" else "删除此汉化源？") },
        text = { Text(if (selected.isOfficial) "将关闭游戏并移除零协汉化文件，保留来源入口，下次使用时会重新下载。游戏资源、账号和其他汉化源会保留。"
            else "将关闭游戏并删除“${selected.name}”，切回都市零协会。重新使用需再次导入；游戏资源、账号和其他汉化源会保留。") },
        confirmButton = { TextButton({ remove = false; actions.remove(selected.id) }, enabled = !state.busy,
            colors = ButtonDefaults.textButtonColors(contentColor = C.Error)) { Text(if (selected.isOfficial) "卸载" else "删除") } },
        dismissButton = { TextButton({ remove = false }) { Text("取消") } })
    if (resetIcon) BybusDialog(onDismissRequest = { resetIcon = false }, title = { Text("恢复默认图标？") },
        text = { Text("将移除此来源的自定义图标，汉化内容会保留。") },
        confirmButton = { TextButton({ resetIcon = false; actions.resetIcon(selected.id) }, enabled = !state.busy) { Text("恢复") } },
        dismissButton = { TextButton({ resetIcon = false }) { Text("取消") } })
    if (rename) BybusDialog(onDismissRequest = { rename = false }, title = { Text("汉化名称") },
        text = { OutlinedTextField(name, { if (it.length <= 64) name = it }, singleLine = true) },
        confirmButton = { TextButton({ rename = false; actions.rename(selected.id, name) }, enabled = !state.busy && name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton({ rename = false }) { Text("取消") } })
}

@Composable
internal fun TranslationSourceSwitchDialog(source: TranslationSource, enabled: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    BybusDialog(onDismissRequest = onDismiss, title = { Text("切换汉化源？") },
        text = { Text("将关闭游戏，下次启动使用“${source.name}”。游戏资源和账号会保留。") },
        confirmButton = { TextButton(onConfirm, enabled = enabled) { Text("切换") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
