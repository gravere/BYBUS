package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

internal enum class RepairPhase { Idle, Running, Complete, Failed }
internal data class RepairState(val phase: RepairPhase = RepairPhase.Idle, val step: Int = -1,
                                val message: String? = null)

@Composable
internal fun RepairPane(state: LauncherUiState, onRepair: () -> Unit, onExport: () -> Unit) {
    // The operation lock/migration guard can reject a repair before its first step.
    val repair = if (state.failed && state.stage == "正在修复")
        state.repair.copy(phase = RepairPhase.Failed, message = state.message) else state.repair
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(Glyph.Repair, accent = true)
            Text("修复汉化与启动文件", style = MaterialTheme.typography.titleMedium)
        }
        Text("修复时会关闭游戏，账号和已下载的游戏资源会保留。", style = MaterialTheme.typography.bodyMedium)
        DetailCard {
            listOf("重新同步游戏", "校验中文字体", "重建汉化文件").forEachIndexed { index, title ->
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val completed = repair.phase == RepairPhase.Complete || repair.step > index
                    if (repair.phase == RepairPhase.Running && repair.step == index)
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.Accent)
                    else if (completed) LauncherGlyph(Glyph.Check, C.Accent, Modifier.size(20.dp))
                    else Text("${index + 1}", modifier = Modifier.width(20.dp), color = C.Muted,
                        style = MaterialTheme.typography.labelLarge)
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        repair.message?.let {
            Text(it, color = if (repair.phase == RepairPhase.Failed) C.Error else C.Ink,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.installedVersion == null && !state.busy)
            Text("请先安装官方游戏。", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRepair, enabled = !state.busy && state.installedVersion != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(when (repair.phase) {
                RepairPhase.Running -> "正在修复"
                RepairPhase.Complete -> "再次修复"
                RepairPhase.Failed -> "重试修复"
                else -> "开始修复"
            })
        }
        if (repair.phase == RepairPhase.Failed)
            OutlinedButton(onClick = onExport, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("导出诊断") }
    }
}
