package com.example.limbuszhcn

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun LaunchButton(title: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f,
        spring(dampingRatio = .8f, stiffness = 450f), label = "launch press")
    val haptic = LocalHapticFeedback.current
    Button(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        enabled = enabled && !busy,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        colors = ButtonDefaults.buttonColors(disabledContainerColor = if (busy) MaterialTheme.colorScheme.primary else C.Line,
            disabledContentColor = if (busy) C.OnPrimary else C.Muted)
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(21.dp), color = C.OnPrimary, strokeWidth = 2.dp)
        else LauncherGlyph(Glyph.Play, if (enabled) C.OnPrimary else C.Muted, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        AnimatedContent(title, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(90)) }, label = "launch title") {
            Text(it, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun PreparationSteps(step: Int) {
    val accent = C.Accent
    val line = C.Line
    Row(Modifier.fillMaxWidth().padding(top = 12.dp).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(step.coerceIn(0, 2).toFloat(), 0f..2f, 1)
        stateDescription = "准备步骤 ${step.coerceIn(0, 2) + 1}，共 3 步"
    }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { index ->
            val color by animateColorAsState(if (index <= step) accent else line, tween(200), label = "step $index")
            Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
internal fun IconTile(icon: Glyph, accent: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
        .background(if (accent) C.AccentSoft else C.Background), contentAlignment = Alignment.Center) {
        LauncherGlyph(icon, if (accent) C.Accent else C.Ink, Modifier.size(21.dp))
    }
}

/** Fixed targets and one shared treatment for every source chooser. */
@Composable
internal fun <T> SourceTabs(options: List<T>, selected: T, label: (T) -> String, description: String,
    onSelect: (T) -> Unit, trackColor: Color = C.Background) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(16.dp))
        .background(trackColor).padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            val active = option == selected
            val background by animateColorAsState(if (active) C.AccentSoft else trackColor, tween(180), label = "source background")
            val foreground by animateColorAsState(if (active) C.Accent else C.Muted, tween(180), label = "source label")
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(background)
                .selectable(selected = active, role = Role.Tab, onClick = { if (!active) onSelect(option) })
                .semantics {
                    contentDescription = "$description：${label(option)}"
                    stateDescription = if (active) "已选择" else "未选择"
                }
                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(label(option), color = foreground, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

internal enum class Glyph { Mark, Bus, Settings, Battery, Account, Arrow, Chevron, Refresh, Import, File, Stop, Globe, Play, External, Check, Close, Info }

@Composable
internal fun LauncherGlyph(glyph: Glyph, color: Color, modifier: Modifier = Modifier, description: String? = null) {
    if (glyph == Glyph.Mark) {
        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_bybus), description, modifier, tint = color)
        return
    }
    Canvas(if (description != null) modifier.semantics { contentDescription = description } else modifier) {
        withTransform({ scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) }) {
            val stroke = Stroke(1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x, y), Offset(x2, y2), strokeWidth = 1.65f, cap = StrokeCap.Round)
            fun path(vararg points: Float) {
                val p = Path().apply { moveTo(points[0], points[1]); for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1]) }
                drawPath(p, color, style = stroke)
            }
            when (glyph) {
                Glyph.Mark -> Unit // Shared vector is handled above.
                Glyph.Arrow -> { line(4f, 12f, 20f, 12f); path(14f, 6f, 20f, 12f, 14f, 18f) }
                Glyph.Chevron -> path(9f, 6f, 15f, 12f, 9f, 18f)
                Glyph.Play -> drawPath(Path().apply { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }, color)
                Glyph.Check -> path(5f, 12f, 10f, 17f, 19f, 7f)
                Glyph.Close -> { line(6f, 6f, 18f, 18f); line(6f, 18f, 18f, 6f) }
                Glyph.External -> { path(13f, 4f, 20f, 4f, 20f, 11f); line(11f, 13f, 20f, 4f); path(9f, 5f, 4f, 5f, 4f, 20f, 19f, 20f, 19f, 15f) }
                Glyph.Bus -> {
                    drawRoundRect(color, Offset(5f, 3f), Size(14f, 16f), CornerRadius(3f), style = stroke)
                    line(5f, 11f, 19f, 11f); line(8f, 19f, 8f, 21f); line(16f, 19f, 16f, 21f)
                    drawCircle(color, 1.1f, Offset(8.5f, 15f)); drawCircle(color, 1.1f, Offset(15.5f, 15f)); line(10f, 6f, 14f, 6f)
                }
                Glyph.Settings -> {
                    line(5f, 3f, 5f, 7f); line(5f, 12f, 5f, 21f); drawCircle(color, 2.2f, Offset(5f, 9.5f), style = stroke)
                    line(12f, 3f, 12f, 12f); line(12f, 17f, 12f, 21f); drawCircle(color, 2.2f, Offset(12f, 14.5f), style = stroke)
                    line(19f, 3f, 19f, 7f); line(19f, 12f, 19f, 21f); drawCircle(color, 2.2f, Offset(19f, 9.5f), style = stroke)
                }
                Glyph.Battery -> {
                    drawRoundRect(color, Offset(2.5f, 6f), Size(16f, 12f), CornerRadius(2.5f), style = stroke)
                    line(21.5f, 10f, 21.5f, 14f)
                    path(12f, 8.5f, 8.5f, 12.5f, 12f, 12.5f, 9f, 15.5f)
                }
                Glyph.Account -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = stroke)
                    drawCircle(color, 2.8f, Offset(12f, 8.5f), style = stroke)
                    drawPath(Path().apply {
                        moveTo(6.2f, 18.5f)
                        cubicTo(6.8f, 15.8f, 9f, 14.5f, 12f, 14.5f)
                        cubicTo(15f, 14.5f, 17.2f, 15.8f, 17.8f, 18.5f)
                    }, color, style = stroke)
                }
                Glyph.Refresh -> { drawArc(color, 35f, 280f, false, Offset(4f, 4f), Size(16f, 16f), style = stroke); path(15f, 3f, 19.5f, 6.5f, 15f, 8f) }
                Glyph.Import -> { path(4f, 15f, 4f, 20f, 20f, 20f, 20f, 15f); line(12f, 3f, 12f, 15f); path(7f, 10f, 12f, 15f, 17f, 10f) }
                Glyph.File -> {
                    path(14f, 3f, 5f, 3f, 5f, 21f, 19f, 21f, 19f, 8f, 14f, 3f, 14f, 8f, 19f, 8f)
                    line(9f, 12f, 15f, 12f); line(9f, 16f, 15f, 16f)
                }
                Glyph.Stop -> { drawCircle(color, 8.5f, Offset(12f, 12f), style = stroke); line(6f, 18f, 18f, 6f) }
                Glyph.Info -> { drawCircle(color, 9f, Offset(12f, 12f), style = stroke); line(12f, 11f, 12f, 17f); drawCircle(color, 1f, Offset(12f, 7f)) }
                Glyph.Globe -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = stroke)
                    drawOval(color, Offset(8f, 3f), Size(8f, 18f), style = stroke)
                    line(3f, 12f, 21f, 12f)
                }
            }
        }
    }
}
