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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
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
    var pending by remember { mutableStateOf<String?>(null) }
    var hasSelection by remember { mutableStateOf(false) }
    fun select(id: String?) { pending = id; hasSelection = true; closing = true }
    val visibility = remember { Animatable(0f) }
    val display = HomeProjectDisplay.resolve(sources, pinnedId)
    val space = with(LocalDensity.current) { 12.dp.roundToPx() }
    val config = LocalConfiguration.current
    val width = homeDisplayPopupWidth(config.screenWidthDp.toFloat(), LocalDensity.current.fontScale).dp
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
        if (closing) { onClosed(); if (hasSelection) onSelect(pending) }
    }
    Popup(position, onDismissRequest = { closing = true }, properties = PopupProperties(focusable = true)) {
        Box(Modifier.padding(10.dp).graphicsLayer {
            alpha = visibility.value; translationY = -4.dp.toPx() * (1f - visibility.value)
        }) {
        Surface(Modifier.width(width).shadow(6.dp, RoundedCornerShape(18.dp), clip = false,
            ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .12f)),
            shape = RoundedCornerShape(18.dp), color = C.Panel, shadowElevation = 0.dp, tonalElevation = 0.dp) {
            Column(Modifier.heightIn(max = minOf(if (config.screenWidthDp >= 600) 420 else 340, config.screenHeightDp - 80).coerceAtLeast(120).dp)
                .verticalScroll(rememberScrollState()).selectableGroup().padding(vertical = 6.dp)) {
                Text("首页显示", color = C.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                DisplayChoice("跟随当前汉化源", !display.pinned, !closing,
                    icon = { LauncherGlyph(Glyph.Sync, C.Accent, Modifier.size(20.dp)) }) { select(null) }
                HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = C.Line)
                Text("固定来源", color = C.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                sources.sources.forEach { source ->
                    DisplayChoice(source.name, display.pinned && display.source.id == source.id, !closing,
                        icon = { TranslationSourceIcon(source, Modifier.size(24.dp)) }) { select(source.id) }
                }
            }
        }
        }
    }
}

@Composable
private fun DisplayChoice(title: String, selected: Boolean, enabled: Boolean,
    icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (selected) C.AccentSoft else C.Panel)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Text(title, color = C.Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box(Modifier.size(18.dp)) { if (selected) LauncherGlyph(Glyph.Check, C.Accent, Modifier.fillMaxSize()) }
    }
}

internal fun homeDisplayPopupWidth(windowWidth: Float, fontScale: Float): Float =
    ((if (windowWidth >= 600) 328f else 264f) * (1f + (fontScale.coerceAtLeast(1f) - 1f) * .5f))
        .coerceAtMost((windowWidth - 48f).coerceAtLeast(140f))
