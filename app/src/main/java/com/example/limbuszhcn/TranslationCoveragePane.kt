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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CoverageSummary(state: LauncherUiState, onOpen: () -> Unit, onCheck: () -> Unit) {
    val coverage = state.coverage
    val report = coverage.report
    val resultLabel = if (coverage.stale || coverage.error != null || coverage.loading) "上次结果" else "查看结果"
    DetailCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val openResult = if (report == null) Modifier else Modifier.clickable(
                role = Role.Button, onClickLabel = resultLabel, onClick = onOpen)
            Column(Modifier.weight(1f).then(openResult).heightIn(min = 48.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("汉化覆盖", modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleSmall)
                    if (report != null) Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                        Text(resultLabel, style = MaterialTheme.typography.labelMedium, color = C.Accent)
                        LauncherGlyph(Glyph.Chevron, C.Accent, Modifier.size(12.dp))
                    }
                }
                Text(when {
                    coverage.loading -> "正在对照本地文本"
                    coverage.error != null -> "本次检查未完成"
                    coverage.stale && report != null -> "当前汉化需要重新检查"
                    report != null && report.unreadable > 0 -> "${report.unreadable} 个文件未能检查"
                    report != null && report.total == 0 -> "已读文件中没有可检查的文本"
                    report != null && report.missing == 0 && report.review == 0 -> "${report.total} 条本地文本已覆盖"
                    report != null -> "${report.missing} 条未覆盖 · ${report.review} 条待核对"
                    else -> "还未检查本地游戏文本"
                }, color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (report != null) {
                ActionIconButton(Glyph.Refresh, "重新检查", onCheck, enabled = !state.busy, busy = coverage.loading)
            } else {
                TextButton(onCheck, enabled = !coverage.loading && !state.busy) {
                    Text(if (coverage.loading) "检查中" else "检查", style = MaterialTheme.typography.labelMedium)
                }
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DetailCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (coverage.stale) "上次检查结果" else "检查结果", style = MaterialTheme.typography.titleMedium)
                    if (report != null) Text(DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(report.checkedAt)) + if (coverage.stale) " · 待重新检查" else " · 已保存",
                        style = MaterialTheme.typography.labelSmall, color = C.Muted)
                }
                ActionIconButton(Glyph.Refresh, "重新检查", onCheck, enabled = !state.busy, busy = coverage.loading)
            }
            coverage.error?.let { Text(it, color = C.Error, style = MaterialTheme.typography.bodyMedium) }
            if (coverage.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (report != null) {
                Text(report.sourceName, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) { CoverageMetric("已覆盖", report.covered) }
                    Box(Modifier.weight(1f)) { CoverageMetric("未覆盖", report.missing) }
                    Box(Modifier.weight(1f)) { CoverageMetric("待核对", report.review) }
                }
                if (report.unreadable > 0) Text("${report.unreadable} 个文件未能读取，未计入结果。", color = C.Error, style = MaterialTheme.typography.bodySmall)
                Text("未覆盖：没有可用译文。待核对：译文仍含日文或韩文。", style = MaterialTheme.typography.bodySmall)
                val unavailable = report.missing - report.aiEligible + report.review - report.reviewEligible
                if (unavailable > 0) Text("其中 $unavailable 条因长度或格式限制暂不能自动翻译，不会提交给 AI。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (report != null) {
            if (report.aiEligible + report.reviewEligible > 0) DetailCard {
                Text("选择补译范围", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiScope.entries.forEach { item ->
                        val count = (if (item.missing) report.aiEligible else 0) + (if (item.review) report.reviewEligible else 0)
                        FilterChip(scope == item, { scope = item }, enabled = coverage.current && !state.busy && count > 0,
                            label = { Text("${item.label} $count") })
                    }
                }
                val count = (if (scope.missing) report.aiEligible else 0) + (if (scope.review) report.reviewEligible else 0)
                FilledTonalButton({ onTranslate(scope) }, enabled = count > 0 && coverage.current && !state.busy && !state.ai.hasPendingTask,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("设置 AI 翻译") }
            }
            if (report.details.isEmpty()) DetailCard {
                Text(when {
                    report.files == 0 && report.unreadable > 0 -> "本次没有读到游戏文本，暂时无法判断汉化覆盖情况。"
                    report.unreadable > 0 -> "已读文件未发现缺失，仍有文件未能检查。"
                    report.total == 0 -> "已读文件中没有可检查的文本。"
                    else -> "本次本地检查没有发现未覆盖文本。"
                }, style = MaterialTheme.typography.bodyMedium)
            }
            if (report.details.isNotEmpty()) DetailCard {
                report.details.groupBy { it.category }.entries.forEachIndexed { groupIndex, (category, files) ->
                    val isOpen = expanded == category
                    if (groupIndex > 0) HorizontalDivider(color = C.Line)
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = if (isOpen) null else category }
                        .heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(category, style = MaterialTheme.typography.titleSmall)
                            Text("未覆盖 ${files.sumOf { it.missing }} · 待核对 ${files.sumOf { it.review }}", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(18.dp).rotate(if (isOpen) 270f else 90f))
                    }
                    if (isOpen) {
                        // Bounded pages avoid huge clipped layers and thousands of laid-out texts.
                        files.drop(page * 3).take(3).forEach { file ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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
                            TextButton({ page-- }, enabled = page > 0) { Text("上一组", style = MaterialTheme.typography.labelMedium) }
                            Text("${page + 1} / ${(files.size + 2) / 3}", style = MaterialTheme.typography.labelMedium)
                            TextButton({ page++ }, enabled = (page + 1) * 3 < files.size) { Text("下一组", style = MaterialTheme.typography.labelMedium) }
                        }
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
