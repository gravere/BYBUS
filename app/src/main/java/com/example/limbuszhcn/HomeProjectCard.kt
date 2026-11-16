package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.tracking.ProjectTrackingState
import com.example.limbuszhcn.translation.SourceState
import com.example.limbuszhcn.translation.TranslationSource
import com.example.limbuszhcn.ui.theme.LauncherColors as C
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class HomeProjectDisplay(val source: TranslationSource, val pinned: Boolean) {
    companion object {
        fun resolve(sources: SourceState, pinnedId: String?): HomeProjectDisplay {
            val fixed = sources.sources.firstOrNull { it.id == pinnedId }
            return HomeProjectDisplay(fixed ?: sources.selected, fixed != null)
        }
    }
}

@Composable
internal fun HomeProjectCard(state: LauncherUiState, tracking: ProjectTrackingState, pinnedId: String?,
    onPin: (String?) -> Unit, onProject: () -> Unit, onSources: () -> Unit, onAi: () -> Unit,
    modifier: Modifier = Modifier) {
    val display = HomeProjectDisplay.resolve(state.sources, pinnedId)
    val source = display.source
    var choose by remember { mutableStateOf(false) }
    val continueAi = source.kind == "ai" && source.id == state.sources.selectedId
    val open = if (source.isOfficial) onProject else if (continueAi) onAi else onSources
    HomeSummaryCard(open, modifier.semantics {
        stateDescription = if (display.pinned) "固定显示：${source.name}" else "跟随当前汉化源：${source.name}"
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TranslationSourceIcon(source, Modifier.size(28.dp))
            Text(source.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (display.pinned) LauncherGlyph(Glyph.Pin, C.Muted, Modifier.size(16.dp), "固定显示")
            Box {
                ActionIconButton(Glyph.More, "首页卡片显示", { choose = true })
                DropdownMenu(choose, onDismissRequest = { choose = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                    DropdownMenuItem(text = { Text("跟随当前汉化源") }, onClick = { choose = false; onPin(null) },
                        trailingIcon = { if (!display.pinned) LauncherGlyph(Glyph.Check, C.Accent, Modifier.size(18.dp)) })
                    HorizontalDivider()
                    Text("固定显示", color = C.Muted, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    state.sources.sources.forEach { option ->
                        DropdownMenuItem(text = { Text(option.name) }, onClick = { choose = false; onPin(option.id) },
                            leadingIcon = { TranslationSourceIcon(option, Modifier.size(24.dp)) },
                            trailingIcon = { if (display.pinned && option.id == source.id)
                                LauncherGlyph(Glyph.Check, C.Accent, Modifier.size(18.dp)) })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (source.isOfficial) ZeroProjectSummary(tracking) else {
            Text(source.label, color = C.Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Text(if (source.textFileCount > 0) "已保存 ${source.textFileCount} 个文本文件" else "已保存到本地",
                style = MaterialTheme.typography.titleLarge)
            if (source.updatedAtMillis > 0) {
                Spacer(Modifier.height(6.dp))
                Text("更新于 " + DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(source.updatedAtMillis)), color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = C.Line)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (continueAi) "继续 AI 翻译" else "管理汉化源", color = C.Muted,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(16.dp))
            }
        }
    }
}
