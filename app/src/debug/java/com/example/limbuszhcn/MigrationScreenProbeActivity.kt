package com.example.limbuszhcn

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.example.limbuszhcn.migration.MigrationOperation
import com.example.limbuszhcn.migration.MigrationScreen
import com.example.limbuszhcn.migration.MigrationState
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme

/** Production screen with explicit display fixtures. No provider or game data is accessed. */
class MigrationScreenProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val ready = MigrationState(ready = true, hasLegacy = true, total = 12_700_000_000L)
        val fixture = when (intent.getStringExtra("state")) {
            "ready" -> ready
            "update" -> MigrationState(hasLegacy = true)
            "copy" -> ready.copy(busy = true, pending = true, operation = MigrationOperation.Legacy,
                copied = 6_350_000_000L, message = "正在复制数据")
            "merge" -> MigrationState(busy = true, pending = true, operation = MigrationOperation.Resources,
                copied = 16, total = 32, progressFiles = true, message = "正在合并资源")
            "complete" -> ready.copy(done = true, operation = MigrationOperation.Legacy, copied = ready.total)
            "error" -> ready.copy(pending = true, failed = true, operation = MigrationOperation.Legacy,
                message = "剩余空间不足。请先释放空间，旧数据仍保留。")
            else -> MigrationState()
        }
        setContent {
            var state by remember { mutableStateOf(fixture) }
            LimbusZhCNTheme {
                MigrationScreen(state, onBack = ::finish, onRefresh = { state = ready },
                    onMigrate = { state = ready.copy(busy = true, operation = MigrationOperation.Legacy,
                        message = "正在准备游戏…") }, onSelectFile = {}, onOpenLegacy = {}, onFinish = ::finish)
            }
        }
    }
}
