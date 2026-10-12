package com.example.limbuszhcn

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.limbuszhcn.tracking.ProjectTrackingState
import com.example.limbuszhcn.tracking.ProjectSource
import com.example.limbuszhcn.update.GameUpdateSource
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LauncherScreen(
    state: LauncherUiState,
    projectState: ProjectTrackingState,
    appUpdateState: com.example.limbuszhcn.selfupdate.AppUpdateState,
    onCheckAppUpdate: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    onCancelAppUpdate: () -> Unit,
    onOpenAppRelease: () -> Unit,
    onLaunch: () -> Unit,
    onRefresh: () -> Unit,
    onUpdate: () -> Unit,
    onCheckGameUpdate: () -> Unit,
    onGameStore: () -> Unit,
    onSelectGameSource: (GameUpdateSource) -> Unit,
    onOpenGamePage: (String) -> Unit,
    onOpenInstallSource: () -> Unit,
    onImportGame: () -> Unit,
    onInstallGame: () -> Unit,
    onCancelGameInstall: () -> Unit,
    onSync: () -> Unit,
    onRestore: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onGoogleSettings: () -> Unit,
    onRefreshProject: () -> Unit,
    onSelectProjectSource: (ProjectSource) -> Unit,
    onOpenLink: (String) -> Unit
) {
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var parentSheet by rememberSaveable { mutableStateOf<String?>(null) }
    var handledUpdatesRequest by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(state.updatesRequest) {
        if (state.updatesRequest > 0 && state.updatesRequest != handledUpdatesRequest) {
            handledUpdatesRequest = state.updatesRequest
            parentSheet = null
            sheet = "updates"
        }
    }
    val snackbar = remember { SnackbarHostState() }
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy) {
        val finished = wasBusy && !state.busy
        wasBusy = state.busy
        if (finished && !state.failed) {
            val feedback = when (state.stage) {
                "检查零协更新并校验汉化文件" -> "汉化检查完成"
                "重新同步游戏", "同步游戏，保留已有数据" -> "游戏已同步"
                "正在停用汉化" -> "汉化已停用"
                "正在保存诊断包" -> "诊断包已保存"
                "取消导出" -> "已取消导出"
                else -> null
            }
            if (feedback != null && snackbar.showSnackbar(feedback, actionLabel = "详情",
                    duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) { parentSheet = null; sheet = "status" }
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
                    FilledIconButton(onClick = { open("tools") }, modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = C.Background)) {
                        LauncherGlyph(Glyph.Settings, C.Ink, Modifier.size(22.dp), "设置与工具")
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (layout.twoColumns) {
                        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).verticalScroll(homeScroll)
                            .padding(top = if (layout.compactHeight) 8.dp else 20.dp, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(layout.columnGap.dp),
                            verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                LaunchPanel(state, onLaunch, onRefresh, onGameStore) { open(if (state.failed) "error" else "status") }
                                Spacer(Modifier.height(16.dp))
                                InstallationCard(state) { open("status") }
                                Spacer(Modifier.height(8.dp))
                                AboutLink { open("about") }
                            }
                            Surface(Modifier.weight(1f), color = C.Panel, contentColor = C.Ink, shape = RoundedCornerShape(24.dp)) {
                                Box(Modifier.padding(20.dp)) {
                                    ProjectDetails(projectState, onRefreshProject, onSelectProjectSource, onOpenLink)
                                }
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().verticalScroll(homeScroll)) {
                            Spacer(Modifier.height(if (layout.compactHeight) 12.dp else if (layout.centeredDetails) 20.dp else 36.dp))
                            LaunchPanel(state, onLaunch, onRefresh, onGameStore) { open(if (state.failed) "error" else "status") }
                            Spacer(Modifier.height(20.dp))
                            InstallationCard(state) { open("status") }
                            Spacer(Modifier.height(if (layout.centeredDetails) 16.dp else 28.dp))
                            ProjectCard(projectState) { open("project") }
                            Spacer(Modifier.height(10.dp))
                            AboutLink { open("about") }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                Snackbar(it, shape = RoundedCornerShape(20.dp), containerColor = C.Ink,
                    contentColor = C.OnPrimary, actionColor = C.OnPrimary)
            }
            sheet?.let { page ->
                LauncherDetailSurface(layout, page, onDismiss = ::close,
                    onBack = parentSheet?.let { parent -> { sheet = parent; parentSheet = null } }) { active ->
                    if (active == "project") {
                        ProjectDetails(projectState, onRefreshProject, onSelectProjectSource, onOpenLink)
                    } else if (active == "updates") {
                        GameUpdatesPane(state, onCheckGameUpdate, onSelectGameSource, onOpenGamePage,
                            onOpenInstallSource, onImportGame, onInstallGame, onCancelGameInstall)
                    } else if (active == "app-update") {
                        AppUpdatesPane(appUpdateState, state, onCheckAppUpdate, onDownloadAppUpdate,
                            onInstallAppUpdate, onCancelAppUpdate, onOpenAppRelease, onUpdate)
                    } else {
                        LauncherDetails(active, state, onGameStore = { openChild("updates") },
                            onAppUpdate = { openChild("app-update"); onCheckAppUpdate() },
                            onSync = { close(); onSync() }, onRestore = { close(); onRestore() },
                            onExport = { close(); onExport() }, onSettings = { close(); onSettings() },
                            onGoogleSettings = { close(); onGoogleSettings() },
                            onRefresh = { if (active != "status") close(); onRefresh() }, onAbout = { openChild("about") })
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchPanel(state: LauncherUiState, onLaunch: () -> Unit, onRefresh: () -> Unit,
    onGameStore: () -> Unit, onDetails: () -> Unit) {
    val ready = state.container?.available == true && state.installedVersion != null &&
        state.compatibility?.isSupported == true && state.nativeCompatibilityIssue == null
    Surface(color = C.AccentSoft, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (ready) C.Accent else C.Muted))
                Text(if (state.busy) "正在准备" else if (ready) "设备已就绪" else "设备状态",
                    color = C.OnAccentSoft, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(28.dp))
            PrimaryAction(state, onLaunch, onRefresh, onDetails)
            if (state.gameUpdate.newerSources(state.installedVersionName).isNotEmpty() && !state.busy) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = C.Line)
                Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onGameStore).heightIn(min = 48.dp).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("渠道有较新版本",
                        style = MaterialTheme.typography.bodySmall, color = C.OnAccentSoft, modifier = Modifier.weight(1f))
                    LauncherGlyph(Glyph.Chevron, C.Accent, Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun InstallationCard(state: LauncherUiState, onClick: () -> Unit) {
    Surface(onClick = onClick, color = C.Background, contentColor = C.Ink, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            InstallationRow(Glyph.Bus, "游戏",
                when { state.container == null -> "正在检测"; state.installedVersion == null -> "等待安装";
                    else -> "已安装 · ${state.installedVersionName ?: state.installedVersion}" },
                when { state.gameUpdate.loading -> "检查中";
                    state.gameUpdate.newerSources(state.installedVersionName).isNotEmpty() -> "有新版";
                    state.installedVersion == null -> "查看"; else -> state.gameUpdate.label }, state.installedVersion != null)
            HorizontalDivider(color = C.Line)
            InstallationRow(Glyph.Check, "本地汉化", state.translation?.version ?: "首次启动时准备",
                if (state.translation != null) "已保存" else "待准备", state.translation != null)
        }
    }
}

@Composable
private fun InstallationRow(icon: Glyph, title: String, subtitle: String, value: String, ready: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 300.dp * LocalDensity.current.fontScale
        val statusWidth = maxWidth * .32f
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            LauncherGlyph(icon, C.Muted, Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = C.Muted, style = MaterialTheme.typography.bodySmall)
                if (stacked) Text(value, color = if (ready) C.Ink else C.Muted, style = MaterialTheme.typography.labelMedium)
            }
            if (!stacked) Text(value, color = if (ready) C.Ink else C.Muted, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.widthIn(max = statusWidth))
            Spacer(Modifier.width(9.dp))
            LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(14.dp))
        }
    }
}

@Composable
private fun PrimaryAction(state: LauncherUiState, onLaunch: () -> Unit, onRefresh: () -> Unit, onDetails: () -> Unit) {
    val missing = state.container != null && state.installedVersion == null
    val canLaunch = state.container?.available == true && state.installedVersion != null &&
        state.compatibility?.isSupported == true && state.nativeCompatibilityIssue == null
    val needsDetection = missing || state.container == null
    val title = when {
        state.busy -> when (state.launchStep) { 0 -> "准备游戏"; 1 -> "检查汉化"; 2 -> "正在启动"; else -> "正在处理" }
        needsDetection -> "检测游戏"
        state.nativeCompatibilityIssue != null -> "查看兼容状态"
        !canLaunch -> "查看运行状态"
        state.failed -> "重新启动"
        else -> "启动游戏"
    }
    LaunchButton(title, state.busy, enabled = state.container != null || state.failed,
        onClick = { when { needsDetection -> onRefresh(); canLaunch -> onLaunch(); else -> onDetails() } })
    if (state.busy && state.launchStep >= 0) PreparationSteps(state.launchStep)
    val caption = when {
        state.busy -> state.stage
        state.failed -> "准备未完成 · 查看详情"
        missing -> "从更新渠道安装，或选择完整安装包"
        state.container == null -> "正在检测已安装游戏"
        state.nativeCompatibilityIssue != null -> "当前游戏版本需要兼容更新"
        !canLaunch -> "轻触查看设备与游戏状态"
        else -> "启动前检查游戏与汉化更新"
    }
    if (state.failed && !state.busy) {
        TextButton(onClick = onDetails, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
            Text(caption, color = C.Error, style = MaterialTheme.typography.bodySmall)
        }
    } else {
        Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center) {
            Text(caption, color = C.Muted, style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun AboutLink(onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp)) {
        Text("使用说明", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(5.dp))
        LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(12.dp))
    }
}
