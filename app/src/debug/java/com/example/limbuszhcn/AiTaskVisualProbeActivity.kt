package com.example.limbuszhcn

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import com.example.limbuszhcn.ui.theme.LauncherColors as C

/** Production components with local fixture data. No provider requests, saved task or game launch. */
class AiTaskVisualProbeActivity : ComponentActivity() {
    private var session: AiTaskService.Session? = null
    private var state by mutableStateOf(LauncherUiState())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        enableEdgeToEdge()
        val mode = intent.getStringExtra("mode") ?: "running"
        val now = SystemClock.elapsedRealtime()
        val progress = AiProgress(completed = 6, limit = 49, inputTokens = 7219, outputTokens = 8308,
            elapsedMillis = 716000, requests = 6, unknownUsage = true, price = AiPrice(1.0, 3.54), sampledAt = now,
            message = "等待翻译结果 · 本批 1 条")
        val task = AiTaskSnapshot("visual-task", AiConfig(), "zero", "都市零协会", "visual-output", "汉化包", null,
            progress = progress, plan = if (mode == "preparing") null else AiTaskPlan(mapOf("fixture" to (1..49).map { "$it" }.toSet()), emptyMap(), 49),
            explicitEntries = true)
        val running = mode != "paused" && mode != "error"
        state = LauncherUiState(busy = running, taskPage = "ai-translation", stage = if (mode == "preparing") "正在读取已保存的任务" else progress.message,
            workProgress = if (mode == "preparing") WorkProgress(startedAt = now) else WorkProgress(6, 49, now),
            ai = AiUiState(running = running, hasKey = true, task = task, progress = progress,
                failed = mode == "error", message = if (mode == "error") "翻译已暂停：网络连接中断，进度已保存。" else progress.message))
        setContent {
            LimbusZhCNTheme {
                Surface(Modifier.fillMaxSize(), color = C.Panel) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            PrimaryAction(state, {}, {}, {}, animated = true)
                            AiTaskCard(state, { pause() }, {}, {})
                        }
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (intent.getBooleanExtra("notice", false) && session == null && state.ai.running) {
            session = AiTaskService.start(this) { pause() }.also {
                it.update(state.stage, state.workProgress.done, state.workProgress.total)
                it.elapsed(state.ai.progress.elapsedMillis)
            }
        }
    }
    private fun pause() {
        state = state.copy(busy = false, ai = state.ai.copy(running = false, progress = state.ai.progress.freezeAt(SystemClock.elapsedRealtime())))
        session?.close(); session = null
    }
    override fun onDestroy() { session?.close(); session = null; super.onDestroy() }
}
