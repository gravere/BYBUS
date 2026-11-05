package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

/** One quiet surface for transfer status and its action, aligned with the page cards. */
@Composable
internal fun UpdateActionCard(
    message: String?, icon: Glyph, busy: Boolean, progress: Int? = null,
    failed: Boolean = false, description: String, progressDescription: String,
    horizontalPadding: Dp = 24.dp, action: String? = null, actionEnabled: Boolean = true,
    onAction: () -> Unit = {}, onCancel: (() -> Unit)? = null
) {
    Box(Modifier.fillMaxWidth().padding(start = horizontalPadding, end = horizontalPadding,
        top = 8.dp, bottom = 16.dp)) {
        Surface(color = C.Background, contentColor = C.Ink, shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!message.isNullOrBlank()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LauncherGlyph(icon, if (failed) C.Error else C.Muted, Modifier.size(20.dp))
                        Text(message, color = if (failed) C.Error else C.Muted,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (busy && progress != null) {
                            Text("${progress.coerceIn(0, 100)}%", color = C.Accent,
                                style = MaterialTheme.typography.labelLarge)
                        }
                        if (busy && onCancel != null) {
                            FilledTonalIconButton(onClick = onCancel, modifier = Modifier.size(48.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = C.Panel, contentColor = C.Muted)) {
                                LauncherGlyph(Glyph.Close, C.Muted, Modifier.size(18.dp), "取消更新")
                            }
                        }
                    }
                }
                if (busy) {
                    val track = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                        .semantics { contentDescription = progressDescription }
                    if (progress == null) {
                        LinearProgressIndicator(modifier = track, color = C.Accent, trackColor = C.Line)
                    } else {
                        LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f },
                            modifier = track, color = C.Accent, trackColor = C.Line)
                    }
                }
                if (action != null) {
                    FilledTonalButton(onClick = onAction, enabled = actionEnabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = C.AccentSoft, contentColor = C.Accent)) {
                        Text(action)
                    }
                }
            }
        }
    }
}
