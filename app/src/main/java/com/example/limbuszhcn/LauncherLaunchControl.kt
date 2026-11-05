package com.example.limbuszhcn

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C
import kotlin.math.PI
import kotlin.math.sin

internal enum class LaunchAction(val title: String, val glyph: Glyph, val busy: Boolean = false,
    val primary: Boolean = false) {
    Detect("检测游戏", Glyph.Search), Detecting("正在检测", Glyph.Search, busy = true),
    Prepare("准备游戏", Glyph.Sync, busy = true), Translate("检查汉化", Glyph.Translation, busy = true),
    Starting("正在启动", Glyph.Enter, busy = true, primary = true), Working("正在处理", Glyph.Progress, busy = true),
    DeviceStatus("查看设备兼容性", Glyph.Shield), NativeStatus("查看兼容状态", Glyph.Shield),
    Status("查看运行状态", Glyph.Info), Retry("重新启动", Glyph.Refresh, primary = true),
    Launch("启动游戏", Glyph.Play, primary = true)
}

private data class LaunchContent(val title: String, val glyph: Glyph, val working: Boolean)

@Composable
internal fun LaunchButton(action: LaunchAction, enabled: Boolean, step: Int,
    animated: Boolean, feedback: String? = null, status: String = "", onClick: () -> Unit) {
    val busy = action.busy
    val confirmed = feedback != null
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .985f else 1f, tween(130), label = "launch press")
    // Keep the frame state out of composition and layout; pause when the home is covered.
    val phase = if (animated && (enabled || busy)) rememberInfiniteTransition(label = "launch outline").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4800, easing = LinearEasing)), label = "outline phase")
    else rememberUpdatedState(.5f)
    var buttonSize by remember { mutableStateOf(IntSize.Zero) }
    val width = launchButtonWidth(LocalDensity.current.fontScale).dp
    val activeStep = if (busy && step in 0..2) step else -1
    val accent = C.Accent
    val soft = C.AccentSoft
    val line = C.Line
    val haptic = LocalHapticFeedback.current
    val strong = action.primary && !confirmed
    val buttonColor by animateColorAsState(if (strong) accent else soft, tween(180), label = "launch surface")
    val foreground by animateColorAsState(if (strong) C.OnPrimary else C.Accent, tween(180), label = "launch ink")
    val content = LaunchContent(feedback ?: action.title, if (confirmed) Glyph.Check else action.glyph, busy)
    Box(Modifier.fillMaxWidth().heightIn(min = 164.dp)
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }, contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().semantics {
            if (activeStep >= 0) {
                contentDescription = "启动进度"
                progressBarRangeInfo = ProgressBarRangeInfo(activeStep.toFloat(), 0f..3f, 2)
                stateDescription = "已完成 $activeStep 个阶段，正在${listOf("准备游戏", "检查汉化", "启动游戏")[activeStep]}，共 3 个阶段"
            }
        }.drawWithCache {
            val measured = Size(buttonSize.width.toFloat(), buttonSize.height.toFloat())
            val origin = Offset((size.width - measured.width) / 2, (size.height - measured.height) / 2)
            val base = Rect(origin, measured)
            val spread = (minOf((size.width - measured.width) / 2,
                (size.height - measured.height) / 2) - 2.dp.toPx()).coerceAtLeast(0f)
            val stroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
            val ring = capsulePath(base.inflate(5.dp.toPx()))
            val measure = PathMeasure().apply { setPath(ring, false) }
            val span = measure.length / 3
            val gap = 9.dp.toPx().coerceAtMost(span / 4)
            val segments = List(3) { index -> Path().also {
                measure.getSegment(index * span + gap / 2, (index + 1) * span - gap / 2, it)
            } }
            val highlight = Path()
            val glow = Brush.radialGradient(listOf(accent.copy(alpha = .09f),
                soft.copy(alpha = .025f), Color.Transparent), Offset(size.width / 2, size.height / 2), size.width / 2)
            val wave = Brush.linearGradient(listOf(accent.copy(alpha = .03f),
                accent.copy(alpha = .18f), accent.copy(alpha = .04f)),
                start = Offset.Zero, end = Offset(size.width, size.height))
            onDrawBehind {
                if (measured.width <= 0 || measured.height <= 0) return@onDrawBehind
                val halo = base.inflate(spread)
                drawRoundRect(glow, halo.topLeft, halo.size,
                    CornerRadius(minOf(halo.width, halo.height) / 2))
                repeat(3) { layer ->
                    val p = (phase.value + layer / 3f) % 1f
                    val bounds = base.inflate(spread * p)
                    // Equal offsets in both axes preserve the capsule's curvature.
                    drawRoundRect(wave, bounds.topLeft, bounds.size,
                        CornerRadius(minOf(bounds.width, bounds.height) / 2),
                        alpha = (1f - p) * (if (activeStep >= 0) .45f else 1f),
                        style = Stroke(1.dp.toPx()))
                }
                if (activeStep >= 0) {
                    segments.forEachIndexed { index, path ->
                        drawPath(path, if (index < activeStep) accent else if (index == activeStep)
                            accent.copy(alpha = .3f) else line, style = stroke)
                    }
                    // The highlight loops only within the current real phase, never faking completion.
                    val sweep = (phase.value * 3f) % 1f
                    val length = span - gap
                    val start = activeStep * span + gap / 2 + length * .66f * sweep
                    highlight.reset()
                    measure.getSegment(start, start + length * .34f, highlight)
                    drawPath(highlight, accent, alpha = sin(PI * sweep).toFloat(), style = stroke)
                }
            }
        })
        Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
            enabled = enabled && !busy, interactionSource = interaction,
            modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = width).fillMaxWidth()
                .heightIn(min = 76.dp).background(Brush.linearGradient(listOf(buttonColor,
                    lerp(buttonColor, if (strong) C.OnPrimary else C.Panel, .06f))), CircleShape)
                .onSizeChanged { buttonSize = it }.semantics {
                    if (busy || confirmed) liveRegion = LiveRegionMode.Polite
                    if (status.isNotBlank()) stateDescription = status
                },
            shape = CircleShape,
            border = BorderStroke(1.dp, accent.copy(alpha = if (strong) .04f else .12f)),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 22.dp, top = 12.dp, bottom = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = foreground,
                disabledContainerColor = Color.Transparent, disabledContentColor = if (busy) foreground else C.Muted)) {
            AnimatedContent(content, Modifier.fillMaxWidth(), transitionSpec = {
                (fadeIn(tween(180)) togetherWith fadeOut(tween(90))).using(SizeTransform(clip = false))
            }, label = "launch action") { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(foreground.copy(alpha = .1f)),
                        contentAlignment = Alignment.Center) {
                        LauncherGlyph(item.glyph, foreground, Modifier.size(23.dp))
                        if (item.working && activeStep < 0) CircularProgressIndicator(Modifier.size(39.dp),
                            color = foreground.copy(alpha = .5f), strokeWidth = 1.5.dp)
                    }
                    Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        if (!item.working && !confirmed && enabled) LauncherGlyph(
                            if (action.primary) Glyph.Arrow else Glyph.Chevron, foreground, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** Clockwise from the top centre; the same measured capsule defines all three stages. */
private fun capsulePath(bounds: Rect): Path = Path().apply {
    val r = minOf(bounds.width, bounds.height) / 2
    moveTo(bounds.center.x, bounds.top)
    lineTo(bounds.right - r, bounds.top)
    arcTo(Rect(bounds.right - 2 * r, bounds.top, bounds.right, bounds.top + 2 * r), -90f, 90f, false)
    lineTo(bounds.right, bounds.bottom - r)
    arcTo(Rect(bounds.right - 2 * r, bounds.bottom - 2 * r, bounds.right, bounds.bottom), 0f, 90f, false)
    lineTo(bounds.left + r, bounds.bottom)
    arcTo(Rect(bounds.left, bounds.bottom - 2 * r, bounds.left + 2 * r, bounds.bottom), 90f, 90f, false)
    lineTo(bounds.left, bounds.top + r)
    arcTo(Rect(bounds.left, bounds.top, bounds.left + 2 * r, bounds.top + 2 * r), 180f, 90f, false)
    lineTo(bounds.center.x, bounds.top)
    close()
}
