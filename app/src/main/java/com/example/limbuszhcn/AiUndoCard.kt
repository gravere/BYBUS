package com.example.limbuszhcn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.gamefs.GameTextLanguage
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.ui.theme.LauncherColors as C
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun undoTime(point: AiUndoPoint, state: SourceState): String {
    val time = point.summary?.atMillis?.takeIf { it > 0 } ?: state.sources.find { it.id == point.sourceId }?.updatedAtMillis ?: 0
    return if (time > 0) SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(time)) else "时间未记录"
}
private fun undoSource(point: AiUndoPoint, state: SourceState) = state.sources.find { it.id == point.sourceId }

@Composable
internal fun AiUndoCard(point: AiUndoPoint, sources: SourceState, enabled: Boolean, onUndo: () -> Unit) {
    var details by remember(point) { mutableStateOf(false) }
    DetailCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("上次译文改动", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(undoTime(point, sources), style = MaterialTheme.typography.labelSmall, color = C.Muted)
        }
        val source = undoSource(point, sources)
        Text(source?.name ?: point.previous?.name ?: "AI 汉化包", style = MaterialTheme.typography.bodyMedium)
        Text(point.summary?.description ?: if (point.previous == null) "生成汉化包" else "更新汉化包", style = MaterialTheme.typography.bodySmall)
        point.summary?.counts?.takeIf(String::isNotBlank)?.let { Text(it, color = C.Muted, style = MaterialTheme.typography.bodySmall) }
        AnimatedVisibility(details) { AiUndoScope(point, source) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ details = !details }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (details) "收起详情" else "查看改动", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onUndo, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("撤回这次改动", color = if (enabled) C.Error else C.Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun AiUndoScope(point: AiUndoPoint, source: TranslationSource?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        source?.language?.let(GameTextLanguage::fromCode)?.let { Text("原文语言：${it.displayName}", style = MaterialTheme.typography.bodySmall) }
        val summary = point.summary
        if (summary == null) Text("旧记录未保存条目明细，仍可恢复改动前的汉化。", style = MaterialTheme.typography.bodySmall, color = C.Muted)
        else {
            summary.categories.forEach { (category, count) ->
                Row(Modifier.fillMaxWidth()) { Text(category, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("$count 条", style = MaterialTheme.typography.bodySmall, color = C.Muted) }
            }
            if (summary.kind == AiChangeKind.Upstream) Text("以上统计 AI 处理条目；本次也同步了原包译文。", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            if (summary.examples.isNotEmpty()) {
                Text("改动示例", style = MaterialTheme.typography.labelMedium)
                summary.examples.forEach { example ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${example.category} · ${example.original}", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                        Text("改动前：${example.before ?: "无译文"}", style = MaterialTheme.typography.bodySmall)
                        Text("改动后：${example.after ?: "已移除译文"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AiUndoConfirmation(point: AiUndoPoint, sources: SourceState) {
    val source = undoSource(point, sources)
    val remaining = sources.sources.mapNotNull { if (it.id == point.sourceId) point.previous?.copy(name = it.name) else it }
    val target = remaining.find { it.id == point.previousSelectedId }?.name
        ?: remaining.find { it.id == source?.baseId }?.name ?: TranslationSources.OFFICIAL.name
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(source?.name ?: point.previous?.name ?: "AI 汉化包", style = MaterialTheme.typography.bodyMedium)
        Text(undoTime(point, sources), color = C.Muted, style = MaterialTheme.typography.bodySmall)
        point.summary?.let { Text(it.description + if (it.counts.isBlank()) "" else "\n${it.counts}", style = MaterialTheme.typography.bodySmall) }
        Text(if (point.previous == null) "这次新生成的汉化包将被移除。" else "此汉化包将恢复到上次改动前。", style = MaterialTheme.typography.bodyMedium)
        Text("撤回后使用「$target」。将关闭游戏，保留账号和游戏资源；API 费用不退回。", style = MaterialTheme.typography.bodySmall)
    }
}
