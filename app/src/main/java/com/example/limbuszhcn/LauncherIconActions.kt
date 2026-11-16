package com.example.limbuszhcn

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

/** Letterbox the 24-unit artwork even when a parent forces a rectangular slot. */
internal fun DrawScope.inGlyphViewport(draw: DrawScope.() -> Unit) {
    val side = minOf(size.width, size.height)
    if (side <= 0f) return
    withTransform({
        translate((size.width - side) / 2f, (size.height - side) / 2f)
        scale(side / 24f, side / 24f, pivot = Offset.Zero)
    }, draw)
}

/** The slot obeys its parent; the indicator inside always receives square constraints. */
private fun Modifier.squareIndicator() = layout { measurable, constraints ->
    val width = constraints.constrainWidth(24.dp.roundToPx())
    val height = constraints.constrainHeight(24.dp.roundToPx())
    val side = minOf(width, height)
    val child = measurable.measure(Constraints.fixed(side, side))
    layout(width, height) { child.placeRelative((width - side) / 2, (height - side) / 2) }
}

@Composable
internal fun LauncherSpinner(modifier: Modifier = Modifier, color: Color = C.Accent, strokeWidth: Dp = 2.dp) {
    CircularProgressIndicator(modifier.squareIndicator(), color = color, strokeWidth = strokeWidth)
}

@Composable
internal fun ActionIconButton(glyph: Glyph, description: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, rotation: Float = 0f) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tint by animateColorAsState(when {
        busy -> C.Accent
        !enabled -> C.Muted.copy(alpha = .4f)
        pressed || hovered || focused -> C.Accent
        else -> C.Muted
    }, tween(140), label = "icon action tint")
    IconButton(onClick = onClick, enabled = enabled && !busy, interactionSource = interaction,
        modifier = modifier.size(48.dp).semantics {
            contentDescription = description
            if (busy) stateDescription = "正在处理"
        }) {
        Crossfade(busy, modifier = Modifier.size(21.dp), animationSpec = tween(120), label = "icon action state") { loading ->
            if (loading) LauncherSpinner(Modifier.fillMaxSize(), color = tint)
            else LauncherGlyph(glyph, tint, Modifier.fillMaxSize().rotate(rotation))
        }
    }
}
