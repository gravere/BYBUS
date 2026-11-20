package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.ui.theme.LauncherColors as C
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun AiPricingCard(endpoint: String, model: String, ai: AiUiState, actions: TranslationActions, enabled: Boolean) {
    val current = ai.pricing.takeIf { it.endpoint == endpoint && it.model == model }
    val price = current?.price
    var editing by remember(endpoint, model) { mutableStateOf(false) }
    var choosing by remember(endpoint, model) { mutableStateOf(false) }
    var input by remember(endpoint, model, editing) { mutableStateOf(price?.input?.toString().orEmpty()) }
    var output by remember(endpoint, model, editing) { mutableStateOf(price?.output?.toString().orEmpty()) }
    var cached by remember(endpoint, model, editing) { mutableStateOf(price?.cached?.toString().orEmpty()) }
    var currency by remember(endpoint, model, editing) { mutableStateOf(price?.currency ?: "USD") }
    var error by remember(editing) { mutableStateOf<String?>(null) }
    LaunchedEffect(endpoint, model) {
        delay(400)
        if (runCatching { normalizedAiEndpoint(endpoint); validAiModelId(model) }.getOrDefault(false)) actions.loadPrice(endpoint, model, false)
    }
    DetailCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("模型价格", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            ActionIconButton(Glyph.Refresh, "同步模型价格", { actions.loadPrice(endpoint, model, true) }, enabled = enabled, busy = current?.loading == true)
        }
        if (price == null) Text(if (current?.loading == true) "正在查询价格…" else "未自动匹配，可选择目录价格或手动填写", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        else {
            Text("输入 ${price.input} · 输出 ${price.output} ${price.currency} / 百万 Token", style = MaterialTheme.typography.bodyMedium)
            price.cached?.let { Text("缓存输入 $it ${price.currency} / 百万 Token", style = MaterialTheme.typography.bodySmall, color = C.Muted) }
            Text(price.reference?.let { "所选参考：$it" } ?: if (price.manual) "手动填写的参考价" else "Models.dev · ${java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(java.util.Date(price.fetchedAt))} · 参考价格", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        current?.message?.let { Text(it, color = C.Muted, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ choosing = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("选择价格", style = MaterialTheme.typography.labelMedium)
            }
            TextButton({ editing = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("手动填写", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (choosing) AiPricePicker(model, current, enabled, onRefresh = { actions.loadPrice(endpoint, model, true) },
        onDismiss = { choosing = false }, onSelect = { actions.savePrice(endpoint, model, it.selectedPrice()); choosing = false })
    if (editing) BybusDialog(onDismissRequest = { editing = false }, title = { Text("自定义模型价格") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val fieldColors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = C.Line,
                focusedBorderColor = C.Accent, unfocusedContainerColor = C.Background, focusedContainerColor = C.Background)
            Text("按每百万 Token 填写。留空缓存单价时，按普通输入计费。")
            listOf(Triple("输入单价", input, { s: String -> input = s }), Triple("输出单价", output, { s: String -> output = s }),
                Triple("缓存输入（可选）", cached, { s: String -> cached = s })).forEach { (label, value, change) ->
                OutlinedTextField(value, { if (it.length <= 20) change(it) }, Modifier.fillMaxWidth(), label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(12.dp), colors = fieldColors,
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next), textStyle = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(currency, { currency = it.take(3).uppercase(Locale.ROOT) }, Modifier.fillMaxWidth(), label = { Text("币种，例如 USD / CNY", style = MaterialTheme.typography.labelMedium) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp), colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done))
            error?.let { Text(it, color = C.Error) }
            if (price?.manual == true) TextButton({ actions.savePrice(endpoint, model, null); editing = false }, modifier = Modifier.fillMaxWidth()) { Text("恢复自动价格") }
        }
    }, confirmButton = { TextButton({
        runCatching { AiPrice(requireNotNull(input.toDoubleOrNull()), requireNotNull(output.toDoubleOrNull()),
            cached.takeIf(String::isNotBlank)?.let { requireNotNull(it.toDoubleOrNull()) }, currency, manual = true).validated() }
            .onSuccess { actions.savePrice(endpoint, model, it); editing = false }.onFailure { error = "请检查单价和币种" }
    }, enabled = enabled) { Text("保存") } }, dismissButton = { TextButton({ editing = false }) { Text("取消") } })
}

@Composable
internal fun AiUsageSummary(progress: AiProgress, running: Boolean = false) {
    val elapsed by produceState(progress.elapsedMillis, progress, running) {
        do {
            value = progress.elapsedAt(android.os.SystemClock.elapsedRealtime(), running)
            if (running) delay(1000)
        } while (running)
    }
    val seconds = elapsed / 1000
    val duration = if (seconds < 60) "${seconds}秒" else "${seconds / 60}分${seconds % 60}秒"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("用时 $duration", style = MaterialTheme.typography.bodySmall, color = C.Muted)
        if (progress.requests <= 0 && progress.inputTokens + progress.outputTokens == 0L) return@Column
        Text("${progress.inputTokens} 输入 · ${progress.outputTokens} 输出 Token", style = MaterialTheme.typography.bodySmall, color = C.Muted)
        val price = progress.price
        Text(if (progress.unknownUsage && progress.inputTokens + progress.outputTokens == 0L) "接口未返回用量，费用未知" else if (price == null) "任务开始时未设置价格，费用未知" else {
            val cost = price.cost(progress.inputTokens, progress.outputTokens, progress.cachedTokens)
            "${if (progress.unknownUsage) "已知用量估算" else "估算费用"} ${String.format(Locale.ROOT, "%.6f", cost)} ${price.currency}"
        }, style = MaterialTheme.typography.bodyMedium)
        if (progress.unknownUsage) Text("部分请求未返回用量，费用可能不完整。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
    }
}
