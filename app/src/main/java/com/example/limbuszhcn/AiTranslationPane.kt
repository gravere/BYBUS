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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.AiConfig
import com.example.limbuszhcn.translation.AiKeyChoice
import com.example.limbuszhcn.translation.AiScope
import com.example.limbuszhcn.translation.AiTranslationPrompt
import com.example.limbuszhcn.translation.normalizedAiEndpoint
import com.example.limbuszhcn.translation.AiEntryRequest
import com.example.limbuszhcn.translation.CoverageRequest
import com.example.limbuszhcn.translation.CoverageFilter
import com.example.limbuszhcn.translation.CoverageKind
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun AiTranslationPane(state: LauncherUiState, actions: TranslationActions, onCoverage: () -> Unit = {}, requestedScope: AiScope? = null,
    requestedEntries: AiEntryRequest? = null, onEntries: (String?) -> Unit = {}, requestedCoverage: CoverageRequest? = null) {
    val ai = state.ai
    var endpoint by remember(ai.config.endpoint, ai.activeKeyId) { mutableStateOf(ai.config.endpoint) }
    var model by remember(ai.config.model, ai.activeKeyId) { mutableStateOf(ai.config.model) }
    var missing by remember(ai.config.missingOnly, requestedScope, requestedEntries, requestedCoverage) { mutableStateOf(
        requestedEntries?.let { request -> state.sources.sources.find { it.id == request.report.sourceId }?.independent == false }
            ?: (requestedCoverage != null || requestedScope != null || ai.config.missingOnly)) }
    var scope by remember(ai.config.includeMissing, ai.config.includeReview, requestedScope, requestedCoverage) {
        mutableStateOf(requestedScope ?: requestedCoverage?.let { when (it.filter.kind) {
            CoverageKind.Missing -> AiScope.Missing; CoverageKind.Review -> AiScope.Review; null -> AiScope.Both
        } } ?: if (ai.config.includeReview) if (ai.config.includeMissing) AiScope.Both else AiScope.Review else AiScope.Missing)
    }
    var limit by remember(ai.config.limit) { mutableIntStateOf(ai.config.limit) }
    // Never saved in a Bundle or ViewModel; the persisted value is encrypted separately.
    var key by remember(endpoint, ai.activeKeyId, ai.savedKeyRevision) { mutableStateOf("") }
    var reveal by remember(endpoint, ai.activeKeyId, ai.savedKeyRevision) { mutableStateOf(false) }
    var newKeyName by remember(endpoint, ai.savedKeyRevision) { mutableStateOf("") }
    var namingKey by remember { mutableStateOf(false) }
    var selectingKey by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var cancelTask by remember { mutableStateOf(false) }
    LaunchedEffect(state.aiCancelPrompt) { if (state.aiCancelPrompt != null) cancelTask = false }
    var showConfiguration by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf<AiKeyChoice?>(null) }
    var undo by remember { mutableStateOf<com.example.limbuszhcn.translation.AiUndoPoint?>(null) }
    var prompt by remember(ai.config.prompt) { mutableStateOf(ai.config.prompt) }
    var stream by remember(ai.config.stream) { mutableStateOf(ai.config.stream) }
    var editPrompt by remember { mutableStateOf(false) }
    var resetPrompt by remember { mutableStateOf(false) }
    var restoreDefault by remember { mutableStateOf(false) }
    var deleteAi by remember { mutableStateOf<com.example.limbuszhcn.translation.TranslationSource?>(null) }
    var showModels by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun finishEditing() { focus.clearFocus(); keyboard?.hide() }
    fun config() = AiConfig(endpoint.trim(), model.trim(), missing, limit, scope.missing, scope.review, prompt, stream)
    var category by remember(state.coverage.report?.entryIndex) { mutableStateOf<String?>(null) }
    val coverageFilter = CoverageFilter(when (scope) { AiScope.Missing -> CoverageKind.Missing; AiScope.Review -> CoverageKind.Review; else -> null }, category)
    val coverageRequest = if (requestedEntries == null && missing) requestedCoverage ?: coverageSelection(state.coverage, coverageFilter, null, actions.coverageCount) else null
    val enabled = !state.busy && !ai.settingsBusy && !ai.running
    val pending = ai.hasPendingTask || ai.running
    val savedKeyAvailable = ai.hasKey && runCatching {
        normalizedAiEndpoint(endpoint) == normalizedAiEndpoint(ai.config.endpoint)
    }.getOrDefault(false)
    val hasCredential = key.isNotBlank() || savedKeyAvailable
    val coverageReady = if (requestedEntries == null) state.coverage.current && coverageRequest != null && coverageRequest.count > 0 &&
        coverageRequest.fingerprint == state.coverage.fingerprint && coverageRequest.report.entryIndex == state.coverage.report?.entryIndex else
        !ai.library.stale && ai.library.report?.key == requestedEntries.report.key &&
            (requestedEntries.report.sourceId == null || state.sources.sources.any { it.id == requestedEntries.report.sourceId && it.revision == requestedEntries.report.revision })
    LaunchedEffect(endpoint, key, ai.activeKeyId) { actions.clearModels(); showModels = false; search = "" }
    LaunchedEffect(ai.models.request, ai.models.loading) {
        if (!ai.models.loading && ai.models.values.isNotEmpty()) showModels = true
    }
    DisposableEffect(Unit) { onDispose { actions.clearModels() } }
    Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (pending) {
            AiTaskCard(state, actions.pauseAi, actions.resumeAi) { cancelTask = true }
            TextButton({ showConfiguration = !showConfiguration }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showConfiguration) "收起翻译设置" else "查看翻译设置")
            }
            if (!showConfiguration) AiSettingsFeedback(ai, config(), key)
        }
        if (!pending || showConfiguration) {
        if (requestedEntries != null) DetailCard {
            Text("${requestedEntries.report.name} · ${requestedEntries.report.language.displayName}", style = MaterialTheme.typography.titleSmall)
            Text("已选 ${requestedEntries.count} 条，本次最多 ${minOf(limit, requestedEntries.count)} 条", style = MaterialTheme.typography.bodyMedium)
            Text("已有译文的所选条目会重新翻译，其他条目保留。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            if (!coverageReady) TextButton({ onEntries(requestedEntries.report.sourceId) }, enabled = enabled) { Text("重新读取并选择条目") }
        } else if (requestedCoverage != null) DetailCard {
            Text("${requestedCoverage.report.sourceName} · ${requestedCoverage.report.language.displayName}原文", style = MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(requestedCoverage.filter.category ?: "全部类别", requestedCoverage.filter.kind?.label,
                "已选 ${requestedCoverage.count} 条").joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            if (!coverageReady) TextButton(onCoverage) { Text("返回覆盖明细重新选择") }
        } else if (missing) CoverageSummary(state, onCoverage, actions.inspectCoverage)
        DetailCard {
            Text("连接设置", style = MaterialTheme.typography.titleSmall)
            AiField("服务地址", endpoint, { if (it.length <= 2048) endpoint = it }, "https://api.openai.com/v1",
                enabled, type = KeyboardType.Uri)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("API Key", color = C.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    if (ai.keys.isNotEmpty()) TextButton({ finishEditing(); selectingKey = true }, enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("切换密钥（${ai.keys.size}）", style = MaterialTheme.typography.labelMedium)
                    }
                }
                AiInput(key, { if (it.length <= 1024) key = it }, "API Key",
                    if (savedKeyAvailable) ai.keys.find { it.id == ai.activeKeyId }?.let { "当前使用 ${it.name}" }
                        ?: "已保存密钥" else "输入服务商提供的密钥",
                    enabled, type = KeyboardType.Password,
                    transformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = if (key.isNotEmpty()) ({ TextButton({ reveal = !reveal }) { Text(if (reveal) "隐藏" else "显示") } }) else null)
                if (key.isNotBlank()) {
                    FilledTonalButton({ finishEditing(); reveal = false; namingKey = true },
                        enabled = enabled, modifier = Modifier.align(Alignment.End)) { Text("保存密钥") }
                }
            }
            HorizontalDivider(color = C.Line)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("模型", color = C.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton({ finishEditing(); actions.loadModels(endpoint.trim(), key) },
                        enabled = enabled && !ai.models.loading && endpoint.isNotBlank(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        if (ai.models.loading) LauncherSpinner(Modifier.size(16.dp), strokeWidth = 1.6.dp)
                        else LauncherGlyph(Glyph.Refresh, LocalContentColor.current, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (ai.models.loading) "正在获取" else "获取模型", style = MaterialTheme.typography.labelMedium)
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("流式接收", style = MaterialTheme.typography.bodyMedium)
                    Text("持续接收回复；服务不支持时可关闭", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(stream, { stream = it }, enabled = enabled)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton({ finishEditing(); reveal = false; actions.saveAi(config(), key, false) },
                    enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("保存设置") }
                TextButton({ finishEditing(); reveal = false; actions.saveAi(config(), key, true) },
                    enabled = enabled && hasCredential, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (ai.settingsBusy) "处理中" else "测试翻译") }
            }
            AiSettingsFeedback(ai, config(), key)
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
                    Text("保存后用于下一次翻译，已有译文保持不变。", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({ finishEditing(); actions.savePrompt(prompt) }, enabled = enabled && prompt.isNotBlank(), modifier = Modifier.weight(1f)) { Text("保存提示词") }
                        TextButton({ finishEditing(); resetPrompt = true }, enabled = enabled && prompt != AiTranslationPrompt.DEFAULT, modifier = Modifier.weight(1f)) { Text("恢复默认") }
                    }
                }
            }
        }
        AiPricingCard(endpoint.trim(), model.trim(), ai, actions, enabled)
        if (!pending && ai.progress.requests > 0) DetailCard { Text("上次翻译用量", style = MaterialTheme.typography.titleSmall); AiUsageSummary(ai.progress) }
        if (!pending) DetailCard {
            if (requestedEntries == null && requestedCoverage == null) {
            Text("翻译方式", style = MaterialTheme.typography.titleSmall)
            SourceTabs(listOf(true, false), missing, { if (it) "补充当前汉化" else "从零全局翻译" }, "翻译方式", { if (enabled) missing = it })
            if (!missing) Text("无需现成汉化包，可选择全部原文或指定类别。", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
            if (missing && requestedEntries == null && requestedCoverage == null) {
                Text("处理范围", style = MaterialTheme.typography.titleSmall)
                SourceTabs(AiScope.entries.toList(), scope, { it.label }, "处理范围", { if (enabled) scope = it })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("内容分类", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    CoverageCategoryPicker(category, state.coverage.report?.details?.map { it.category }?.distinct()?.sorted().orEmpty()) { if (enabled) category = it }
                }
                coverageRequest?.let { Text("当前范围 ${it.count} 条", style = MaterialTheme.typography.bodySmall, color = C.Muted) }
                if (scope.review) Text("待核对：译文仍含日语或韩语。先查看明细，避免改掉刻意保留的用词。", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
            Text("本次翻译数量", style = MaterialTheme.typography.titleSmall)
            SourceTabs(listOf(100, 1000, 10000, com.example.limbuszhcn.translation.MAX_AI_TASK_ENTRIES), limit,
                { if (it == com.example.limbuszhcn.translation.MAX_AI_TASK_ENTRIES) "全部" else "$it 条" }, "翻译上限", { if (enabled) limit = it })
            Text("“全部”会连续处理所选范围，可随时暂停。按 API 用量计费。",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        }
        if (!pending) {
            if (!missing && requestedEntries == null) {
                Button({ finishEditing(); onEntries(null) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("读取原文并选择范围") }
            } else if (requestedEntries == null && (!state.coverage.current || state.coverage.report?.entryIndex == null)) {
                FilledTonalButton({ finishEditing(); actions.inspectCoverage() }, enabled = enabled && !state.coverage.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (state.coverage.loading) "正在检查汉化覆盖" else "先检查汉化覆盖")
                }
            } else Button({ finishEditing(); confirm = true }, enabled = enabled && hasCredential && coverageReady,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(when {
                    coverageReady -> "生成并使用"
                    !missing && !state.coverage.independentReady -> "暂时无法读取独立翻译进度"
                    else -> "所选范围已无需补译"
                })
            }
        }
        state.sources.undo?.let { point -> AiUndoCard(point, state.sources, enabled) { finishEditing(); undo = point } }
        val generated = state.sources.sources.filter { it.kind == "ai" }
        if (generated.isNotEmpty() && requestedEntries == null) DetailCard {
            Text("已生成的汉化", style = MaterialTheme.typography.titleSmall)
            SheetAction(Glyph.Translation, "恢复默认汉化", enabled && !state.sources.selected.isOfficial,
                onClick = { finishEditing(); restoreDefault = true })
            com.example.limbuszhcn.translation.sourceFamilies(state.sources.sources).filter { it.versions.isNotEmpty() }.forEach { family ->
                val versions = family.versions
                HorizontalDivider(color = C.Line)
                SourceVersionDisclosure(family.id, "${family.name} · ${versions.size} 个版本", family.base) {
                versions.sortedByDescending { it.updatedAtMillis }.forEach { source ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TranslationSourceIcon(source, Modifier.size(32.dp))
                    Column(Modifier.weight(1f).clickable(enabled = !state.busy) { onEntries(source.id) }.padding(vertical = 6.dp)) {
                        Text(source.name, style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull(source.language?.let { com.example.limbuszhcn.gamefs.GameTextLanguage.fromCode(it)?.displayName },
                            source.aiEntryCount?.let { "AI 已译 $it 条" } ?: "查看已译条目").joinToString(" · "), color = C.Muted, style = MaterialTheme.typography.bodySmall)
                        state.packageTimeLabel(source)?.let { Text(it, color = C.Muted, style = MaterialTheme.typography.bodySmall) }
                        if (source.id == state.sources.selectedId) Text("当前使用", color = C.Accent,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    ActionIconButton(Glyph.Delete, "删除 ${source.name}",
                        { finishEditing(); deleteAi = source }, enabled = enabled)
                }
                }
                }
            }
        }
    }
    if (cancelTask) AiTaskCancelDialog({ cancelTask = false }, { cancelTask = false; actions.cancelAi() },
        ai.task?.phase != com.example.limbuszhcn.translation.AiTaskPhase.Applying)
    if (confirm) BybusDialog(onDismissRequest = { confirm = false }, title = { Text("开始 AI 翻译？") },
        text = { val count = requestedEntries?.count ?: coverageRequest?.count ?: 0
            Text("${if (requestedEntries != null) "所选文本" else if (missing) scope.label else "尚未完成的可译文本"}共 $count 条，本次最多 ${minOf(limit, count)} 条。将关闭游戏，使用你的 API 翻译并计费。${if (requestedEntries != null) "仅翻译所选条目，其余译文保留。" else "已完成的译文保留。"}") },
        confirmButton = { TextButton({ confirm = false; reveal = false
            if (requestedEntries != null) actions.generateEntries(config(), key, requestedEntries)
            else coverageRequest?.let { actions.generateCoverage(config(), key, it) }
        }, enabled = enabled && coverageReady) { Text("开始翻译") } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
    if (namingKey) BybusDialog(onDismissRequest = { namingKey = false }, title = { Text("保存密钥") },
        text = { AiField("密钥名称", newKeyName, { if (it.length <= 48) newKeyName = it },
            "可选，例如「个人账号」", enabled, KeyboardType.Text) },
        confirmButton = { TextButton({ namingKey = false; actions.saveKey(endpoint, model, key, newKeyName) },
            enabled = enabled && key.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton({ namingKey = false }) { Text("取消") } })
    if (selectingKey) AiKeyPicker(ai.keys, ai.activeKeyId, enabled, actions,
        onRemove = { selectingKey = false; clear = it }, onDismiss = { selectingKey = false }, error = ai.settingsMessage.takeIf { ai.settingsFailed })
    clear?.let { item -> BybusDialog(destructive = true, onDismissRequest = { clear = null }, title = { Text("移除「${item.name}」？") },
        text = { Text("此密钥将从本机删除。") },
        confirmButton = { TextButton({ clear = null; actions.clearModels(); actions.clearKey(item.id) }, enabled = enabled) { Text("移除") } },
        dismissButton = { TextButton({ clear = null }) { Text("取消") } }) }
    undo?.let { point -> BybusDialog(destructive = true, onDismissRequest = { undo = null }, title = { Text("撤回这次改动？") },
        text = { AiUndoConfirmation(point, state.sources) },
        confirmButton = { TextButton({ undo = null; actions.undoAi(point) }, enabled = enabled && state.sources.undo == point) { Text("撤回") } },
        dismissButton = { TextButton({ undo = null }) { Text("取消") } }) }
    if (resetPrompt) BybusDialog(destructive = true, onDismissRequest = { resetPrompt = false }, title = { Text("恢复默认提示词？") },
        text = { Text("将替换当前输入的自定义提示词，保存后生效。已有汉化包不会改变。") },
        confirmButton = { TextButton({ resetPrompt = false; prompt = AiTranslationPrompt.DEFAULT }, enabled = enabled) { Text("恢复") } },
        dismissButton = { TextButton({ resetPrompt = false }) { Text("取消") } })
    if (restoreDefault) BybusDialog(onDismissRequest = { restoreDefault = false }, title = { Text("恢复默认汉化？") },
        text = { Text("将关闭游戏并切回都市零协会，下次启动检查正式汉化更新。已生成的 AI 汉化会保留，可随时重新选择；游戏数据与账号不变。") },
        confirmButton = { TextButton({ restoreDefault = false; actions.select("zero") }, enabled = enabled) { Text("恢复") } },
        dismissButton = { TextButton({ restoreDefault = false }) { Text("取消") } })
    deleteAi?.let { source -> BybusDialog(destructive = true, onDismissRequest = { deleteAi = null }, title = { Text("删除 AI 汉化？") },
        text = { Text("删除“${source.name}”及其生成缓存，不能撤回。${if (source.id == state.sources.selectedId) "将关闭游戏并切回都市零协会。" else "当前使用的汉化不变。"}游戏数据、账号、其他汉化源与 API 设置会保留。") },
        confirmButton = { TextButton({ deleteAi = null; actions.removeAi(source.id) }, enabled = enabled) { Text("删除") } },
        dismissButton = { TextButton({ deleteAi = null }) { Text("取消") } }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiTaskCard(state: LauncherUiState, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    val ai = state.ai
    val task = ai.task
    val progress = ai.progress
    val saving = task?.phase == com.example.limbuszhcn.translation.AiTaskPhase.Applying
    val elapsed = aiElapsedMillis(progress, ai.running)
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(task?.id) { mutableStateOf(false) }
    val angle by androidx.compose.animation.core.animateFloatAsState(if (expanded) 270f else 90f, tween(160), label = "task details")
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    var backgroundAllowed by remember { mutableStateOf(com.example.limbuszhcn.translation.AiBackgroundAccess.allowed(context)) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME)
                backgroundAllowed = com.example.limbuszhcn.translation.AiBackgroundAccess.allowed(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DetailCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LauncherGlyph(Glyph.Ai, C.Accent, Modifier.size(20.dp))
            Text(if (saving) "保存译文" else if (ai.stopping) "正在暂停" else if (ai.running) "正在翻译" else "已暂停",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(com.example.limbuszhcn.translation.formatTaskDuration(elapsed), color = C.Muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { contentDescription = "翻译用时" })
        }
        if (task?.plan != null) {
            val total = task.plan.total
            val done = (progress.completed + progress.reused).coerceAtMost(total)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.align(Alignment.Bottom).padding(end = 12.dp), verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("$done", style = MaterialTheme.typography.headlineSmall)
                        Text("/ $total 条", color = C.Muted, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 2.dp))
                    }
                    Text(if (!ai.running) "进度已保存" else com.example.limbuszhcn.translation.compactAiTaskLabel(ai.message.orEmpty()),
                        color = C.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Bottom).padding(bottom = 2.dp))
                }
                LinearProgressIndicator(progress = { if (total == 0) 1f else done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "翻译任务进度" })
            }
        } else {
            if (ai.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (progress.totalFiles > 0) "准备文本 ${progress.scannedFiles}/${progress.totalFiles}" else "正在准备文本",
                style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
        if (ai.failed) ai.message?.let { Text(it, color = C.Error, style = MaterialTheme.typography.bodySmall) }
        if (!backgroundAllowed) TextButton({ com.example.limbuszhcn.translation.AiBackgroundAccess.open(context) }) {
            Text("允许锁屏后继续翻译")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ai.running) FilledTonalButton(onPause, enabled = !ai.stopping && !saving, modifier = Modifier.weight(1f)) { Text("暂停") }
            else Button(onResume, enabled = !state.busy && !ai.settingsBusy && ai.hasKey && task != null && !ai.recoveryIssue, modifier = Modifier.weight(1f)) { Text("继续") }
            TextButton(onCancel, enabled = !saving && (ai.running || !state.busy), modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(contentColor = C.Error)) { Text("取消任务") }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }.heightIn(min = 36.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("任务详情", color = C.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(14.dp).rotate(angle))
        }
        ExpandableDetails(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                task?.let {
                    AiTaskMetaRow("汉化源", it.sourceName)
                    AiTaskMetaRow("翻译范围", it.scopeLabel)
                    AiTaskMetaRow("任务模型", it.config.model)
                    if (it.plan != null && it.plan.available > it.plan.total) AiTaskMetaRow("本次选择", "${it.plan.total} / ${it.plan.available} 条")
                }
                AiUsageSummary(progress, showElapsed = false)
                if (!ai.failed) ai.message?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = C.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun AiTaskCancelDialog(onDismiss: () -> Unit, onConfirm: () -> Unit, enabled: Boolean = true) {
    BybusDialog(destructive = true, onDismissRequest = onDismiss, title = { Text("取消翻译任务？") },
        text = { Text("删除这次任务及未发布译文，已生成的汉化包不受影响。已产生的 API 费用不会返还。") },
        confirmButton = { TextButton(onConfirm, enabled = enabled) { Text("删除任务") } },
        dismissButton = { TextButton(onDismiss) { Text("保留任务") } })
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
