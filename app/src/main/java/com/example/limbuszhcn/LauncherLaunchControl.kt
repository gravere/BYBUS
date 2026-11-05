package com.example.limbuszhcn

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C
import kotlin.math.PI
import kotlin.math.sin

internal enum class LaunchAction(val title: String, val symbol: LaunchSymbol, val busy: Boolean = false) {
    Detect("检测游戏", LaunchSymbol.Scan), Detecting("正在检测", LaunchSymbol.Scan, busy = true),
    Prepare("准备游戏", LaunchSymbol.Prepare, busy = true), Translate("检查汉化", LaunchSymbol.Translate, busy = true),
    Starting("正在启动", LaunchSymbol.Depart, busy = true), Working("正在处理", LaunchSymbol.Working, busy = true),
    DeviceStatus("查看设备兼容性", LaunchSymbol.Attention), NativeStatus("查看兼容状态", LaunchSymbol.Attention),
    Status("查看运行状态", LaunchSymbol.Info), Retry("重新启动", LaunchSymbol.Retry), Launch("启动游戏", LaunchSymbol.Ready)
}

private data class LaunchContent(val title: String, val symbol: LaunchSymbol, val busy: Boolean)

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
    val buttonColor by animateColorAsState(if (confirmed) soft else C.Panel, tween(180), label = "launch surface")
    Box(Modifier.fillMaxWidth().heightIn(min = 120.dp)
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
            val wave = Brush.linearGradient(listOf(accent.copy(alpha = .04f),
                accent.copy(alpha = .24f), accent.copy(alpha = .05f)),
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp).widthIn(max = width).fillMaxWidth()
                .heightIn(min = 56.dp)
                .onSizeChanged { buttonSize = it }.semantics {
                    if (busy || confirmed) liveRegion = LiveRegionMode.Polite
                    if (status.isNotBlank()) stateDescription = status
                },
            shape = CircleShape,
            border = BorderStroke(1.dp, accent.copy(alpha = if (enabled || busy) .22f else .1f)),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp, pressedElevation = 0.dp,
                disabledElevation = if (busy) 1.dp else 0.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor, contentColor = C.Ink,
                disabledContainerColor = C.Panel, disabledContentColor = if (busy) C.Ink else C.Muted)) {
            val content = LaunchContent(feedback ?: action.title, if (confirmed) LaunchSymbol.Complete else action.symbol, busy)
            AnimatedContent(content, Modifier.fillMaxWidth(), contentAlignment = Alignment.Center,
                transitionSpec = {
                (fadeIn(tween(180)) togetherWith fadeOut(tween(90))).using(SizeTransform(clip = false))
            }, label = "launch action") { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    LaunchPhaseGlyph(item.symbol, phase, animated && item.busy,
                        if (enabled || busy) accent else C.Muted, Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(item.title, Modifier.weight(1f, fill = false), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge)
                    // Balance the symbol and its gap, keeping the title at the button centre.
                    Spacer(Modifier.width(32.dp))
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
