package com.example.limbuszhcn

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.ui.theme.LauncherColors as C
import java.math.BigDecimal

internal fun priceNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

@Composable
internal fun AiPricePicker(model: String, state: AiPricingState?, enabled: Boolean,
    onRefresh: () -> Unit, onDismiss: () -> Unit, onSelect: (AiPriceOption) -> Unit) {
    var query by remember { mutableStateOf(model) }
    var selected by remember { mutableStateOf<AiPriceOption?>(null) }
    val options = state?.options.orEmpty()
    val matches = remember(options, query) { searchPriceOptions(options, query) }
    // Refresh may replace a catalogue price; require a fresh selection before confirming it.
    val choice = selected?.takeIf { options.any { option -> option == it } }
    BybusDialog(onDismissRequest = onDismiss, title = { Text("选择参考价格") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(query, { query = it.take(160) }, Modifier.fillMaxWidth().semantics { contentDescription = "搜索价格模型" },
                placeholder = { Text("模型名称、ID 或服务商", style = MaterialTheme.typography.bodyMedium) },
                textStyle = MaterialTheme.typography.bodyMedium, singleLine = true, shape = RoundedCornerShape(12.dp),
                trailingIcon = { if (query.isNotEmpty()) ActionIconButton(Glyph.Close, "清空价格搜索", { query = "" }) })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state?.loading == true) "正在同步目录" else "${matches.size} 项 · USD / 百万 Token", Modifier.weight(1f),
                    color = C.Muted, style = MaterialTheme.typography.labelMedium)
                ActionIconButton(Glyph.Refresh, "刷新价格目录", onRefresh, enabled = enabled, busy = state?.loading == true)
            }
            if (matches.isEmpty()) Text(if (options.isEmpty()) state?.message ?: "价格目录尚未加载，请点刷新" else "没有找到，可缩短关键词或清空搜索",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(matches, key = { it.id }) { option ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (choice?.id == option.id) C.AccentSoft else C.Background)
                        .clickable(enabled = enabled) { selected = option }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(option.name, style = MaterialTheme.typography.bodyMedium)
                            Text("${option.provider} · ${option.model}", color = C.Muted, style = MaterialTheme.typography.labelSmall)
                            Text("输入 ${priceNumber(option.price.input)} · 输出 ${priceNumber(option.price.output)}" +
                                option.price.cached?.let { " · 缓存 ${priceNumber(it)}" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        }
                        if (choice?.id == option.id) LauncherGlyph(Glyph.Check, C.Accent, Modifier.padding(start = 8.dp).size(18.dp))
                    }
                }
            }
            Text("只用于费用估算，不会更换翻译模型。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ choice?.let(onSelect) }, enabled = enabled && choice != null && state?.loading != true) { Text("使用此价格") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
