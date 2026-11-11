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
    resumed: Boolean,
    onCheckAppUpdate: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    onCancelAppUpdate: () -> Unit,
    onOpenAppRelease: () -> Unit,
    onLaunch: () -> Unit,
    onPrepareResources: () -> Unit = {},
    onRefresh: () -> Unit,
    onUpdate: () -> Unit,
    onCheckGameUpdate: () -> Unit,
    onSelectGameSource: (GameUpdateSource) -> Unit,
    onOpenGamePage: (String) -> Unit,
    onOpenInstallSource: () -> Unit,
    onImportGame: () -> Unit,
    onInstallGame: () -> Unit,
    onCancelGameInstall: () -> Unit,
    onSync: () -> Unit,
    onMigration: () -> Unit,
    onRestore: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onGoogleSettings: () -> Unit,
    onCheckGooglePayment: () -> Unit = {},
    onEnableGooglePayment: () -> Unit = {},
    onRefreshProject: () -> Unit,
    onSelectProjectSource: (ProjectSource) -> Unit,
    onOpenLink: (String) -> Unit
) {
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var parentSheet by rememberSaveable { mutableStateOf<String?>(null) }
    var updateTab by rememberSaveable { mutableStateOf(UpdateTab.App) }
    var handledUpdatesRequest by rememberSaveable { mutableLongStateOf(0L) }
    var handledPreparationRequest by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(state.resourcePreparationRequest) {
        if (state.resourcePreparationRequest > 0 && state.resourcePreparationRequest != handledPreparationRequest) {
            handledPreparationRequest = state.resourcePreparationRequest
            parentSheet = null
            sheet = "resource-preparation"
        }
    }
    LaunchedEffect(state.updatesRequest) {
        if (state.updatesRequest > 0 && state.updatesRequest != handledUpdatesRequest) {
            handledUpdatesRequest = state.updatesRequest
            parentSheet = null
            sheet = "updates"
        }
    }
    val haptic = LocalHapticFeedback.current
    fun open(page: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        parentSheet = null
        sheet = page
    }
    fun openChild(page: String) { parentSheet = sheet; sheet = page }
    fun close() { parentSheet = null; sheet = null }

    Surface(Modifier.fillMaxSize(), color = C.Background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Use the space allocated to this window, including split screen.
            // Large text needs a wider column, not a smaller font.
            val layout = LauncherLayout.calculate(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale)
            val homeScroll = rememberScrollState()
            Column(Modifier.widthIn(max = layout.contentMaxWidth.dp).fillMaxSize().align(Alignment.TopCenter)
                .padding(horizontal = layout.margin.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    LauncherGlyph(Glyph.Mark, C.Accent, Modifier.size(30.dp))
                    Text(androidx.compose.ui.res.stringResource(R.string.app_name), fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 2.6.sp, color = C.Ink,
                        modifier = Modifier.padding(start = 11.dp).weight(1f))
                    Box {
                        FilledIconButton(onClick = { open("tools") }, modifier = Modifier.size(48.dp).semantics {
                            if (appUpdateState.updateAvailable) stateDescription = "有 BYBUS 新版本"
                        }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = C.Background)) {
                            LauncherGlyph(Glyph.Settings, C.Ink, Modifier.size(22.dp), "设置与工具")
                        }
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
                                LaunchActions(state, onLaunch, onRefresh, resumed && sheet == null,
                                    onDetails = { open(if (state.failed) "error" else "status") },
                                    modifier = Modifier.width(layout.actionWidth.dp))
                                ProjectCard(projectState, Modifier.weight(1f).fillMaxHeight()) { open("project") }
                            }
                        }
                    } else {
                        val groupHeight = minOf(bodyHeight, 600.dp)
                        Column(Modifier.fillMaxWidth().align(Alignment.Center)
                            .verticalScroll(homeScroll).heightIn(min = groupHeight).padding(vertical = 16.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            InstallationCard(state) { open("status") }
                            LaunchActions(state, onLaunch, onRefresh, resumed && sheet == null,
                                onDetails = { open(if (state.failed) "error" else "status") },
                                modifier = Modifier.widthIn(max = layout.actionWidth.dp).fillMaxWidth())
                            ProjectCard(projectState) { open("project") }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                    AboutLink { open("about") }
                }
            }
            sheet?.let { page ->
                LauncherDetailSurface(layout, page, onDismiss = ::close,
                    onBack = parentSheet?.let { parent -> { sheet = parent; parentSheet = null } },
                    navigation = { active -> if (active == "app-update") {
                        UpdateTabs(updateTab, state.busy && state.stage == "检查零协更新并校验汉化文件") { updateTab = it }
                    } },
                    footer = { active -> if (active == "app-update") {
                        if (updateTab == UpdateTab.App) AppUpdateControls(appUpdateState, state, onDownloadAppUpdate, onInstallAppUpdate, onCancelAppUpdate, layout.detailPadding.dp)
                        else TranslationUpdateControls(state, onUpdate, layout.detailPadding.dp)
                    } }) { active ->
                    if (active == "google") {
                        GooglePaymentPane(state, onCheckGooglePayment, onEnableGooglePayment,
                            onSettings = { onGoogleSettings() })
                    } else if (active == "resource-preparation") {
                        ResourcePreparationPane(state, onPrepare = { close(); onPrepareResources() },
                            onCheck = onLaunch, onImport = onMigration)
                    } else if (active == "project") {
                        ProjectDetails(projectState, onRefreshProject, onSelectProjectSource, onOpenLink)
                    } else if (active == "updates") {
                        GameUpdatesPane(state, onCheckGameUpdate, onSelectGameSource, onOpenGamePage,
                            onOpenInstallSource, onImportGame, onInstallGame, onCancelGameInstall)
                    } else if (active == "app-update") {
                        if (updateTab == UpdateTab.App) AppUpdatesPane(appUpdateState, onCheckAppUpdate, onOpenAppRelease)
                        else TranslationUpdatesPane(state)
                    } else {
                        LauncherDetails(active, state, onGameStore = { openChild("updates") },
                            appUpdateAvailable = appUpdateState.updateAvailable,
                            onAppUpdate = { updateTab = UpdateTab.App; openChild("app-update"); onCheckAppUpdate() },
                            onSync = { close(); onSync() }, onRestore = { close(); onRestore() },
                            onMigration = onMigration,
                            onExport = { close(); onExport() }, onSettings = { close(); onSettings() },
                            onGoogleSettings = { openChild("google"); onCheckGooglePayment() },
                            onRefresh = { if (active != "status") close(); onRefresh() }, onAbout = { openChild("about") })
                    }
                }
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
        InstallationRow(Glyph.Translation, "本地汉化", state.translation?.version
            ?: if (state.resourcePreparation != null) "汉化包已校验 · 等待游戏资源" else "首次启动时准备")
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
    val feedback = rememberOperationFeedback(state)
    val missing = state.container != null && state.installedVersion == null
    val canLaunch = state.container?.available == true && state.installedVersion != null &&
        state.compatibility?.isSupported == true && state.nativeCompatibilityIssue == null
    val needsDetection = missing || state.container == null
    val action = when {
        state.busy -> when (state.launchStep) {
            0 -> LaunchAction.Prepare; 1 -> LaunchAction.Translate; 2 -> LaunchAction.Starting; else -> LaunchAction.Working
        }
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
        LaunchButton(action, enabled = state.container != null || state.failed,
            step = state.launchStep, animated = animated, feedback = feedback,
            status = if (state.busy) state.stage else feedback.orEmpty(),
            onClick = { when { needsDetection -> onRefresh(); canLaunch -> onLaunch(); else -> onDetails() } })
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
private fun rememberOperationFeedback(state: LauncherUiState): String? {
    var feedback by remember { mutableStateOf<String?>(null) }
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
