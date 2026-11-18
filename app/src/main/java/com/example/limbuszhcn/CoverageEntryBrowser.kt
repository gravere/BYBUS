package com.example.limbuszhcn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.ui.theme.LauncherColors as C
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CoverageEntryBrowser(report: CoverageReport, kind: CoverageKind?, onKind: (CoverageKind?) -> Unit,
    readEntries: CoveragePageReader): Unit = key(report.entryIndex, report.checkedAt) {
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable(kind, category, search) { mutableIntStateOf(0) }
    var expanded by rememberSaveable(kind, category, search, page) { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf(false) }
    val categories = remember(report.details) { report.details.map { it.category }.distinct().sorted() }
    val filter = CoverageFilter(kind, category, search)
    val reader by rememberUpdatedState(readEntries)
    val focus = LocalFocusManager.current
    val ref = report.entryIndex
    val result = key(filter, page) {
        val cancel = remember { android.os.CancellationSignal() }
        DisposableEffect(cancel) { onDispose { cancel.cancel() } }
        produceState<Result<CoveragePage>?>(null) {
            if (filter.term.isNotEmpty()) delay(180)
            value = try {
                Result.success(withContext(Dispatchers.IO) {
                    if (ref == null) report.legacyEntries().filteredPage(filter, page) else reader(ref, filter, page, cancel)
                })
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { Result.failure(e) }
        }.value
    }

    DetailCard {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("条目明细", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleSmall)
            Box {
                TextButton({ menu = true }, modifier = Modifier.semantics { contentDescription = "筛选内容类别" }) {
                    Text(category ?: "全部类别", style = MaterialTheme.typography.labelMedium)
                    LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(15.dp).rotate(90f))
                }
                DropdownMenu(menu, { menu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                    (listOf(null) + categories).forEach { option ->
                        DropdownMenuItem(text = { Text(option ?: "全部类别") }, onClick = { category = option; menu = false; focus.clearFocus() },
                            trailingIcon = { if (category == option) LauncherGlyph(Glyph.Check, C.Accent, Modifier.size(16.dp)) })
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(null, CoverageKind.Missing, CoverageKind.Review).forEach { option ->
                FilterChip(selected = kind == option, onClick = { onKind(option); focus.clearFocus() },
                    label = { Text(option?.label ?: "全部", style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.semantics { contentDescription = option?.let { "只看" + it.label } ?: "查看全部状态" })
            }
        }
        OutlinedTextField(search, { search = it.take(160) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索条目" },
            placeholder = { Text(if (ref == null) "搜索已存样例" else "文字、编号或游戏 ID", style = MaterialTheme.typography.bodyMedium) },
            singleLine = true, shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodyMedium,
            leadingIcon = { LauncherGlyph(Glyph.Search, C.Muted, Modifier.size(18.dp)) },
            trailingIcon = if (search.isEmpty()) null else {
                { IconButton({ search = "" }) { LauncherGlyph(Glyph.Close, C.Muted, Modifier.size(16.dp), "清除搜索") } }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = C.Line, focusedBorderColor = C.Accent,
                unfocusedContainerColor = C.Panel, focusedContainerColor = C.Panel))
        if (ref == null) Text("旧报告只保存了部分样例。重新检查一次，即可查看完整明细和条目编号。",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
        when (kind) {
            CoverageKind.Missing -> Text("未覆盖的条目还没有可用译文。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            CoverageKind.Review -> Text("待核对的条目已有译文，其中仍含日文或韩文。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            null -> Unit
        }
    }
    val visible = result?.getOrNull()
    when {
        result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        visible == null -> DetailCard { Text("明细暂时无法读取。可在页首重新检查。", style = MaterialTheme.typography.bodyMedium) }
        visible.total == 0 -> DetailCard {
            Text(if (ref == null) "没有符合条件的已存样例" else "没有符合条件的条目", style = MaterialTheme.typography.bodyMedium)
            if (kind != null || category != null || search.isNotEmpty()) TextButton({
                onKind(null); category = null; search = ""; focus.clearFocus()
            }) { Text("清除筛选", style = MaterialTheme.typography.labelMedium) }
        }
        else -> {
            Text(if (ref == null) "已存样例 · ${visible.total} 条" else "符合条件 · ${visible.total} 条",
                style = MaterialTheme.typography.labelMedium, color = C.Muted)
            for ((group, rows) in visible.rows.groupBy { it.kind to it.category }) {
                Surface(Modifier.fillMaxWidth(), color = C.Background, shape = RoundedCornerShape(20.dp)) {
                    Column {
                        Text(group.second, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.titleSmall)
                        for ((i, row) in rows.withIndex()) {
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = C.Line)
                            CoverageEntryRow(row, ref == null, expanded == row.number) { expanded = if (expanded == row.number) null else row.number }
                        }
                    }
                }
            }
            if (visible.pages > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton({ page = visible.page - 1 }, enabled = visible.page > 0) { Text("上一页") }
                Text("${visible.page + 1} / ${visible.pages}", color = C.Muted, style = MaterialTheme.typography.labelMedium)
                TextButton({ page = visible.page + 1 }, enabled = visible.page + 1 < visible.pages) { Text("下一页") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoverageEntryRow(row: CoverageEntry, legacy: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val number = if (legacy) "样例 ${row.number}" else "#${row.number}"
    Column {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "收起条目" else "展开条目", onClick = onClick)
            .semantics { stateDescription = row.kind.label + if (expanded) "，已展开" else "，已收起" }
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(number, Modifier.align(Alignment.CenterVertically), color = C.Accent, style = MaterialTheme.typography.labelLarge)
                    val missing = row.kind == CoverageKind.Missing
                    Surface(Modifier.align(Alignment.CenterVertically), shape = RoundedCornerShape(6.dp),
                        color = if (missing) MaterialTheme.colorScheme.errorContainer else C.AccentSoft) {
                        Text(row.kind.label, Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = if (missing) MaterialTheme.colorScheme.onErrorContainer else C.OnAccentSoft,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    row.recordId?.let { Text("游戏 ID $it", Modifier.align(Alignment.CenterVertically),
                        color = C.Muted, style = MaterialTheme.typography.labelMedium) }
                }
                LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(16.dp).rotate(if (expanded) 270f else 90f))
            }
            if (!expanded) {
                Text(row.text.take(320), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                row.translation?.let { Text("现有译文  " + it.take(320), color = C.Muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (expanded) SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                EntryText("原文", row.text)
                row.translation?.let { EntryText("现有译文", it) }
                if (row.note.isNotBlank()) Text(row.note, color = C.Muted, style = MaterialTheme.typography.bodySmall)
                Text(row.resource + row.pointer.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty(),
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EntryText(label: String, text: String) {
    val long = text.length > 12000
    Text(if (long) "${label}节选" else label, color = C.Muted, style = MaterialTheme.typography.labelSmall)
    Text(if (long) text.take(12000) + "…" else text, style = MaterialTheme.typography.bodyMedium)
}
