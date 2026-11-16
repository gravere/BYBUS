package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun CoverageSummary(state: LauncherUiState, onOpen: () -> Unit) {
    val coverage = state.coverage
    val report = coverage.report
    DetailCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("汉化覆盖", style = MaterialTheme.typography.titleSmall)
                Text(when {
                    coverage.loading -> "正在对照本地文本"
                    report != null -> "${report.missing} 条未覆盖 · ${report.review} 条待核对"
                    coverage.error != null -> "本次检查未完成"
                    else -> "还未检查本地游戏文本"
                }, color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onOpen, enabled = !state.busy) { Text(if (report != null || coverage.error != null) "查看" else "检查") }
        }
    }
}

@Composable
internal fun TranslationCoveragePane(state: LauncherUiState, onCheck: () -> Unit) {
    val coverage = state.coverage
    val report = coverage.report
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DetailCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(report?.sourceName ?: state.sources.selected.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onCheck, enabled = !coverage.loading && !state.busy) {
                    if (coverage.loading) LauncherSpinner(Modifier.size(16.dp)) else LauncherGlyph(Glyph.Refresh, C.Accent, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp));Text(if (coverage.loading) "检查中" else "重新检查")
                }
            }
            Text("对照当前汉化包与本机已下载的游戏文本，不使用 API 额度。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            coverage.error?.let { Text(it, color = C.Error, style = MaterialTheme.typography.bodyMedium) }
            if (coverage.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (report != null) {
                Text("${report.total} 条文本中，${report.covered} 条已有对应译文", style = MaterialTheme.typography.bodyMedium)
                Text("未覆盖 ${report.missing} 条，其中 ${report.aiEligible} 条可用 AI 补全；另有 ${report.review} 条译文仍含日韩文，建议核对。",
                    style = MaterialTheme.typography.bodyMedium)
                if (report.unreadable > 0) Text("${report.unreadable} 个文件未能读取，尚未计入结果。", color = C.Error, style = MaterialTheme.typography.bodySmall)
                Text("检查于 " + DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(report.checkedAt)),
                    color = C.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (report != null) {
            if (report.details.isEmpty()) DetailCard { Text(if (report.unreadable == 0) "本次本地检查没有发现未覆盖文本。" else "已读文件未发现缺失，仍有文件未能检查。") }
            report.details.groupBy { it.category }.forEach { (category, files) ->
                var expanded by remember(report.checkedAt, category) { mutableStateOf(false) }
                var count by remember(report.checkedAt, category) { mutableIntStateOf(12) }
                DetailCard {
                    TextButton({ expanded = !expanded }, contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(category, style = MaterialTheme.typography.titleSmall, color = C.Ink)
                            Text("未覆盖 ${files.sumOf { it.missing }} · 待核对 ${files.sumOf { it.review }}", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(18.dp).rotate(if (expanded) 270f else 90f))
                    }
                    if (expanded) {
                        files.take(count).forEach { file ->
                            HorizontalDivider(color = C.Line)
                            Text(file.resource.removePrefix("jp/").removePrefix("JP_"), style = MaterialTheme.typography.labelMedium)
                            Text("未覆盖 ${file.missing} · 待核对 ${file.review}", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                            file.examples.forEach { sample ->
                                Text((if (sample.review) "待核对示例：" else "未覆盖原文：") + sample.text,
                                    style = MaterialTheme.typography.bodySmall, color = C.Ink)
                            }
                        }
                        if (files.size > count) TextButton({ count += 12 }) { Text("再显示 ${minOf(12, files.size - count)} 个文件") }
                    }
                }
            }
        }
        Text("只检查已下载的文本文件；未下载的章节、图片内文字和服务端临时消息不在统计内。待核对可能包含专名，不会自动覆盖已有译文。游戏或汉化更新后，可重新检查。",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
    }
}
