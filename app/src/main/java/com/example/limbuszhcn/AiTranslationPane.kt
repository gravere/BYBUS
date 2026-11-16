package com.example.limbuszhcn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.AiConfig
import com.example.limbuszhcn.translation.normalizedAiEndpoint
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun AiTranslationPane(state: LauncherUiState, actions: TranslationActions, onCoverage: () -> Unit = {}) {
    val ai = state.ai
    var endpoint by remember(ai.config.endpoint) { mutableStateOf(ai.config.endpoint) }
    var model by remember(ai.config.model) { mutableStateOf(ai.config.model) }
    var missing by remember(ai.config.missingOnly) { mutableStateOf(ai.config.missingOnly) }
    var limit by remember(ai.config.limit) { mutableIntStateOf(ai.config.limit) }
    // Never saved in a Bundle or ViewModel; the persisted value is encrypted separately.
    var key by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun finishEditing() { focus.clearFocus(); keyboard?.hide() }
    fun config() = AiConfig(endpoint.trim(), model.trim(), missing, limit)
    val enabled = !state.busy && !ai.settingsBusy && !ai.running
    val savedKeyAvailable = ai.hasKey && runCatching {
        normalizedAiEndpoint(endpoint) == normalizedAiEndpoint(ai.config.endpoint)
    }.getOrDefault(false)
    val hasCredential = key.isNotBlank() || savedKeyAvailable
    LaunchedEffect(endpoint, key) { actions.clearModels(); showModels = false; search = "" }
    LaunchedEffect(ai.models.request, ai.models.loading) {
        if (!ai.models.loading && ai.models.values.isNotEmpty()) showModels = true
    }
    DisposableEffect(Unit) { onDispose { actions.clearModels() } }
    Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CoverageSummary(state, onCoverage)
        DetailCard {
            Text("连接设置", style = MaterialTheme.typography.titleSmall)
            AiField("服务地址", endpoint, { if (it.length <= 2048) endpoint = it }, "https://api.openai.com/v1",
                enabled, type = KeyboardType.Uri)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("API Key", color = C.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    if (ai.hasKey) TextButton({ clear = true }, enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 8.dp)) { Text("移除已存 Key") }
                }
                AiInput(key, { if (it.length <= 1024) key = it }, "API Key",
                    if (savedKeyAvailable) "已安全保存，留空继续使用" else "翻译时需要填写",
                    enabled, type = KeyboardType.Password,
                    transformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = if (key.isNotEmpty()) ({ TextButton({ reveal = !reveal }) { Text(if (reveal) "隐藏" else "显示") } }) else null)
            }
            HorizontalDivider(color = C.Line)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("模型", color = C.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton({ finishEditing(); actions.loadModels(endpoint.trim(), key) },
                        enabled = enabled && !ai.models.loading && endpoint.isNotBlank(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        if (ai.models.loading) LauncherSpinner(Modifier.size(16.dp), strokeWidth = 1.6.dp)
                        else LauncherGlyph(Glyph.Refresh, C.Accent, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (ai.models.loading) "正在获取" else "获取模型")
                    }
                }
                AiInput(model, { if (it.length <= 128) model = it }, "模型", "填写模型 ID，或从上游选择", enabled,
                    type = KeyboardType.Ascii,
                    trailing = if (ai.models.values.isNotEmpty()) ({ ActionIconButton(Glyph.Chevron,
                        if (showModels) "收起模型列表" else "选择上游模型", { showModels = !showModels }, rotation = if (showModels) 270f else 90f) }) else null)
                ai.models.error?.let { Text(it, color = C.Error, style = MaterialTheme.typography.bodySmall) }
                AnimatedVisibility(showModels && ai.models.values.isNotEmpty()) {
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiInput(search, { search = it.take(128) }, "搜索上游模型", "搜索模型", enabled, type = KeyboardType.Text)
                        val matches = remember(ai.models.values, search) {
                            ai.models.values.filter { it.contains(search.trim(), ignoreCase = true) }
                        }
                        if (matches.isEmpty()) Text("没有匹配的模型，仍可手动填写", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(14.dp)).background(C.Panel)) {
                            items(matches, key = { it }) { id ->
                                Row(Modifier.fillMaxWidth().clickable(enabled = enabled) {
                                    model = id; showModels = false; finishEditing()
                                }.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(id, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    if (id == model) LauncherGlyph(Glyph.Check, C.Accent, Modifier.padding(start = 8.dp).size(18.dp))
                                }
                            }
                        }
                        Text("来自上游的 ${ai.models.values.size} 个模型，请选择支持文本对话的模型。",
                            color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton({ finishEditing(); val value = key; key = ""; reveal = false; actions.saveAi(config(), value, false) },
                    enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("保存设置") }
                TextButton({ finishEditing(); reveal = false; actions.saveAi(config(), key, true) },
                    enabled = enabled && hasCredential, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (ai.settingsBusy) "处理中" else "测试翻译") }
            }
            ai.message?.takeUnless { ai.running }?.let {
                Text(it, color = if (ai.failed) C.Error else C.Accent, style = MaterialTheme.typography.bodySmall)
            }
        }
        DetailCard {
            Text("翻译方式", style = MaterialTheme.typography.titleSmall)
            SourceTabs(listOf(true, false), missing, { if (it) "补全缺失" else "独立翻译" }, "翻译方式", { if (enabled) missing = it })
            Text("单次最多翻译", style = MaterialTheme.typography.titleSmall)
            SourceTabs(listOf(100, 500, 1000), limit, { "$it 条" }, "翻译上限", { if (enabled) limit = it })
            Text("按已下载的游戏文本分批处理。测试和翻译会使用你的 API 额度，已完成的译文会保留。",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (ai.running) {
            DetailCard {
                LinearProgressIndicator(progress = { (ai.progress.completed.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(ai.message.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                TextButton(actions.cancelAi, modifier = Modifier.fillMaxWidth()) { Text("暂停翻译") }
            }
        } else {
            Button({ finishEditing(); confirm = true }, enabled = enabled && hasCredential,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("生成并使用") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("开始 AI 翻译？") },
        text = { Text("将关闭游戏，并向你配置的 API 发送游戏文本。本批最多处理 $limit 条，费用由服务商计入你的账户。生成的汉化独立保存，账号和原汉化包会保留。") },
        confirmButton = { TextButton({ confirm = false; val value = key; key = ""; reveal = false; actions.generateAi(config(), value) }, enabled = enabled) { Text("开始翻译") } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("移除 API Key？") },
        text = { Text("已生成的译文会保留。再次使用 API 时需要重新填写 Key。") },
        confirmButton = { TextButton({ clear = false; key = ""; reveal = false; actions.clearModels(); actions.clearKey() }, enabled = enabled) { Text("移除") } },
        dismissButton = { TextButton({ clear = false }) { Text("取消") } })
}

@Composable
private fun AiField(title: String, value: String, onValue: (String) -> Unit, placeholder: String,
    enabled: Boolean, type: KeyboardType) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = C.Muted, style = MaterialTheme.typography.labelMedium)
        AiInput(value, onValue, title, placeholder, enabled, type)
    }
}

@Composable
private fun AiInput(value: String, onValue: (String) -> Unit, description: String, placeholder: String,
    enabled: Boolean, type: KeyboardType, transformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val outline by animateColorAsState(if (focused) C.Accent else C.Line.copy(alpha = .6f), tween(140), label = "AI input focus")
    val shape = RoundedCornerShape(14.dp)
    val focus = LocalFocusManager.current
    OutlinedTextField(value, onValue, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        enabled = enabled, shape = shape, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
        visualTransformation = transformation, interactionSource = interactions, trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next, autoCorrect = false),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = outline, unfocusedBorderColor = outline,
            focusedContainerColor = C.Panel, unfocusedContainerColor = C.Panel, disabledContainerColor = C.Panel,
            cursorColor = C.Accent, focusedTextColor = C.Ink, unfocusedTextColor = C.Ink))
}
