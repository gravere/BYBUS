package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun AiUpstreamDialog(offer: AiUpstreamOffer, hasKey: Boolean, enabled: Boolean,
    onChoice: (AiUpdateChoice?) -> Unit, onConfigure: () -> Unit) {
    var choice by remember(offer.sourceId, offer.revision, offer.version) { mutableStateOf(AiUpdateChoice.Reuse) }
    AlertDialog(onDismissRequest = { if (enabled) onChoice(null) },
        title = { Text(if (offer.baseVersion == null) "同步零协汉化" else "零协汉化有更新") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (offer.baseVersion == null) "当前 AI 包未记录零协版本。可继续使用，或同步至 ${offer.version}。"
                    else "零协已更新至 ${offer.version}，选择更新后的汉化方式。")
                Column(Modifier.selectableGroup()) {
                    listOf(AiUpdateChoice.Official to "仅使用零协译文", AiUpdateChoice.Reuse to "沿用已有 AI 译文",
                        AiUpdateChoice.Supplement to "复用后补译一批").forEach { (value, label) ->
                        val available = enabled && (value != AiUpdateChoice.Supplement || hasKey)
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .selectable(choice == value, available, role = Role.RadioButton, onClick = { choice = value }),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(choice == value, onClick = null, enabled = available)
                            Text(label, Modifier.padding(start = 12.dp), color = if (available) C.Ink else C.Muted)
                        }
                    }
                }
                Text(when (choice) {
                    AiUpdateChoice.Supplement -> "先复用已有译文，再补译最多 ${offer.config.limit} 条未覆盖内容。使用已保存的 API 设置，新增请求会消耗额度。"
                    AiUpdateChoice.Reuse -> "零协新译文优先；仍缺失的部分复用原文未变的 AI 译文。不会调用 API，其余内容保留原文。"
                    else -> "更新后使用零协译文，不调用 API。"
                }, style = MaterialTheme.typography.bodySmall)
                Text("旧 AI 包会保留。同一个零协版本只提醒一次，也可稍后手动检查更新。", style = MaterialTheme.typography.bodySmall)
                if (!hasKey) TextButton(onConfigure, enabled = enabled) { Text("设置 AI 翻译") }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ onChoice(choice) }, enabled = enabled && (choice != AiUpdateChoice.Supplement || hasKey), modifier = Modifier.fillMaxWidth()) {
                    Text(if (!offer.launchAfter) "更新并使用" else if (choice == AiUpdateChoice.Supplement) "补译后进入游戏" else "更新后进入游戏")
                }
                OutlinedButton({ onChoice(AiUpdateChoice.Current) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (offer.launchAfter) "继续当前汉化进入" else "保留当前汉化")
                }
                TextButton({ onChoice(null) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        })
}

@Composable
internal fun AiLaunchProgressDialog(state: LauncherUiState, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, title = { Text("正在准备 AI 汉化") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.ai.message.orEmpty())
            Text("已复用 ${state.ai.progress.reused} 条 · 本次新译 ${state.ai.progress.completed} 条", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onCancel) { Text("取消本次启动") } })
}
