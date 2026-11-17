package com.example.limbuszhcn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.limbuszhcn.translation.AiScope
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun CoverageSummary(state: LauncherUiState, onOpen: () -> Unit) {
    val coverage = state.coverage; val report = coverage.report
    DetailCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("汉化覆盖", style = MaterialTheme.typography.titleSmall)
                Text(when {
                    coverage.loading -> "正在对照本地文本"
                    coverage.stale && report != null -> "资源已变动，上次结果待更新"
                    report != null -> "${report.missing} 条未覆盖 · ${report.review} 条待核对"
                    coverage.error != null -> "本次检查未完成"
                    else -> "还未检查本地游戏文本"
                }, color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onOpen, enabled = report != null || !state.busy && !coverage.loading) {
                Text(if (report != null) "查看结果" else if (coverage.loading) "检查中" else "检查")
            }
        }
        if (coverage.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        coverage.error?.let { Text(it, color = C.Error, style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TranslationCoveragePane(state: LauncherUiState, onCheck: () -> Unit, onTranslate: (AiScope) -> Unit = {}) {
    val coverage = state.coverage; val report = coverage.report
    var expanded by remember(report?.checkedAt) { mutableStateOf<String?>(null) }
    var page by remember(report?.checkedAt, expanded) { mutableIntStateOf(0) }
    var scope by remember { mutableStateOf(AiScope.Missing) }
    LaunchedEffect(report?.checkedAt) { if (report != null && report.aiEligible == 0 && report.reviewEligible > 0) scope = AiScope.Review }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DetailCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(report?.sourceName ?: state.sources.selected.name, style = MaterialTheme.typography.titleMedium)
                    if (report != null) Text(DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(report.checkedAt)), style = MaterialTheme.typography.labelSmall, color = C.Muted)
                }
                IconButton(onCheck, enabled = !coverage.loading && !state.busy) {
                    if (coverage.loading) LauncherSpinner(Modifier.size(20.dp))
                    else LauncherGlyph(Glyph.Refresh, C.Accent, Modifier.size(22.dp), "重新检查")
                }
            }
            coverage.error?.let { Text(it, color = C.Error, style = MaterialTheme.typography.bodyMedium) }
            if (coverage.stale) {
                Text("汉化或游戏文本已变动，以下保留上次结果。重新检查后再选择补译范围。", style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onCheck, enabled = !coverage.loading && !state.busy) { Text("重新检查") }
            }
            if (coverage.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (report != null) {
                Text("已对照 ${report.total} 条本地文本", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverageMetric("已覆盖", report.covered)
                    CoverageMetric("未覆盖", report.missing)
                    CoverageMetric("待核对", report.review)
                }
                if (report.shared > 0 || report.excluded > 0) Text("公共译文覆盖 ${report.shared} 条 · 已排除内部备注及未使用文本 ${report.excluded} 条",
                    style = MaterialTheme.typography.bodySmall)
                if (report.unreadable > 0) Text("${report.unreadable} 个文件未能读取，未计入结果。", color = C.Error, style = MaterialTheme.typography.bodySmall)
                Text("未覆盖表示本地未找到可用译文；待核对表示可能仍含原文。未下载内容不计入。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (report != null) {
            if (report.aiEligible + report.reviewEligible > 0) DetailCard {
                Text("交给 AI 处理", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiScope.entries.forEach { item ->
                        val count = (if (item.missing) report.aiEligible else 0) + (if (item.review) report.reviewEligible else 0)
                        FilterChip(scope == item, { scope = item }, enabled = !coverage.stale && !coverage.loading && !state.busy && count > 0,
                            label = { Text("${item.label} $count") })
                    }
                }
                val count = (if (scope.missing) report.aiEligible else 0) + (if (scope.review) report.reviewEligible else 0)
                FilledTonalButton({ onTranslate(scope) }, enabled = count > 0 && !coverage.stale && !coverage.loading && !state.busy && !state.ai.hasPendingTask,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("选择模型并确认") }
            }
            if (report.details.isEmpty()) DetailCard { Text(if (report.unreadable == 0) "本次本地检查没有发现未覆盖文本。" else "已读文件未发现缺失，仍有文件未能检查。") }
            report.details.groupBy { it.category }.forEach { (category, files) ->
                val isOpen = expanded == category
                DetailCard {
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = if (isOpen) null else category }
                        .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(category, style = MaterialTheme.typography.titleSmall)
                            Text("未覆盖 ${files.sumOf { it.missing }} · 待核对 ${files.sumOf { it.review }}", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(18.dp).rotate(if (isOpen) 270f else 90f))
                    }
                }
                if (isOpen) {
                    // Bounded pages avoid huge clipped layers and thousands of laid-out texts.
                    files.drop(page * 3).take(3).forEach { file ->
                        DetailCard {
                            Text(file.resource.removePrefix("jp/").replace("_", "_\u200b").replace("/", "/\u200b"),
                                modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium)
                            Text("未覆盖 ${file.missing} · 待核对 ${file.review}", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                            file.examples.forEach { sample ->
                                HorizontalDivider(color = C.Line)
                                Text(sample.reason, style = MaterialTheme.typography.labelMedium)
                                Text("原文示例", style = MaterialTheme.typography.labelSmall, color = C.Muted)
                                Text(sample.text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
                                sample.translation?.let {
                                    Text("现有译文", style = MaterialTheme.typography.labelSmall, color = C.Muted)
                                    Text(it, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    if (files.size > 3) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ page-- }, enabled = page > 0) { Text("上一组") }
                        Text("${page + 1} / ${(files.size + 2) / 3}", style = MaterialTheme.typography.labelMedium)
                        TextButton({ page++ }, enabled = (page + 1) * 3 < files.size) { Text("下一组") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverageMetric(label: String, count: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(count.toString(), style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = C.Muted)
    }
}
