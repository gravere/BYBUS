package com.example.limbuszhcn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun AiEntriesPane(state: LauncherUiState, actions: TranslationActions, onBaseUpdate: (String) -> Unit = {}, onTranslate: (AiEntryRequest) -> Unit) {
    val library = state.ai.library
    val report = library.report
    val enabled = !state.busy && !state.ai.hasPendingTask && !library.loading && !library.stale
    var kind by remember(report?.key) { mutableStateOf(if (report?.sourceId == null) AiEntryKind.Pending else AiEntryKind.Ai) }
    var category by remember(report?.key) { mutableStateOf<String?>(null) }
    var search by remember(report?.key) { mutableStateOf("") }
    var query by remember(report?.key) { mutableStateOf("") }
    var page by remember(report?.key, kind, category, query) { mutableIntStateOf(0) }
    var expanded by remember(report?.key) { mutableStateOf<String?>(null) }
    val selected = remember(report?.key) { mutableStateMapOf<String, AiEntryKind>() }
    var visible by remember(report?.key) { mutableStateOf<AiEntryPage?>(null) }
    var reading by remember { mutableStateOf(false) }
    var loaded by remember(report?.key) { mutableStateOf<Pair<AiEntryFilter, Int>?>(null) }
    var readError by remember(report?.key) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<AiEntryRequest?>(null) }
    var usePack by remember { mutableStateOf(false) }
    var downloadOriginals by remember { mutableStateOf(false) }
    val filter = AiEntryFilter(kind, category, query)
    val current = loaded == (filter to page)
    LaunchedEffect(search) { delay(160); query = search.trim() }
    LaunchedEffect(report?.key, filter, page, library.loading) {
        if (report == null || library.loading) return@LaunchedEffect
        reading = true; readError = null
        val cancel = android.os.CancellationSignal()
        try {
            visible = withContext(Dispatchers.IO) { actions.aiEntries(report, filter, page, cancel) }
            loaded = filter to page
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { visible = null; readError = "条目已变化，请重新读取" }
        finally { cancel.cancel(); reading = false }
    }
    fun chosen(row: AiEntry) = requireNotNull(report).let { AiEntryRequest(it, ids = setOf(row.id), count = 1) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DetailCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(report?.name ?: "AI 翻译条目", style = MaterialTheme.typography.titleSmall)
                    report?.let { Text("${it.language.displayName} · AI 已译 ${it.aiCount} 条", color = C.Accent, style = MaterialTheme.typography.bodyMedium) }
                }
                if (library.loading) LauncherSpinner(Modifier.size(20.dp)) else ActionIconButton(Glyph.Refresh, "重新读取条目",
                    { actions.inspectAiEntries(library.sourceId, true) }, enabled = !state.busy)
            }
            if (report?.sourceId == null) Text("从游戏原文开始翻译，无需现成汉化包。可按类别或一次选择全部生成。", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            if (library.loading) OperationProgress(state)
            library.error?.let { Text(it, color = C.Error, style = MaterialTheme.typography.bodySmall) }
            if (report == null && library.sourceId == null && !library.loading) FilledTonalButton({ downloadOriginals = true },
                enabled = !state.busy && !state.ai.hasPendingTask) { Text("下载游戏原文") }
            if (report != null && "公告与邮件" !in report.categories) Text("公告：先在游戏内打开，再刷新此页读取正文。", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            if (library.stale && library.error == null) Text("汉化包已变化，请重新读取条目", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            if (report != null && report.unreadable > 0) Text("${report.unreadable} 个文件暂时无法读取，未计入条目。", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            if (report?.sourceId != null && report.sourceId != state.sources.selectedId)
                FilledTonalButton({ usePack = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("使用此汉化包", style = MaterialTheme.typography.labelLarge) }
        }
        report?.sourceId?.let { id -> state.sources.sources.find { it.id == id && !it.independent && it.baseId != "global" }?.let {
            FilledTonalButton({ onBaseUpdate(id) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("检查原包更新") }
        } }
        if (report != null) DetailCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("条目明细", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                CoverageCategoryPicker(category, report.categories) { category = it }
            }
            SourceTabs(AiEntryKind.entries.toList(), kind, { "${it.label} ${report.counts[it] ?: 0}" }, "译文类型", { kind = it })
            OutlinedTextField(search, { search = it.take(128) }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜索文字、编号或文件", style = MaterialTheme.typography.bodyMedium) },
                textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(14.dp),
                leadingIcon = { LauncherGlyph(Glyph.Search, C.Muted, Modifier.size(18.dp)) })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selected.isEmpty()) "${visible?.total ?: 0} 条" else "已选 ${selected.size} 条",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton({ visible?.rows?.forEach { if (selected.size < 1000) selected[it.id] = it.kind } }, enabled = enabled && !reading && current) {
                    Text("选本页", style = MaterialTheme.typography.labelMedium)
                }
                if (selected.isNotEmpty()) TextButton({ selected.clear() }) { Text("清空", style = MaterialTheme.typography.labelMedium) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({
                    val request = if (selected.isNotEmpty()) AiEntryRequest(report, ids = selected.keys.toSet(), count = selected.size)
                        else AiEntryRequest(report, filter, count = requireNotNull(visible).total)
                    onTranslate(request)
                }, enabled = enabled && !reading && current && (selected.isNotEmpty() || (visible?.total ?: 0) > 0), modifier = Modifier.weight(1f)) {
                    Text(if (selected.isEmpty()) "翻译全部已筛选条目" else "翻译所选", style = MaterialTheme.typography.labelMedium)
                }
                if (selected.isNotEmpty()) OutlinedButton({ deleting = AiEntryRequest(report, ids = selected.keys.toSet(), count = selected.size) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Error),
                    enabled = enabled && selected.values.all { it == AiEntryKind.Ai }) { Text("删除", style = MaterialTheme.typography.labelLarge) }
            }
            readError?.let { Text(it, color = C.Error, style = MaterialTheme.typography.bodySmall) }
            if (reading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!reading && visible?.total == 0) Text("此范围暂无条目", style = MaterialTheme.typography.bodyMedium, color = C.Muted)
            visible?.rows?.forEach { row ->
                HorizontalDivider(color = C.Line)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { expanded = if (expanded == row.id) null else row.id }.padding(vertical = 6.dp)) {
                        Text("#${row.number} · ${row.category}", color = C.Accent, style = MaterialTheme.typography.labelSmall)
                        Text(row.translation ?: row.original, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Checkbox(row.id in selected, { checked ->
                        if (!checked) selected.remove(row.id) else if (selected.size < 1000) selected[row.id] = row.kind
                    }, enabled = enabled && !reading && current)
                }
                AnimatedVisibility(expanded == row.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("原文", color = C.Muted, style = MaterialTheme.typography.labelSmall)
                        Text(row.original, style = MaterialTheme.typography.bodyMedium)
                        row.translation?.let {
                            Text(row.kind.label, color = C.Muted, style = MaterialTheme.typography.labelSmall)
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(row.recordId?.let { "编号 $it · " }.orEmpty() + row.resource, color = C.Muted, style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ onTranslate(chosen(row)) }, enabled = enabled && !reading && current) {
                                Text(if (row.kind == AiEntryKind.Pending) "翻译此条" else "重译此条", style = MaterialTheme.typography.labelMedium)
                            }
                            if (row.kind == AiEntryKind.Ai) TextButton({ deleting = chosen(row) }, enabled = enabled && !reading && current) {
                                Text("删除译文", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            visible?.let { result -> if (result.pages > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton({ page = result.page - 1 }, enabled = !reading && result.page > 0) { Text("上一页") }
                Text("${result.page + 1} / ${result.pages}", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                TextButton({ page = result.page + 1 }, enabled = !reading && result.page + 1 < result.pages) { Text("下一页") }
            } }
        }
    }
    deleting?.let { request -> BybusDialog(destructive = true, onDismissRequest = { deleting = null }, title = { Text("删除 ${request.count} 条 AI 译文？") },
        text = { Text("只移除所选 AI 译文，未有其他译文时将显示游戏原文。若包内不再有中文译文，会移除此包。API 费用不会返还。") },
        confirmButton = { TextButton({ deleting = null; actions.deleteEntries(request) }, enabled = enabled) { Text("删除") } },
        dismissButton = { TextButton({ deleting = null }) { Text("取消") } }) }
    if (downloadOriginals) BybusDialog(onDismissRequest = { downloadOriginals = false }, title = { Text("进入游戏下载原文？") },
        text = { Text("本次以游戏原文启动，不下载汉化包。完成游戏资源下载后，返回这里读取原文，再开始 AI 翻译。已有汉化包会保留。") },
        confirmButton = { TextButton({ downloadOriginals = false; actions.downloadAiOriginals() }, enabled = !state.busy) { Text("进入游戏") } },
        dismissButton = { TextButton({ downloadOriginals = false }) { Text("取消") } })
    if (usePack && report?.sourceId != null) BybusDialog(onDismissRequest = { usePack = false }, title = { Text("使用此汉化包？") },
        text = { Text("将关闭游戏并切换汉化，下次启动时生效。") },
        confirmButton = { TextButton({ usePack = false; actions.select(report.sourceId) }, enabled = enabled) { Text("使用") } },
        dismissButton = { TextButton({ usePack = false }) { Text("取消") } })
}
