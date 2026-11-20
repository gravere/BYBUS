package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.AiTestResult
import com.example.limbuszhcn.ui.theme.LauncherColors as C
import java.util.Locale

@Composable
internal fun AiTestFeedback(result: AiTestResult) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LauncherGlyph(Glyph.Check, C.Accent, Modifier.size(16.dp))
            Text("测试通过", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = C.Accent)
            Text(String.format(Locale.ROOT, "%.1f 秒", result.elapsedMillis / 1000.0), color = C.Muted, style = MaterialTheme.typography.labelSmall)
        }
        Text("原文：${AiTestResult.ORIGINAL}", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        Text("译文：${result.translation}", style = MaterialTheme.typography.bodyMedium)
        Text("仅测试，未写入游戏汉化。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
    }
}
