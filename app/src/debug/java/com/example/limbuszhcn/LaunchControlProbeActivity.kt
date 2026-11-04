package com.example.limbuszhcn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import com.example.limbuszhcn.ui.theme.LauncherColors as C

/** Draws the production control with explicit fixture states; never starts game preparation. */
class LaunchControlProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val step = intent.getIntExtra("step", -1).coerceIn(-2, 3)
        val animate = intent.getBooleanExtra("animate", false)
        setContent {
            LimbusZhCNTheme {
                Surface(Modifier.fillMaxSize(), color = C.Background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text("启动控件渲染", style = MaterialTheme.typography.titleMedium)
                        val title = when (step) {
                            0 -> "准备游戏"; 1 -> "检查汉化"; 2 -> "正在启动"
                            -2 -> "正在处理"; 3 -> "重新启动"; else -> "启动游戏"
                        }
                        Box(Modifier.fillMaxWidth().widthIn(max = 424.dp)) {
                            LaunchButton(title, step in 0..2 || step == -2, true, step, animate) {}
                        }
                        Text("独立控件状态，不执行游戏启动", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
