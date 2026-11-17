package com.example.limbuszhcn

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun AiUpstreamDialog(offer: AiUpstreamOffer, hasKey: Boolean, enabled: Boolean,
    onChoice: (AiUpdateChoice?) -> Unit, onConfigure: () -> Unit) {
    var choice by remember(offer.sourceId, offer.revision, offer.version) { mutableStateOf(AiUpdateChoice.Reuse) }
    AlertDialog(onDismissRequest = { if (enabled) onChoice(null) }, containerColor = C.Panel,
        title = { Text(if (offer.baseVersion == null) "更新汉化" else "零协汉化有更新") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择更新后的汉化方式，旧 AI 包会保留。", style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(AiUpdateChoice.Official to "只更新零协", AiUpdateChoice.Reuse to "更新零协，保留 AI",
                        AiUpdateChoice.Supplement to "更新后继续补译").forEach { (value, label) ->
                        val available = enabled && (value != AiUpdateChoice.Supplement || hasKey)
                        AiUpdateOption(label, when (value) {
                            AiUpdateChoice.Official -> "暂不使用 AI 译文，缺少的内容保留原文。"
                            AiUpdateChoice.Reuse -> "零协补齐的用零协，原文没变的沿用已有 AI 译文。"
                            else -> "保留可用 AI 译文，再补译最多 ${offer.config.limit} 条。"
                        }, choice == value, available, { choice = value },
                            if (value == AiUpdateChoice.Supplement) {
                                { Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 12.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (hasKey) "消耗 API 额度" else "需先设置 API · 补译会收费",
                                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = C.Muted)
                                    if (!hasKey) TextButton(onConfigure, enabled = enabled,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text("设置") }
                                } }
                            } else null)
                    }
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ onChoice(choice) }, enabled = enabled && (choice != AiUpdateChoice.Supplement || hasKey), modifier = Modifier.fillMaxWidth()) {
                    Text(if (choice == AiUpdateChoice.Supplement) {
                        if (offer.launchAfter) "补译后进入游戏" else "补译并使用"
                    } else if (offer.launchAfter) "更新后进入游戏" else "更新并使用")
                }
                OutlinedButton({ onChoice(AiUpdateChoice.Current) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (offer.launchAfter) "继续当前汉化进入" else "保留当前汉化")
                }
                TextButton({ onChoice(null) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        })
}

@Composable
private fun AiUpdateOption(title: String, detail: String, selected: Boolean, enabled: Boolean,
    onSelect: () -> Unit, footer: (@Composable () -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    val fill by animateColorAsState(if (selected) C.AccentSoft.copy(alpha = .42f) else C.Panel,
        tween(140), label = "translation-choice")
    Column(Modifier.fillMaxWidth().clip(shape).background(fill)
        .border(1.dp, if (selected) C.Accent.copy(alpha = .4f) else C.Line, shape)) {
        Row(Modifier.fillMaxWidth().selectable(selected, enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(12.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) C.Ink else C.Muted)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
        }
        footer?.invoke()
    }
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
