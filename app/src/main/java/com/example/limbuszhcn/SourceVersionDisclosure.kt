package com.example.limbuszhcn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.TranslationSource
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun SourceVersionDisclosure(id: String, title: String, source: TranslationSource? = null,
    content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val angle by animateFloatAsState(if (expanded) 270f else 90f, tween(160), label = "source versions")
    Column {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (source != null) TranslationSourceIcon(source, Modifier.size(28.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(16.dp).rotate(angle))
        }
        AnimatedVisibility(expanded) { Column(content = content) }
    }
}

@Composable
internal fun SourceSelectionRow(source: TranslationSource, state: LauncherUiState, onProject: () -> Unit, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy, role = Role.Button,
        onClick = if (source.isOfficial && source.id == state.sources.selectedId) onProject else onSelect).heightIn(min = 64.dp)
        .padding(start = if (source.kind == "ai") 28.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TranslationSourceIcon(source, Modifier.size(if (source.kind == "ai") 32.dp else 40.dp))
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.titleSmall)
            Text(state.packageTimeLabel(source) ?: source.label, color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (source.id == state.sources.selectedId) LauncherGlyph(Glyph.Check, C.Accent, Modifier.size(20.dp), "当前使用")
        if (source.isOfficial) ActionIconButton(Glyph.Info, "查看零协项目状态", onProject)
    }
}
