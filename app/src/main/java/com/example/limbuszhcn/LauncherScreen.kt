package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.limbuszhcn.tracking.ProjectTrackingState
import com.example.limbuszhcn.tracking.ProjectSource
import com.example.limbuszhcn.update.GameUpdateSource
import kotlinx.coroutines.delay
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LauncherScreen(
    state: LauncherUiState,
    projectState: ProjectTrackingState,
    appUpdateState: com.example.limbuszhcn.selfupdate.AppUpdateState,
    visible: Boolean,
    onCheckAppUpdate: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    onCancelAppUpdate: () -> Unit,
    onOpenAppRelease: () -> Unit,
    onLaunch: () -> Unit,
    onRefresh: () -> Unit,
    onUpdate: () -> Unit,
    onCheckGameUpdate: () -> Unit,
    onSelectGameSource: (GameUpdateSource) -> Unit,
    onOpenGamePage: (String) -> Unit,
    onOpenInstallSource: () -> Unit,
    onImportGame: () -> Unit,
    onInstallGame: () -> Unit,
    onCancelGameInstall: () -> Unit,
    onRepair: () -> Unit = {},
    onClearGameResources: () -> Unit = {},
    onMigration: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onGoogleSettings: () -> Unit,
    onCheckGooglePayment: () -> Unit = {},
    onEnableGooglePayment: () -> Unit = {},
    onRefreshProject: () -> Unit,
    onSelectProjectSource: (ProjectSource) -> Unit,
    onOpenLink: (String) -> Unit,
    translationActions: TranslationActions = TranslationActions(),
    homeProjectSourceId: String? = null,
    onHomeProjectSource: (String?) -> Unit = {},
    onDismissAiCancellation: () -> Unit = {},
    onConfirmAiCancellation: () -> Unit = {},
    onAutomaticAppUpdates: (Boolean) -> Unit = {}
) {
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var parentSheets by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmation by rememberSaveable { mutableStateOf<ConfirmedOperation?>(null) }
    var confirmZeroSource by rememberSaveable { mutableStateOf(false) }
    var requestedAiScope by remember { mutableStateOf<com.example.limbuszhcn.translation.AiScope?>(null) }
    var requestedAiEntries by remember { mutableStateOf<com.example.limbuszhcn.translation.AiEntryRequest?>(null) }
    var requestedCoverage by remember { mutableStateOf<com.example.limbuszhcn.translation.CoverageRequest?>(null) }
    var handledUpdatesRequest by rememberSaveable { mutableLongStateOf(0L) }
    var handledAiTaskRequest by rememberSaveable { mutableLongStateOf(0L) }
    var handledPreparationRequest by rememberSaveable { mutableLongStateOf(0L) }
    var hideAiLaunchProgress by remember { mutableStateOf(false) }
    LaunchedEffect(state.aiPreparingLaunch) { if (!state.aiPreparingLaunch) hideAiLaunchProgress = false }
    LaunchedEffect(state.resourcePreparationRequest) {
        if (state.resourcePreparationRequest > 0 && state.resourcePreparationRequest != handledPreparationRequest) {
            handledPreparationRequest = state.resourcePreparationRequest
            parentSheets = emptyList()
            sheet = "resource-preparation"
        }
    }
    LaunchedEffect(state.updatesRequest) {
        if (state.updatesRequest > 0 && state.updatesRequest != handledUpdatesRequest) {
            handledUpdatesRequest = state.updatesRequest
            parentSheets = emptyList()
            sheet = "updates"
        }
    }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state.aiTaskRequest) {
        if (state.aiTaskRequest > 0 && state.aiTaskRequest != handledAiTaskRequest) {
            handledAiTaskRequest = state.aiTaskRequest
            requestedAiScope = null; requestedAiEntries = null; requestedCoverage = null
            parentSheets = emptyList()
            sheet = "ai-translation"
        }
    }
    fun open(page: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        parentSheets = emptyList()
        sheet = page
    }
    fun openChild(page: String) { sheet?.let { parentSheets = parentSheets + it }; sheet = page }
    fun close() { parentSheets = emptyList(); sheet = null }
    fun openTask() {
        val destination = state.taskDestination()
        if (destination == "migration") onMigration() else {
            requestedAiScope = null
            requestedAiEntries = null
            requestedCoverage = null
            open(destination)
        }
    }
    fun openCoverage() {
        if (state.coverage.report != null || !state.canInspectCoverage) openChild("coverage")
        else if (!state.coverage.loading) translationActions.inspectCoverage()
    }
    fun openAiEntries(id: String?) {
        requestedAiScope = null; requestedAiEntries = null; requestedCoverage = null
        if (sheet != "ai-entries") openChild("ai-entries")
        translationActions.inspectAiEntries(id, false)
    }

    Surface(Modifier.fillMaxSize(), color = C.Background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Use the space allocated to this window, including split screen.
            // Large text needs a wider column, not a smaller font.
            val homeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
            val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
            val layout = LauncherLayout.calculate((maxWidth - homeInsets.calculateLeftPadding(direction) - homeInsets.calculateRightPadding(direction)).value,
                (maxHeight - homeInsets.calculateTopPadding() - homeInsets.calculateBottomPadding()).value, LocalDensity.current.fontScale)
            val homeScroll = rememberScrollState()
            Column(Modifier.widthIn(max = layout.contentMaxWidth.dp).fillMaxSize().align(Alignment.TopCenter)
                .padding(homeInsets).padding(horizontal = layout.margin.dp)
                .then(if (sheet != null) Modifier.clearAndSetSemantics { } else Modifier)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    LauncherGlyph(Glyph.Mark, C.Accent, Modifier.size(30.dp))
                    Text(androidx.compose.ui.res.stringResource(R.string.app_name), fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 2.6.sp, color = C.Ink,
                        modifier = Modifier.padding(start = 11.dp).weight(1f))
                    Box {
                        ActionIconButton(Glyph.Settings, "设置与工具", { open("tools") }, modifier = Modifier.semantics {
                            if (appUpdateState.updateAvailable) stateDescription = "有 BYBUS 新版本"
                        })
                        if (appUpdateState.updateAvailable) UpdateDot(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp))
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val bodyHeight = maxHeight
                    if (layout.twoColumns) {
                        Column(Modifier.fillMaxWidth().verticalScroll(homeScroll).heightIn(min = bodyHeight)
                            .padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(layout.columnGap.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                InstallationCard(state, Modifier.weight(1f).fillMaxHeight()) { open("status") }
                                LaunchActions(state, onLaunch, onRefresh, visible,
                                    onDetails = { if (state.busy || state.ai.hasPendingTask) openTask() else open(if (state.failed) "error" else "status") },
                                    modifier = Modifier.width(layout.actionWidth.dp))
                                HomeProjectCard(state, projectState, homeProjectSourceId, onHomeProjectSource,
                                    onProject = { open("project") }, onSources = { open("translation") },
                                    onAi = { id -> open("translation"); openAiEntries(id) },
                                    modifier = Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    } else {
                        val groupHeight = minOf(bodyHeight, 600.dp)
                        Column(Modifier.fillMaxWidth().align(Alignment.Center)
                            .verticalScroll(homeScroll).heightIn(min = groupHeight).padding(vertical = 16.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            InstallationCard(state) { open("status") }
                            LaunchActions(state, onLaunch, onRefresh, visible,
                                onDetails = { if (state.busy || state.ai.hasPendingTask) openTask() else open(if (state.failed) "error" else "status") },
                                modifier = Modifier.widthIn(max = layout.actionWidth.dp).fillMaxWidth())
                            HomeProjectCard(state, projectState, homeProjectSourceId, onHomeProjectSource,
                                onProject = { open("project") }, onSources = { open("translation") },
                                onAi = { id -> open("translation"); openAiEntries(id) })
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                    AboutLink { open("about") }
                }
            }
            sheet?.let { page ->
                LauncherDetailSurface(layout, page, onDismiss = ::close,
                    compactContent = (page == "project" && projectState.loading && (if (projectState.source == com.example.limbuszhcn.tracking.ProjectSource.GitHub) projectState.github.snapshot == null else projectState.paratranz.snapshot == null)) ||
                        (page == "ai-entries" && state.ai.library.loading && state.ai.library.report == null) ||
                        (page == "coverage" && state.coverage.loading && state.coverage.report == null) || page == "ai-base-update",
                    onBack = parentSheets.lastOrNull()?.let { parent -> { sheet = parent; parentSheets = parentSheets.dropLast(1) } },
                    footer = { active -> when (active) {
                        "app-update" -> AppUpdateControls(appUpdateState, state, onDownloadAppUpdate, onInstallAppUpdate, onCancelAppUpdate, layout.detailPadding.dp)
                        "translation" -> TranslationUpdateControls(state, {
                            val selected = state.sources.selected
                            if (selected.kind == "ai" && (selected.independent || selected.baseId != null && selected.baseId != "zero"))
                                openAiEntries(selected.id)
                            else if (selected.kind == "ai") onUpdate() else confirmation = ConfirmedOperation.Translation
                        }, layout.detailPadding.dp)
                        "project" -> if (!state.sources.selected.isOfficial) UpdateActionCard(null, Glyph.Translation,
                            busy = false, description = "选择都市零协会汉化", progressDescription = "",
                            horizontalPadding = layout.detailPadding.dp, action = "使用此汉化源",
                            actionEnabled = !state.busy, onAction = { confirmZeroSource = true })
                    } }) { active ->
                    if (active == "coverage") {
                        TranslationCoveragePane(state, translationActions.inspectCoverage, translationActions.coverageEntries,
                            onTranslate = { scope -> requestedAiScope = scope; requestedAiEntries = null; requestedCoverage = null; openChild("ai-translation") },
                            readCount = translationActions.coverageCount, onSelected = { request ->
                                requestedAiScope = null; requestedAiEntries = null; requestedCoverage = request; openChild("ai-translation")
                            })
                    } else if (active == "repair") {
                        RepairPane(state, { confirmation = ConfirmedOperation.Repair }, onExport)
                    } else if (active == "clear-data") {
                        ClearDataPane(state) { confirmation = ConfirmedOperation.ClearResources }
                    } else if (active == "google") {
                        GooglePaymentPane(state, onCheckGooglePayment, onEnableGooglePayment,
                            onSettings = { onGoogleSettings() })
                    } else if (active == "resource-preparation") {
                        ResourcePreparationPane(state, onCheck = onLaunch, onImport = onMigration)
                    } else if (active == "project") {
                        ProjectDetails(projectState, onRefreshProject, onSelectProjectSource, onOpenLink)
                    } else if (active == "updates") {
                        GameUpdatesPane(state, onCheckGameUpdate, onSelectGameSource, onOpenGamePage,
                            onOpenInstallSource, onImportGame, onInstallGame, onCancelGameInstall)
                    } else if (active == "app-update") {
                        AppUpdatesPane(appUpdateState, onCheckAppUpdate, onOpenAppRelease, onAutomaticAppUpdates)
                    } else if (active == "translation") {
                        TranslationSourcesPane(state, translationActions, onProject = { openChild("project") }, onCoverage = ::openCoverage,
                            onBaseUpdate = { id -> translationActions.inspectBaseUpdate(id); openChild("ai-base-update") }) {
                            requestedAiScope = null; requestedAiEntries = null; requestedCoverage = null; openChild("ai-translation")
                        }
                    } else if (active == "ai-translation") {
                        AiTranslationPane(state, translationActions, onCoverage = ::openCoverage, requestedScope = requestedAiScope,
                            requestedEntries = requestedAiEntries, onEntries = ::openAiEntries, requestedCoverage = requestedCoverage)
                    } else if (active == "ai-base-update") {
                        AiBaseUpdatePane(state, translationActions)
                    } else if (active == "ai-entries") {
                        AiEntriesPane(state, translationActions, onBaseUpdate = { id -> translationActions.inspectBaseUpdate(id); openChild("ai-base-update") }) { request ->
                            requestedAiScope = null; requestedAiEntries = request; requestedCoverage = null; openChild("ai-translation")
                        }
                    } else {
                        LauncherDetails(active, state, onGameStore = { openChild("updates") },
                            appUpdateAvailable = appUpdateState.updateAvailable,
                            onAppUpdate = { openChild("app-update"); onCheckAppUpdate() },
                            onTranslation = { openChild("translation") },
                            onAi = { requestedAiScope = null; requestedAiEntries = null; openChild("ai-translation") },
                            onCoverage = ::openCoverage, onProject = { openChild("project") },
                            onMigration = onMigration, onRepair = { openChild("repair") },
                            onClearData = { openChild("clear-data") },
                            onExport = { close(); onExport() }, onSettings = { close(); onSettings() },
                            onGoogleSettings = { openChild("google"); onCheckGooglePayment() },
                            onRefresh = { if (active != "status") close(); onRefresh() }, onAbout = { openChild("about") })
                    }
                }
            }
        }
    }
    state.importReview?.takeIf { !state.busy }?.let { review ->
        ImportCoverageDialog(review) { choice ->
            translationActions.chooseImportFill(choice)
            if (choice == com.example.limbuszhcn.translation.ImportFillChoice.Ai) {
                requestedAiScope = com.example.limbuszhcn.translation.AiScope.Missing
                requestedAiEntries = null; requestedCoverage = null; open("ai-translation")
            }
        }
    }
    state.aiUpstreamOffer?.let { offer ->
        AiUpstreamDialog(offer, state.ai.hasKey, !state.busy && !state.ai.settingsBusy,
            translationActions.chooseAiUpdate, { translationActions.chooseAiUpdate(null); requestedAiScope = null; open("ai-translation") })
    }
    state.aiCancelPrompt?.let { request ->
        LaunchedEffect(request, state.ai.running, state.ai.task?.id) {
            if (!request.matches(com.example.limbuszhcn.translation.AiTaskService.activeSessionId(), state.ai.task?.id)) onDismissAiCancellation()
        }
        AiTaskCancelDialog(onDismissAiCancellation, onConfirmAiCancellation,
            state.ai.task?.phase != com.example.limbuszhcn.translation.AiTaskPhase.Applying)
    }
    if (state.aiPreparingLaunch && !hideAiLaunchProgress) AiLaunchProgressDialog(state, translationActions.pauseAi) {
        hideAiLaunchProgress = true
        openTask()
    }
    if (confirmZeroSource) TranslationSourceSwitchDialog(com.example.limbuszhcn.translation.TranslationSources.OFFICIAL,
        !state.busy, { confirmZeroSource = false }) {
        confirmZeroSource = false
        translationActions.select("zero")
    }
    confirmation?.let { operation ->
        OperationConfirmation(operation, enabled = !state.busy, onDismiss = { confirmation = null }) {
            confirmation = null
            if (!state.busy) when (operation) {
                ConfirmedOperation.Repair -> onRepair()
                ConfirmedOperation.ClearResources -> onClearGameResources()
                ConfirmedOperation.Translation -> onUpdate()
                else -> Unit
            }
        }
    }
}

@Composable
private fun LaunchActions(state: LauncherUiState, onLaunch: () -> Unit,
    onRefresh: () -> Unit, animated: Boolean, onDetails: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        PrimaryAction(state, onLaunch, onRefresh, onDetails, animated)
    }
}

@Composable
private fun InstallationCard(state: LauncherUiState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ready = state.container?.available == true && state.installedVersion != null &&
        state.compatibility?.isSupported == true && state.nativeCompatibilityIssue == null
    HomeSummaryCard(onClick, modifier) {
        SummaryHeading(Glyph.Progress, "运行状态", when {
                state.busy -> "正在准备"
                state.failed -> "需要检查"
                state.resourcePreparation != null -> "待准备资源"
                ready -> "设备已就绪"
                state.container == null -> "检测中"
                state.installedVersion == null -> "等待安装"
                else -> "需要检查"
            }, ready)
        Spacer(Modifier.height(16.dp))
        InstallationRow(Glyph.Bus, "游戏",
            when { state.container == null -> "正在检测"; state.installedVersion == null -> "等待安装";
                else -> state.installedVersionName ?: state.installedVersion.toString() },
            if (state.gameUpdate.newerSources(state.installedVersionName).isNotEmpty()) "有新版" else null)
        HorizontalDivider(Modifier.padding(start = 32.dp, top = 12.dp, bottom = 12.dp), color = C.Line)
        InstallationRow(Glyph.Translation, "本地汉化", state.translationLabel
            ?: if (state.resourcePreparation != null) "汉化尚未就绪" else "启动时准备汉化")
    }
}

@Composable
private fun InstallationRow(icon: Glyph, title: String, value: String, status: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LauncherGlyph(icon, C.Muted, Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = C.Muted, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        status?.let { Text(it, color = C.Accent, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.widthIn(max = 72.dp)) }
    }
}

@Composable
internal fun PrimaryAction(state: LauncherUiState, onLaunch: () -> Unit, onRefresh: () -> Unit, onDetails: () -> Unit, animated: Boolean) {
    val seconds = operationSeconds(state)
    val aiOperation = state.ai.running || state.taskPage == "ai-translation"
    val aiElapsed = aiElapsedMillis(state.ai.progress, state.ai.running)
    val feedback = rememberOperationFeedback(state)
    val missing = state.container != null && state.installedVersion == null
    val canLaunch = state.container?.available == true && state.installedVersion != null &&
        state.compatibility?.isSupported == true && state.nativeCompatibilityIssue == null
    val needsDetection = missing || state.container == null
    val action = when {
        state.busy -> when (state.launchStep) {
            0 -> LaunchAction.Prepare; 1 -> LaunchAction.Translate; 2 -> LaunchAction.Starting; else -> LaunchAction.Working
        }
        state.canResumeGame -> LaunchAction.Resume
        state.container == null && !state.failed -> LaunchAction.Detecting
        needsDetection -> LaunchAction.Detect
        state.compatibility?.isSupported == false -> LaunchAction.DeviceStatus
        state.nativeCompatibilityIssue != null -> LaunchAction.NativeStatus
        !canLaunch -> LaunchAction.Status
        state.resourcePreparation != null -> LaunchAction.Resources
        state.failed -> LaunchAction.Retry
        else -> LaunchAction.Launch
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LaunchButton(action, enabled = state.busy || state.container != null || state.failed,
            step = state.launchStep, animated = animated, feedback = feedback,
            status = if (state.busy && aiOperation) com.example.limbuszhcn.translation.compactAiTaskLabel(state.stage)
                else if (state.busy) state.stage else feedback?.title.orEmpty(), progressFraction = state.workProgress.fraction,
            progressText = if (!state.busy) "" else if (aiOperation) {
                val duration = com.example.limbuszhcn.translation.formatTaskDuration(if (state.ai.running) aiElapsed else seconds * 1000)
                if (state.workProgress.total > 0) "${state.workProgress.done}/${state.workProgress.total} · $duration" else duration
            } else (if (state.workProgress.total > 0)
                "${state.workProgress.done}/${state.workProgress.total} · ${((state.workProgress.fraction ?: 0f) * 100).toInt()}%"
                else if (state.launchStep in 0..2) "第 ${state.launchStep + 1}/3 步" else "处理中") + " · ${seconds}秒",
            onClick = { when { state.busy -> onDetails(); state.canResumeGame -> onLaunch(); needsDetection -> onRefresh(); canLaunch -> onLaunch(); else -> onDetails() } })
        if (!state.busy && state.ai.hasPendingTask) TextButton(onClick = onDetails) {
            Text("翻译已暂停 · 查看任务", style = MaterialTheme.typography.bodySmall)
        }
        if (state.failed && !state.busy) {
            TextButton(onClick = onDetails, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                LauncherGlyph(Glyph.Info, C.Error, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("查看失败原因", color = C.Error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun rememberOperationFeedback(state: LauncherUiState): OperationFeedback? {
    var feedback by remember { mutableStateOf<OperationFeedback?>(null) }
    var wasBusy by remember { mutableStateOf(false) }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(state.busy, state.failed) {
        val finished = wasBusy && !state.busy
        wasBusy = state.busy
        feedback = null
        if (finished && !state.failed) {
            feedback = completedOperationFeedback(state.stage)
            if (feedback != null) {
                delay(accessibility?.calculateRecommendedTimeoutMillis(4000L,
                    containsIcons = true, containsText = true, containsControls = false) ?: 4000L)
                feedback = null
            }
        }
    }
    return feedback.takeUnless { state.busy || state.failed }
}

@Composable
private fun AboutLink(onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp)) {
        Text("使用说明", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(5.dp))
        LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(12.dp))
    }
}
