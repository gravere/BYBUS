package com.example.limbuszhcn

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun HomeSummaryCard(onClick: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(onClick = onClick, modifier = modifier, color = C.Panel, contentColor = C.Ink,
        shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), content = content)
    }
}

@Composable
internal fun SummaryHeading(icon: Glyph, title: String, status: String? = null, ready: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LauncherGlyph(icon, C.Accent, Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        status?.let {
            Text(it, color = if (ready) C.Accent else C.Muted, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.widthIn(max = 96.dp))
        }
        LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(14.dp))
    }
}

@Composable
internal fun IconTile(icon: Glyph, accent: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
        .background(if (accent) C.AccentSoft else C.Background), contentAlignment = Alignment.Center) {
        LauncherGlyph(icon, if (accent) C.Accent else C.Ink, Modifier.size(20.dp))
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

internal enum class Glyph {
    Mark, Bus, Settings, Battery, Account, Arrow, Chevron, Refresh, File, Stop, Globe, Play, External, Check, Close, Info,
    Upgrade, Gamepad, Sync, Apps, Translation, ReleaseTag, Folder, Store, Progress, BookOpen, Import,
    Search, Enter, Shield, Repair
}

@Composable
internal fun LauncherGlyph(glyph: Glyph, color: Color, modifier: Modifier = Modifier, description: String? = null) {
    val vector = when (glyph) {
        Glyph.Mark -> R.drawable.ic_bybus
        Glyph.Repair -> R.drawable.ic_action_repair
        Glyph.Refresh -> R.drawable.ic_action_refresh
        Glyph.Upgrade -> R.drawable.ic_action_upgrade
        Glyph.Gamepad -> R.drawable.ic_action_gamepad
        Glyph.Sync -> R.drawable.ic_action_sync
        Glyph.Apps -> R.drawable.ic_action_apps
        Glyph.Translation -> R.drawable.ic_action_translation
        Glyph.ReleaseTag -> R.drawable.ic_action_release
        Glyph.Folder -> R.drawable.ic_action_folder
        Glyph.Store -> R.drawable.ic_action_store
        Glyph.Progress -> R.drawable.ic_action_progress
        Glyph.BookOpen -> R.drawable.ic_action_book
        else -> null
    }
    if (vector != null) {
        Icon(androidx.compose.ui.res.painterResource(vector), description, modifier, tint = color)
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
                Glyph.Search -> {
                    drawCircle(color, 6.5f, Offset(10.5f, 10.5f), style = stroke)
                    line(15.2f, 15.2f, 20.5f, 20.5f)
                }
                Glyph.Enter -> {
                    path(13f, 3.5f, 20f, 3.5f, 20f, 20.5f, 13f, 20.5f)
                    line(3.5f, 12f, 14.5f, 12f)
                    path(10.5f, 7.5f, 15f, 12f, 10.5f, 16.5f)
                }
                Glyph.Shield -> {
                    drawPath(Path().apply {
                        moveTo(12f, 2.5f); lineTo(20f, 6f); lineTo(20f, 11f)
                        cubicTo(20f, 16f, 16.5f, 19.5f, 12f, 22f)
                        cubicTo(7.5f, 19.5f, 4f, 16f, 4f, 11f)
                        lineTo(4f, 6f); close()
                    }, color, style = stroke)
                    line(12f, 7f, 12f, 12f); drawCircle(color, 1f, Offset(12f, 16f))
                }
                Glyph.Mark, Glyph.Refresh, Glyph.Upgrade, Glyph.Gamepad, Glyph.Sync, Glyph.Apps, Glyph.Translation,
                Glyph.ReleaseTag, Glyph.Folder, Glyph.Store, Glyph.Progress, Glyph.BookOpen, Glyph.Repair -> Unit // Vectors above.
                Glyph.Arrow -> { line(4f, 12f, 20f, 12f); path(14f, 6f, 20f, 12f, 14f, 18f) }
                Glyph.Chevron -> path(9f, 6f, 15f, 12f, 9f, 18f)
                Glyph.Play -> drawPath(Path().apply { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }, color)
                Glyph.Import -> { line(12f, 3f, 12f, 14f); path(8f, 10f, 12f, 14f, 16f, 10f); path(4f, 14f, 4f, 20f, 20f, 20f, 20f, 14f) }
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
