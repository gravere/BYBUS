package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

internal data class DataClearState(val running: Boolean = false, val failed: Boolean = false,
                                   val message: String? = null)

internal fun formatResourceBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1000L -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1000.0)
    else -> "$bytes B"
}

@Composable
internal fun ClearDataPane(state: LauncherUiState, onClear: () -> Unit) {
    val clear = if (state.failed && state.stage == "正在清除游戏资源")
        state.clearing.copy(running = false, failed = true,
            message = if (state.clearing.failed) state.clearing.message else state.message)
        else state.clearing
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(Glyph.Delete)
            Text("游戏资源与缓存", style = MaterialTheme.typography.titleMedium)
        }
        DetailCard {
            Text("清除 BYBUS 内下载的游戏资源与缓存，释放存储空间。", style = MaterialTheme.typography.bodyMedium)
            Text("账号、Google 服务和系统原版游戏会保留。下次进入游戏需要重新下载资源。",
                style = MaterialTheme.typography.bodyMedium)
        }
        clear.message?.let {
            Text(it, color = if (clear.failed) C.Error else C.Ink, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        OutlinedButton(onClick = onClear, enabled = !state.busy && state.container?.available == true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Error)) {
            if (clear.running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = C.Error)
                Spacer(Modifier.width(10.dp))
            }
            Text(if (clear.running) "正在清除" else "清除游戏资源")
        }
    }
}
