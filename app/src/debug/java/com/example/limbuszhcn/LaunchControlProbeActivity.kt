package com.example.limbuszhcn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import com.example.limbuszhcn.ui.theme.LauncherColors as C
import com.example.limbuszhcn.container.ContainerStatus

/** Draws the production control with explicit fixture states; never starts game preparation. */
class LaunchControlProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val step = intent.getIntExtra("step", -1).coerceIn(-2, 3)
        val animate = intent.getBooleanExtra("animate", false)
        val sync = intent.getBooleanExtra("sync", false)
        setContent {
            LimbusZhCNTheme {
                Surface(Modifier.fillMaxSize(), color = C.Background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text("启动控件渲染", style = MaterialTheme.typography.titleMedium)
                        var state by remember { mutableStateOf(LauncherUiState(
                            container = ContainerStatus(true, true, "fixture"), installedVersion = 1L,
                            compatibility = DeviceCompatibilityReport("fixture", "Android 15", 35, "arm64-v8a",
                                true, 4096L, true, false, true, emptyList()),
                            busy = sync || step in 0..2 || step == -2,
                            launchStep = if (sync) 0 else step,
                            stage = if (sync) "同步游戏，保留已有数据" else "详细准备状态仅在运行记录和无障碍状态中显示")) }
                        Box(Modifier.widthIn(max = 424.dp).fillMaxWidth()) {
                            PrimaryAction(state, onLaunch = { state = state.copy(busy = true, launchStep = 2, stage = "启动回调已触发") },
                                onRefresh = {}, onDetails = {}, animated = animate)
                        }
                        if (sync) TextButton(onClick = { state = state.copy(busy = false, launchStep = -1) }) { Text("完成同步") }
                        Text("独立控件状态，不执行游戏启动", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
