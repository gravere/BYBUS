package com.example.limbuszhcn

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.limbuszhcn.ui.theme.LauncherColors as C

internal enum class ConfirmedOperation(val title: String, val message: String, val action: String,
                                      val destructive: Boolean = false) {
    Repair("开始修复？", "将关闭游戏，重新同步安装文件并重建汉化。账号和已下载的游戏资源会保留。", "开始修复"),
    Translation("更新汉化资源？", "将关闭游戏并检查、更新汉化资源。账号和游戏资源会保留。", "继续更新"),
    ClearResources("清除游戏资源？", "将关闭游戏并删除 BYBUS 内下载的游戏资源与缓存。此操作无法撤销，下次进入游戏需要重新下载。账号、Google 服务和系统原版游戏会保留。", "确认清除", true),
    Migrate("导入旧版数据？", "将关闭容器内的游戏与服务，用旧版数据替换新版的同名数据和账号配置。旧版中的数据会保留。", "确认迁移", true),
    ImportResources("导入游戏资源？", "将关闭容器内的游戏与服务，校验并导入所选资源包。已有文件和账号会保留。", "确认导入")
}

@Composable
internal fun OperationConfirmation(operation: ConfirmedOperation, enabled: Boolean,
                                   onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(24.dp), containerColor = C.Panel,
        title = { Text(operation.title) }, text = { Text(operation.message) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = if (operation.destructive) C.Error else C.Accent)) {
                Text(operation.action)
            }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
