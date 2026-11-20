package com.example.limbuszhcn

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C
import kotlinx.coroutines.delay

internal data class WorkProgress(val done: Int = 0, val total: Int = 0, val startedAt: Long = 0) {
    val fraction: Float? get() = if (total > 0) done.coerceIn(0, total).toFloat() / total else null
}

@Composable
internal fun operationSeconds(state: LauncherUiState): Long {
    val work = state.workProgress
    val seconds by produceState(0L, work.startedAt, state.busy) {
        while (state.busy && work.startedAt > 0) {
            value = ((android.os.SystemClock.elapsedRealtime() - work.startedAt) / 1000).coerceAtLeast(0); delay(1000)
        }
    }
    return seconds
}

@Composable
internal fun OperationProgress(state: LauncherUiState) {
    val work = state.workProgress
    val seconds = operationSeconds(state)
    val fraction by animateFloatAsState(work.fraction ?: 0f, tween(200), label = "operation progress")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(state.stage, style = MaterialTheme.typography.titleSmall)
        if (work.fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (work.total > 0) "${work.done} / ${work.total} · ${(fraction * 100).toInt()}%"
                else if (state.launchStep in 0..2) "第 ${state.launchStep + 1} / 3 步" else "处理中",
                style = MaterialTheme.typography.bodySmall, color = C.Muted)
            Text("已用 ${seconds} 秒", style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
    }
}
