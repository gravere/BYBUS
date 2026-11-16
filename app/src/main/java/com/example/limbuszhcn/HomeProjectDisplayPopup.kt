package com.example.limbuszhcn

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.example.limbuszhcn.translation.SourceState
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun HomeProjectDisplayPopup(sources: SourceState, pinnedId: String?, onSelect: (String?) -> Unit, onClosed: () -> Unit) {
    var closing by remember { mutableStateOf(false) }
    val visibility = remember { Animatable(0f) }
    val display = HomeProjectDisplay.resolve(sources, pinnedId)
    val space = with(LocalDensity.current) { 12.dp.roundToPx() }
    val config = LocalConfiguration.current
    val width = minOf(340, config.screenWidthDp - 32).coerceAtLeast(160).dp
    val position = remember(space) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val x = (anchorBounds.right - popupContentSize.width).coerceIn(space, maxOf(space, windowSize.width - popupContentSize.width - space))
            val below = anchorBounds.bottom + space
            val y = if (below + popupContentSize.height <= windowSize.height - space) below else anchorBounds.top - popupContentSize.height - space
            return IntOffset(x, y.coerceIn(space, maxOf(space, windowSize.height - popupContentSize.height - space)))
        }
    } }
    LaunchedEffect(closing) {
        visibility.animateTo(if (closing) 0f else 1f, tween(if (closing) 120 else 160))
        if (closing) onClosed()
    }
    Popup(position, onDismissRequest = { closing = true }, properties = PopupProperties(focusable = true)) {
        Surface(Modifier.width(width).graphicsLayer {
            alpha = visibility.value; scaleX = .97f + .03f * visibility.value; scaleY = scaleX
        }, shape = RoundedCornerShape(22.dp), color = C.Panel, shadowElevation = 8.dp, tonalElevation = 0.dp) {
            Column(Modifier.heightIn(max = minOf(440, config.screenHeightDp - 64).coerceAtLeast(120).dp)
                .verticalScroll(rememberScrollState()).selectableGroup().padding(vertical = 10.dp)) {
                Text("首页显示", color = C.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                DisplayChoice("跟随当前汉化源", "当前 · ${sources.selected.name}", !display.pinned, !closing,
                    icon = { LauncherGlyph(Glyph.Sync, C.Accent, Modifier.size(24.dp)) }) { onSelect(null); closing = true }
                HorizontalDivider(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = C.Line)
                Text("固定显示", color = C.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
                sources.sources.forEach { source ->
                    DisplayChoice(source.name, source.label, display.pinned && display.source.id == source.id, !closing,
                        icon = { TranslationSourceIcon(source, Modifier.size(28.dp)) }) { onSelect(source.id); closing = true }
                }
            }
        }
    }
}

@Composable
private fun DisplayChoice(title: String, description: String, selected: Boolean, enabled: Boolean,
    icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (selected) C.AccentSoft else C.Panel)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 58.dp).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = C.Ink, style = MaterialTheme.typography.bodyMedium)
            Text(description, color = C.Muted, style = MaterialTheme.typography.labelSmall)
        }
        RadioButton(selected, onClick = null, modifier = Modifier.size(24.dp), colors = RadioButtonDefaults.colors(selectedColor = C.Accent, unselectedColor = C.Muted))
    }
}
