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
import com.example.limbuszhcn.translation.AiScope
import com.example.limbuszhcn.translation.AiTranslationPrompt
import com.example.limbuszhcn.translation.normalizedAiEndpoint
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun AiTranslationPane(state: LauncherUiState, actions: TranslationActions, onCoverage: () -> Unit = {}, requestedScope: AiScope? = null) {
    val ai = state.ai
    var endpoint by remember(ai.config.endpoint) { mutableStateOf(ai.config.endpoint) }
    var model by remember(ai.config.model) { mutableStateOf(ai.config.model) }
    var missing by remember(ai.config.missingOnly, requestedScope) { mutableStateOf(requestedScope != null || ai.config.missingOnly) }
    var scope by remember(ai.config.includeMissing, ai.config.includeReview, requestedScope) {
        mutableStateOf(requestedScope ?: if (ai.config.includeReview) if (ai.config.includeMissing) AiScope.Both else AiScope.Review else AiScope.Missing)
    }
    var limit by remember(ai.config.limit) { mutableIntStateOf(ai.config.limit) }
    // Never saved in a Bundle or ViewModel; the persisted value is encrypted separately.
    var key by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf(false) }
    var prompt by remember(ai.config.prompt) { mutableStateOf(ai.config.prompt) }
    var editPrompt by remember { mutableStateOf(false) }
    var resetPrompt by remember { mutableStateOf(false) }
    var restoreDefault by remember { mutableStateOf(false) }
    var deleteAi by remember { mutableStateOf<com.example.limbuszhcn.translation.TranslationSource?>(null) }
    var showModels by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun finishEditing() { focus.clearFocus(); keyboard?.hide() }
    fun config() = AiConfig(endpoint.trim(), model.trim(), missing, limit, scope.missing, scope.review, prompt)
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("全局提示词", style = MaterialTheme.typography.titleSmall)
                    Text(if (prompt == AiTranslationPrompt.DEFAULT) "零协风格参考 · 默认" else "自定义", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ editPrompt = !editPrompt }, enabled = enabled) { Text(if (editPrompt) "收起" else "编辑") }
            }
            AnimatedVisibility(editPrompt) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(prompt, { if (it.length <= AiTranslationPrompt.MAX_LENGTH) prompt = it },
                        Modifier.fillMaxWidth().semantics { contentDescription = "全局翻译提示词" }, enabled = enabled,
                        minLines = 6, maxLines = 12, shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = C.Panel, unfocusedContainerColor = C.Panel,
                            focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line))
                    Text("影响之后的翻译请求，已有译文不会自动重译。数值、格式和明确译名仍会校验。", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({ finishEditing(); actions.savePrompt(prompt) }, enabled = enabled && prompt.isNotBlank(), modifier = Modifier.weight(1f)) { Text("保存提示词") }
                        TextButton({ finishEditing(); resetPrompt = true }, enabled = enabled && prompt != AiTranslationPrompt.DEFAULT, modifier = Modifier.weight(1f)) { Text("恢复默认") }
                    }
                }
            }
        }
        DetailCard {
            Text("翻译方式", style = MaterialTheme.typography.titleSmall)
            SourceTabs(listOf(true, false), missing, { if (it) "补充当前汉化" else "独立翻译" }, "翻译方式", { if (enabled) missing = it })
            if (missing) {
                Text("处理范围", style = MaterialTheme.typography.titleSmall)
                SourceTabs(AiScope.entries.toList(), scope, { it.label }, "处理范围", { if (enabled) scope = it })
                if (scope.review) Text("待核对不一定是漏译，请先查看原文与现有译文。处理结果另存，原包保留。", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
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
        if (state.sources.undo != null) OutlinedButton({ finishEditing(); undo = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("撤回上次 AI 生成") }
        val generated = state.sources.sources.filter { it.kind == "ai" }
        if (generated.isNotEmpty()) DetailCard {
            Text("已生成的汉化", style = MaterialTheme.typography.titleSmall)
            SheetAction(Glyph.Translation, "恢复默认汉化", enabled && !state.sources.selected.isOfficial,
                onClick = { finishEditing(); restoreDefault = true })
            generated.forEach { source ->
                HorizontalDivider(color = C.Line)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TranslationSourceIcon(source, Modifier.size(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.bodyMedium)
                        if (source.id == state.sources.selectedId) Text("当前使用", color = C.Accent,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    ActionIconButton(Glyph.Delete, "删除 ${source.name}",
                        { finishEditing(); deleteAi = source }, enabled = enabled)
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("开始 AI 翻译？") },
        text = { Text("将关闭游戏，处理${if (missing) scope.label else "全部"}文本，本批最多 $limit 条。文本会发送到你配置的 API，费用计入你的账户。原汉化包保留，生成后可撤回。") },
        confirmButton = { TextButton({ confirm = false; val value = key; key = ""; reveal = false; actions.generateAi(config(), value) }, enabled = enabled) { Text("开始翻译") } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("移除 API Key？") },
        text = { Text("已生成的译文会保留。再次使用 API 时需要重新填写 Key。") },
        confirmButton = { TextButton({ clear = false; key = ""; reveal = false; actions.clearModels(); actions.clearKey() }, enabled = enabled) { Text("移除") } },
        dismissButton = { TextButton({ clear = false }) { Text("取消") } })
    if (undo) AlertDialog(onDismissRequest = { undo = false }, title = { Text("撤回上次 AI 生成？") },
        text = { Text("将关闭游戏，恢复上次生成前的汉化和来源选择。游戏资源、账号与 API 设置会保留；已产生的 API 费用不会返还。") },
        confirmButton = { TextButton({ undo = false; actions.undoAi() }, enabled = enabled) { Text("撤回") } },
        dismissButton = { TextButton({ undo = false }) { Text("取消") } })
    if (resetPrompt) AlertDialog(onDismissRequest = { resetPrompt = false }, title = { Text("恢复默认提示词？") },
        text = { Text("将替换当前输入的自定义提示词，保存后生效。已有汉化包不会改变。") },
        confirmButton = { TextButton({ resetPrompt = false; prompt = AiTranslationPrompt.DEFAULT }, enabled = enabled) { Text("恢复") } },
        dismissButton = { TextButton({ resetPrompt = false }) { Text("取消") } })
    if (restoreDefault) AlertDialog(onDismissRequest = { restoreDefault = false }, title = { Text("恢复默认汉化？") },
        text = { Text("将关闭游戏并切回都市零协会，下次启动检查正式汉化更新。已生成的 AI 汉化会保留，可随时重新选择；游戏数据与账号不变。") },
        confirmButton = { TextButton({ restoreDefault = false; actions.select("zero") }, enabled = enabled) { Text("恢复") } },
        dismissButton = { TextButton({ restoreDefault = false }) { Text("取消") } })
    deleteAi?.let { source -> AlertDialog(onDismissRequest = { deleteAi = null }, title = { Text("删除 AI 汉化？") },
        text = { Text("删除“${source.name}”及其生成缓存，不能撤回。${if (source.id == state.sources.selectedId) "将关闭游戏并切回都市零协会。" else "当前使用的汉化不变。"}游戏数据、账号、其他汉化源与 API 设置会保留。") },
        confirmButton = { TextButton({ deleteAi = null; actions.removeAi(source.id) }, enabled = enabled) { Text("删除") } },
        dismissButton = { TextButton({ deleteAi = null }) { Text("取消") } }) }
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
