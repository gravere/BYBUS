package com.example.limbuszhcn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.container.ContainerStatus
import com.example.limbuszhcn.selfupdate.AppRelease
import com.example.limbuszhcn.selfupdate.AppUpdateState
import com.example.limbuszhcn.selfupdate.ReleaseAsset
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import com.example.limbuszhcn.ui.theme.LauncherColors as C

/** Explicit rendering fixtures only: no repository, downloads, installation or game calls. */
class UpdateControlProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val case = intent.getStringExtra("case") ?: "download"
        val release = AppRelease("fixture", "界面渲染", 2, 26, "0".repeat(64),
            ReleaseAsset(1, "fixture.apk", 120_000_000),
            "- 状态与操作合并在同一张卡片中\n- 下载进度保留在操作区域")
        val initial = when (case) {
            "available" -> AppUpdateState(1, release = release, message = "有新版本可用")
            "ready" -> AppUpdateState(1, release = release, ready = true, message = "已准备好安装")
            "latest" -> AppUpdateState(2, release = release, checked = true, message = "已是最新版本")
            "error" -> AppUpdateState(1, release = release, message = "更新未完成", error = "连接中断，请稍后重试")
            else -> AppUpdateState(1, busy = true, release = release, progress = 46,
                message = "正在下载更新 · 1.6 MB / 3.5 MB")
        }
        setContent {
            LimbusZhCNTheme {
                var state by remember { mutableStateOf(initial) }
                val translation = case == "translation"
                val launcher = LauncherUiState(container = ContainerStatus(true, true, "渲染状态"),
                    busy = translation, stage = "检查零协更新并校验汉化文件", message = "正在校验本地汉化文件")
                Surface(Modifier.fillMaxSize(), color = C.Background) {
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                        val layout = LauncherLayout.calculate(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale)
                        Text("更新控件渲染 · 不执行下载或安装", Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall)
                        LauncherDetailSurface(layout, "app-update", onDismiss = { finish() },
                            navigation = { UpdateTabs(if (translation) UpdateTab.Translation else UpdateTab.App, translation) {} },
                            footer = {
                                if (translation) TranslationUpdateControls(launcher, {}, layout.detailPadding.dp)
                                else AppUpdateControls(state, launcher, {}, {},
                                    { state = state.copy(busy = false, progress = null, message = "已取消，可重试") }, layout.detailPadding.dp)
                            }) {
                            if (translation) TranslationUpdatesPane(launcher) else AppUpdatesPane(state, {}, {})
                        }
                    }
                }
            }
        }
    }
}
