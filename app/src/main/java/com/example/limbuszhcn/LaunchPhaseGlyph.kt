package com.example.limbuszhcn

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.sin

internal enum class LaunchSymbol { Ready, Scan, Prepare, Translate, Depart, Working, Attention, Info, Retry, Complete }

/** One rounded-line family. Read the shared clock only while drawing an active stage. */
@Composable
internal fun LaunchPhaseGlyph(symbol: LaunchSymbol, phase: State<Float>, moving: Boolean,
    color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        withTransform({ scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) }) {
            val p = if (moving) (phase.value * 2f) % 1f else .5f
            val wave = sin(2 * PI * p).toFloat()
            val stroke = Stroke(1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x: Float, y: Float, x2: Float, y2: Float, alpha: Float = 1f) =
                drawLine(color, Offset(x, y), Offset(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round, alpha = alpha)
            fun path(vararg points: Float, alpha: Float = 1f) {
                val outline = Path().apply {
                    moveTo(points[0], points[1])
                    for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                }
                drawPath(outline, color, alpha = alpha, style = stroke)
            }
            when (symbol) {
                LaunchSymbol.Ready -> {
                    drawArc(color, -48f, 276f, false, Offset(4f, 4f), Size(16f, 16f), style = stroke)
                    line(12f, 2.5f, 12f, 11f)
                }
                LaunchSymbol.Scan -> {
                    path(3.5f, 8f, 3.5f, 4f, 7.5f, 4f)
                    path(16.5f, 4f, 20.5f, 4f, 20.5f, 8f)
                    path(3.5f, 16f, 3.5f, 20f, 7.5f, 20f)
                    path(16.5f, 20f, 20.5f, 20f, 20.5f, 16f)
                    val y = 12f + if (moving) 5f * wave else 0f
                    line(6.5f, y, 17.5f, y)
                    line(8.5f, 12f, 15.5f, 12f, .2f)
                }
                LaunchSymbol.Prepare -> {
                    val lift = if (moving) 1.2f * wave else 0f
                    path(3f, 14.5f, 12f, 19.5f, 21f, 14.5f, alpha = .4f)
                    path(3f, 11f, 12f, 16f, 21f, 11f, alpha = .65f)
                    path(3f, 7.5f + lift, 12f, 2.5f + lift, 21f, 7.5f + lift,
                        12f, 12.5f + lift, 3f, 7.5f + lift)
                }
                LaunchSymbol.Translate -> {
                    val first = if (moving) .78f + .22f * wave else 1f
                    val second = if (moving) .78f - .22f * wave else 1f
                    line(8f, 2.5f, 8f, 5f, first)
                    line(2.5f, 5.5f, 13.5f, 5.5f, first)
                    path(11.5f, 5.5f, 10f, 9f, 7.5f, 12f, 3f, 15f, alpha = first)
                    path(5f, 8f, 7.5f, 11f, 11.5f, 14f, alpha = first)
                    path(12f, 21f, 16.5f, 10f, 21f, 21f, alpha = second)
                    line(14f, 17f, 19f, 17f, second)
                }
                LaunchSymbol.Depart -> {
                    path(7f, 4f, 19f, 4f, 19f, 21f, 7f, 21f, alpha = .4f)
                    val edge = 8f + if (moving) 2f * (wave + 1f) else 2f
                    path(edge, 3f, 19f, 4f, 19f, 21f, edge, 22f, edge, 3f)
                    drawCircle(color, .9f, Offset(edge + 2.5f, 12.5f))
                    if (moving) {
                        val x = 1.5f + 3f * p
                        line(x, 11f, x + 2f, 11f, .5f)
                        line(x, 15f, x + 2f, 15f, .3f)
                    }
                }
                LaunchSymbol.Working -> repeat(3) { index ->
                    val pulse = if (moving) (1f + sin(2 * PI * (p - index / 3f)).toFloat()) / 2 else .5f
                    drawCircle(color, 1.6f, Offset(5f + index * 7f, 12f - pulse * 2f), alpha = .35f + .65f * pulse)
                }
                LaunchSymbol.Retry -> {
                    drawArc(color, -65f, 290f, false, Offset(4f, 4f), Size(16f, 16f), style = stroke)
                    drawCircle(color, 1.65f, Offset(15.4f, 4.7f))
                }
                LaunchSymbol.Attention -> {
                    drawPath(Path().apply {
                        moveTo(12f, 3f); lineTo(20f, 6.5f); lineTo(20f, 11f)
                        cubicTo(20f, 16f, 16f, 20f, 12f, 22f)
                        cubicTo(8f, 20f, 4f, 16f, 4f, 11f); lineTo(4f, 6.5f); close()
                    }, color, style = stroke)
                    line(12f, 8f, 12f, 12f)
                    drawCircle(color, .9f, Offset(12f, 16f))
                }
                LaunchSymbol.Info -> {
                    drawCircle(color, 8.5f, Offset(12f, 12f), style = stroke)
                    line(12f, 11f, 12f, 17f)
                    drawCircle(color, .9f, Offset(12f, 7f))
                }
                LaunchSymbol.Complete -> path(4.5f, 12f, 9.5f, 17f, 19.5f, 6.5f)
            }
        }
    }
}
