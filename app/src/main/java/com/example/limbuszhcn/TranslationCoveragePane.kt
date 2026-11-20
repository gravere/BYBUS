package com.example.limbuszhcn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.limbuszhcn.translation.*
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
                    coverage.language?.let { Text("${it.displayName}原文", style = MaterialTheme.typography.labelMedium,
                        color = C.Muted, modifier = Modifier.align(Alignment.CenterVertically)) }
                    if (report != null) Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                        Text(resultLabel, style = MaterialTheme.typography.labelMedium, color = C.Accent)
                        LauncherGlyph(Glyph.Chevron, C.Accent, Modifier.size(12.dp))
                    }
                }
                Text(when {
                    coverage.loading -> "正在对照本地文本"
                    coverage.error != null -> "本次检查未完成"
                    coverage.stale -> "游戏文本或汉化包已变化，请重新检查"
                    report != null && report.unreadable > 0 -> "${report.unreadable} 个文件未能检查"
                    report != null && report.total == 0 -> "已读文件中没有可检查的文本"
                    report != null && report.missing == 0 && report.review == 0 -> "${report.total} 条本地文本已覆盖"
                    report != null -> "${report.missing} 条未覆盖 · ${report.review} 条待核对"
                    coverage.language == null -> "首次启动游戏后可检查"
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
internal fun TranslationCoveragePane(state: LauncherUiState, onCheck: () -> Unit,
    readEntries: CoveragePageReader, onTranslate: (AiScope) -> Unit = {}) {
    val coverage = state.coverage; val report = coverage.report
    var kind by rememberSaveable(report?.checkedAt, report?.entryIndex) { mutableStateOf<CoverageKind?>(null) }
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
                Text("${report.sourceName} · ${report.language.displayName}原文", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) { CoverageMetric("已覆盖", report.covered - report.review) }
                    Box(Modifier.weight(1f)) { CoverageMetric("未覆盖", report.missing) }
                    Box(Modifier.weight(1f)) { CoverageMetric("待核对", report.review) }
                }
                if (report.unreadable > 0) Text("${report.unreadable} 个文件未能读取，未计入结果。", color = C.Error, style = MaterialTheme.typography.bodySmall)
                if (report.aiEligible + report.reviewEligible > 0) {
                    val initialScope = if (report.aiEligible > 0) AiScope.Missing else AiScope.Review
                    TextButton({ onTranslate(initialScope) }, enabled = coverage.current && !state.busy && !state.ai.hasPendingTask) {
                        Text("设置 AI 翻译", style = MaterialTheme.typography.labelMedium)
                        LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(14.dp))
                    }
                }
            }
        }
        if (report != null) {
            if (report.missing + report.review > 0) {
                CoverageEntryBrowser(report, kind, { kind = it }, readEntries)
            } else DetailCard {
                Text(when {
                    report.files == 0 && report.unreadable > 0 -> "本次没有读到游戏文本，暂时无法判断汉化覆盖情况。"
                    report.unreadable > 0 -> "已读文件未发现问题，仍有文件未能检查。"
                    report.total == 0 -> "已读文件中没有可检查的文本。"
                    else -> "所有已检查条目均已覆盖。"
                }, style = MaterialTheme.typography.bodyMedium)
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
