package com.example.limbuszhcn

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.SourceState
import com.example.limbuszhcn.ui.theme.LauncherColors as C

/** Display preferences only: selecting a row never switches the game's translation source. */
@Composable
internal fun HomeProjectDisplayPane(sources: SourceState, pinnedId: String?, enabled: Boolean,
    onSelect: (String?) -> Unit) {
    val display = HomeProjectDisplay.resolve(sources, pinnedId)
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("只改变首页展示，不切换游戏汉化", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Background)) {
            DisplayChoice("跟随当前汉化源", "当前 · ${sources.selected.name}", !display.pinned, enabled,
                icon = { LauncherGlyph(Glyph.Sync, C.Accent, Modifier.size(28.dp)) }, onClick = { onSelect(null) })
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("固定显示", color = C.Muted, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Background)) {
                sources.sources.forEachIndexed { index, source ->
                    if (index > 0) DetailActionDivider()
                    DisplayChoice(source.name, source.label, display.pinned && display.source.id == source.id, enabled,
                        icon = { TranslationSourceIcon(source, Modifier.size(32.dp)) }, onClick = { onSelect(source.id) })
                }
            }
        }
    }
}

@Composable
private fun DisplayChoice(title: String, description: String, selected: Boolean, enabled: Boolean,
    icon: @Composable () -> Unit, onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) C.AccentSoft else C.Background,
        tween(140), label = "home display selection")
    Row(Modifier.fillMaxWidth().background(background)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = C.Ink, style = MaterialTheme.typography.titleSmall)
            Text(description, color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(
            selectedColor = C.Accent, unselectedColor = C.Muted), modifier = Modifier.size(24.dp))
    }
}
