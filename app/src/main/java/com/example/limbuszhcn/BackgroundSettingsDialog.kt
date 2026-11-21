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
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (vivo) "选择「允许后台高耗电」" else "允许 BYBUS 后台运行", style = MaterialTheme.typography.titleSmall)
                Text(if (vivo) "设置 → 电池 → 后台耗电管理 → BYBUS"
                    else "应用设置 → 电池 → 不受限制",
                    color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                Text("锁屏后继续翻译，无需开启自启动。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = C.Line)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("电池优化", style = MaterialTheme.typography.bodyMedium)
                if (exempt) Text("已豁免", color = C.Accent, style = MaterialTheme.typography.labelMedium)
                else TextButton(onClick = { unavailable = !AiBackgroundAccess.open(context) }) {
                    Text("去豁免", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (unavailable) Text("系统未提供可打开的页面，请按上方路径手动设置。", color = C.Error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClick = { unavailable = !AiBackgroundAccess.openSettings(context) }) { Text("去设置") }
    }, dismissButton = { TextButton(onDismiss) { Text("关闭") } })
}
