package com.example.limbuszhcn

import android.os.CancellationSignal
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.ui.theme.LauncherColors as C
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class LoadedCoveragePage(val filter: CoverageFilter, val page: Int, val result: Result<CoveragePage>)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CoverageEntryBrowser(report: CoverageReport, kind: CoverageKind?, onKind: (CoverageKind?) -> Unit,
    readEntries: CoveragePageReader): Unit = key(report.entryIndex, report.checkedAt) {
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable(kind, category, search) { mutableIntStateOf(0) }
    var expanded by rememberSaveable(kind, category, search, page) { mutableStateOf<Int?>(null) }
    val categories = remember(report.details) { report.details.map { it.category }.distinct().sorted() }
    val filter = CoverageFilter(kind, category, search)
    val reader by rememberUpdatedState(readEntries)
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val ref = report.entryIndex
    var loaded by remember { mutableStateOf<LoadedCoveragePage?>(null) }
    val cancel = remember(filter, page) { CancellationSignal() }
    DisposableEffect(cancel) { onDispose { cancel.cancel() } }
    LaunchedEffect(filter, page) {
        if (filter.term.isNotEmpty() && filter.term != loaded?.filter?.term) delay(150)
        val result = try {
            Result.success(withContext(Dispatchers.IO) {
                if (ref == null) report.legacyEntries().filteredPage(filter, page) else reader(ref, filter, page, cancel)
            })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
        loaded = LoadedCoveragePage(filter, page, result)
    }
    val current = loaded?.let { it.filter == filter && it.page == page } == true
    val visible = loaded?.result?.getOrNull()
    val legacy = ref == null
    var showFiltering by remember { mutableStateOf(false) }
    LaunchedEffect(current) {
        if (!current) delay(220)
        showFiltering = !current
    }
    Surface(Modifier.fillMaxWidth(), color = C.Background, shape = RoundedCornerShape(20.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("条目明细", Modifier.align(Alignment.CenterVertically).semantics { heading() },
                        style = MaterialTheme.typography.titleSmall)
                    if (current && visible != null) {
                        val count = if (legacy) "样例 ${visible.total} 条" else "${visible.total} 条"
                        Text(count, Modifier.align(Alignment.CenterVertically), color = C.Muted, style = MaterialTheme.typography.labelMedium)
                    } else if (showFiltering && !current && loaded != null) {
                        CircularProgressIndicator(Modifier.align(Alignment.CenterVertically).size(14.dp)
                            .semantics { contentDescription = "正在筛选条目" }, strokeWidth = 2.dp)
                    }
                }
                CoverageCategoryPicker(category, categories) {
                    category = it
                    focus.clearFocus()
                }
                IconButton({
                    if (searching) {
                        focus.clearFocus()
                        search = ""
                    }
                    searching = !searching
                }) {
                    LauncherGlyph(if (searching) Glyph.Close else Glyph.Search, C.Accent, Modifier.size(19.dp),
                        if (searching) "关闭条目搜索" else "打开条目搜索")
                }
            }
            if (searching) {
                LaunchedEffect(Unit) { searchFocus.requestFocus() }
                OutlinedTextField(search, { search = it.take(160) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(searchFocus)
                        .semantics { contentDescription = "搜索条目" },
                    placeholder = { Text(if (legacy) "搜索已存样例" else "文字、编号或游戏 ID", style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true, shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodyMedium,
                    leadingIcon = { LauncherGlyph(Glyph.Search, C.Muted, Modifier.size(18.dp)) },
                    trailingIcon = if (search.isEmpty()) null else {
                        { IconButton({ search = "" }) { LauncherGlyph(Glyph.Close, C.Muted, Modifier.size(16.dp), "清除搜索") } }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = C.Line, focusedBorderColor = C.Accent,
                        unfocusedContainerColor = C.Panel, focusedContainerColor = C.Panel))
            }
            CoverageStatusTabs(kind) {
                onKind(it)
                focus.clearFocus()
            }
            if (legacy) Text("旧报告仅保存样例，重新检查可查看完整明细。", Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
            when {
                !current && visible?.rows.isNullOrEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth())
                visible == null -> Text("明细暂时无法读取。可在页首重新检查。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                visible.total == 0 -> Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(if (legacy) "没有符合条件的已存样例" else "没有符合条件的条目", style = MaterialTheme.typography.bodyMedium)
                    if (kind != null || category != null || search.isNotEmpty()) TextButton({
                        onKind(null)
                        category = null
                        search = ""
                        searching = false
                        focus.clearFocus()
                    }) { Text("清除筛选", style = MaterialTheme.typography.labelMedium) }
                }
                else -> CoverageRows(visible, legacy, current, expanded,
                    onExpand = { expanded = if (expanded == it) null else it }, onPage = { page = it })
            }
        }
    }
}

@Composable
private fun CoverageCategoryPicker(selected: String?, categories: List<String>, onSelect: (String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TextButton({ menu = true }, modifier = Modifier.widthIn(max = 122.dp).semantics { contentDescription = "筛选内容类别" },
            contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(selected ?: "全部类别", Modifier.weight(1f, fill = false), maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = C.Accent)
            LauncherGlyph(Glyph.Chevron, C.Accent, Modifier.size(14.dp).rotate(90f))
        }
        DropdownMenu(menu, { menu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            for (option in listOf(null) + categories) {
                DropdownMenuItem(text = { Text(option ?: "全部类别") }, onClick = {
                    menu = false
                    onSelect(option)
                }, trailingIcon = { if (selected == option) LauncherGlyph(Glyph.Check, C.Accent, Modifier.size(16.dp)) })
            }
        }
    }
}

@Composable
private fun CoverageRows(result: CoveragePage, legacy: Boolean, enabled: Boolean, expanded: Int?,
    onExpand: (Int) -> Unit, onPage: (Int) -> Unit) {
    for ((group, rows) in result.rows.groupBy { it.kind to it.category }) {
        Text(group.second, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall)
        for ((i, row) in rows.withIndex()) {
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = C.Line)
            key(row.number) { CoverageEntryRow(row, legacy, expanded == row.number, enabled) { onExpand(row.number) } }
        }
    }
    if (result.pages > 1) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton({ onPage(result.page - 1) }, enabled = enabled && result.page > 0) { Text("上一页") }
            Text("${result.page + 1} / ${result.pages}", color = C.Muted, style = MaterialTheme.typography.labelMedium)
            TextButton({ onPage(result.page + 1) }, enabled = enabled && result.page + 1 < result.pages) { Text("下一页") }
        }
    }
}

@Composable
private fun CoverageStatusTabs(kind: CoverageKind?, onKind: (CoverageKind?) -> Unit) {
    val states = remember { listOf(null, CoverageKind.Missing, CoverageKind.Review) }
    val selected = states.indexOf(kind)
    TabRow(selectedTabIndex = selected, containerColor = C.Background, contentColor = C.Accent,
        divider = { HorizontalDivider(color = C.Line) },
        indicator = { tabs ->
            Box(Modifier.tabIndicatorOffset(tabs[selected]), contentAlignment = Alignment.BottomCenter) {
                Spacer(Modifier.size(width = 30.dp, height = 3.dp).background(C.Accent, RoundedCornerShape(2.dp)))
            }
        }) {
        for (option in states) {
            val label = option?.label ?: "全部"
            Tab(selected = option == kind, onClick = { onKind(option) },
                selectedContentColor = C.Accent, unselectedContentColor = C.Muted,
                modifier = Modifier.semantics {
                    contentDescription = if (option == null) "查看全部状态" else "只看$label"
                    option?.let { stateDescription = if (it == CoverageKind.Missing) "尚无译文" else "已有译文，仍含日文或韩文" }
                }, text = { Text(label, style = MaterialTheme.typography.titleSmall) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoverageEntryRow(row: CoverageEntry, legacy: Boolean, expanded: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val number = if (legacy) "样例 ${row.number}" else "#${row.number}"
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val angle by animateFloatAsState(if (expanded) 270f else 90f, tween(155), label = "entry disclosure")
    Column {
        Column(Modifier.fillMaxWidth().clickable(interactionSource = interaction, indication = null, enabled = enabled,
            role = Role.Button, onClickLabel = if (expanded) "收起条目" else "展开条目", onClick = onClick)
            .semantics { stateDescription = row.kind.label + if (expanded) "，已展开" else "，已收起" }
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(number, Modifier.align(Alignment.CenterVertically), color = C.Accent, style = MaterialTheme.typography.labelMedium)
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
                Box(Modifier.size(26.dp).then(if (focused || pressed) Modifier.background(C.AccentSoft, RoundedCornerShape(50)) else Modifier),
                    contentAlignment = Alignment.Center) {
                    LauncherGlyph(Glyph.Chevron, if (expanded || pressed || focused) C.Accent else C.Muted, Modifier.size(15.dp).rotate(angle))
                }
            }
            if (!expanded) {
                Text(row.text.take(320), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                row.translation?.let { Text("现有译文  " + it.take(320), color = C.Muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (expanded) SelectionContainer {
            val guide = C.Line
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).drawBehind {
                val stroke = 2.dp.toPx()
                val x = if (layoutDirection == LayoutDirection.Ltr) stroke / 2 else size.width - stroke / 2
                drawLine(guide, Offset(x, stroke / 2), Offset(x, size.height - stroke / 2), strokeWidth = stroke, cap = StrokeCap.Round)
            }.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
