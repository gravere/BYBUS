package com.example.limbuszhcn

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.limbuszhcn.translation.AiBackgroundAccess
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun BackgroundSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var exempt by remember { mutableStateOf(AiBackgroundAccess.allowed(context)) }
    var unavailable by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = AiBackgroundAccess.allowed(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val identity = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
    val vivo = listOf("vivo", "iqoo", "bbk").any(identity::contains)
    BybusDialog(onDismissRequest = onDismiss, title = { Text("后台运行") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("让翻译在切换应用或锁屏后继续，无需开启自启动。")
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("后台耗电", style = MaterialTheme.typography.titleSmall)
                Text(if (vivo) "设置 → 电池 → 后台耗电管理 → BYBUS，选择「允许后台高耗电」。"
                    else "在 BYBUS 应用设置的「电池」中，允许后台运行或选择「不受限制」。",
                    style = MaterialTheme.typography.bodyMedium)
                Text("这项设置由系统管理，BYBUS 无法读取当前选择。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (exempt) "电池优化：已豁免" else "电池优化：未豁免", style = MaterialTheme.typography.titleSmall)
                Text("电池优化豁免与上面的后台耗电设置是两项独立设置。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                if (!exempt) TextButton(onClick = { unavailable = !AiBackgroundAccess.open(context) }) {
                    Text("申请电池优化豁免", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (unavailable) Text("系统未提供可打开的页面，请按上方路径手动设置。", color = C.Error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClick = { unavailable = !AiBackgroundAccess.openSettings(context) }) { Text("应用设置") }
    }, dismissButton = { TextButton(onDismiss) { Text("关闭") } })
}
