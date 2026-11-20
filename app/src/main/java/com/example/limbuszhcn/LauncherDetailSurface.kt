package com.example.limbuszhcn

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

/** Fixed header and a bounded scrolling body on both tablets and compact windows. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LauncherDetailSurface(layout: LauncherLayout, page: String,
    onDismiss: () -> Unit, onBack: (() -> Unit)? = null, compactContent: Boolean = false,
    navigation: @Composable (String) -> Unit = {},
    footer: @Composable (String) -> Unit = {}, content: @Composable (String) -> Unit) {
    // Loaded pages share a stable height; first-load placeholders use a compact bounded panel.
    val panelHeight by androidx.compose.animation.core.animateDpAsState(
        if (compactContent) minOf(layout.panelHeight.dp, 240.dp) else layout.panelHeight.dp,
        tween(240), label = "detail content height")
    val pageStates = rememberSaveableStateHolder()
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismiss)
    var closing by remember { mutableStateOf(false) }
    val presentation = remember { Animatable(0f) }
    val requestDismiss: () -> Unit = { closing = true }
    androidx.activity.compose.BackHandler(onBack = onBack ?: requestDismiss)
    LaunchedEffect(closing) {
        presentation.animateTo(if (closing) 0f else 1f,
            tween(if (closing) 160 else 220, easing = FastOutSlowInEasing))
        if (closing) currentDismiss()
    }
    val body: @Composable (DetailPanelDrag, Dp) -> Unit = { drag, contentHeight ->
      val compact = layout.compactHeight || contentHeight < 480.dp
      val bodyDrag = remember(drag) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.Drag && available.y < 0 && drag.offset > 0)
                    Offset(0f, drag.drag(available.y)) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.Drag && available.y > 0)
                    Offset(0f, drag.drag(available.y)) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag.offset == 0f) return Velocity.Zero
                drag.settle(available.y)
                return Velocity(0f, available.y)
            }
        }
      }
        Column(Modifier.fillMaxWidth().height(contentHeight)) {
            Column(Modifier.fillMaxWidth().draggable(rememberDraggableState { drag.drag(it) },
                Orientation.Vertical, startDragImmediately = drag.offset > 0,
                onDragStarted = { drag.stop() }, onDragStopped = { drag.settle(it) })) {
                Box(Modifier.fillMaxWidth().height(if (compact) 12.dp else 22.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape).background(C.Line)
                        .semantics { contentDescription = "下拉关闭面板" })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = layout.detailPadding.dp,
                    vertical = if (compact) 0.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) ActionIconButton(Glyph.Chevron, "返回上一页", onBack, rotation = 180f)
                Crossfade(page, animationSpec = tween(180), modifier = Modifier.weight(1f).padding(end = 12.dp), label = "detail title") { active ->
                    Text(when (active) {
                        "tools" -> "设置与工具"; "status" -> "运行状态"; "error" -> "准备未完成"
                        "project" -> "翻译项目"; "coverage" -> "汉化覆盖"; "updates" -> "游戏更新"; "app-update" -> "应用更新"
                        "translation" -> "汉化源与更新"; "ai-translation" -> "AI 翻译"; "ai-entries" -> "翻译条目"; "ai-base-update" -> "更新原包译文"; "clear-data" -> "清除数据"
                        "repair" -> "修复"; "resource-preparation" -> "准备游戏资源"; "google" -> "Google 账号与付款"; else -> "关于"
                    }, style = MaterialTheme.typography.headlineSmall)
                }
                ActionIconButton(Glyph.Close, "关闭面板", requestDismiss)
                }
            }
            HorizontalDivider(color = C.Line)
            if (!compact) navigation(page)
            // A child page starts at its top; returning restores its parent's scroll and disclosure state.
            Crossfade(page, animationSpec = tween(180), modifier = Modifier.weight(1f).fillMaxWidth()
                .nestedScroll(bodyDrag), label = "detail page") { active ->
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                    pageStates.SaveableStateProvider(active) {
                        // Keep edge drags inside an opaque viewport; do not stretch the scrolling layer.
                        Column(Modifier.fillMaxSize().background(C.Panel).clipToBounds()
                            .verticalScroll(rememberScrollState())) {
                            // A short window must keep the body reachable even with tall actions.
                            if (compact) navigation(active)
                            Column(Modifier.padding(layout.detailPadding.dp)) { content(active) }
                            if (compact) footer(active)
                        }
                    }
                }
            }
            if (!compact) footer(page)
        }
    }
    Box(Modifier.fillMaxSize().semantics { paneTitle = "详情面板" }) {
        val safeInsets = WindowInsets.safeDrawing
        val systemPadding = safeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).asPaddingValues()
        val safeTop = with(LocalDensity.current) { safeInsets.getTop(this).toDp() }
        val safeBottom = systemPadding.calculateBottomPadding()
        val direction = LocalLayoutDirection.current
        // Draw in the host window from the first frame, including the status-bar backdrop.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val contentHeight = minOf(panelHeight, (maxHeight - safeTop - safeBottom -
                if (layout.centeredDetails) 32.dp else 0.dp).coerceAtLeast(0.dp))
            val centerShift = if (layout.centeredDetails) (safeTop - safeBottom) / 2 else 0.dp
            val surfaceHeight = contentHeight + if (layout.centeredDetails) 0.dp else safeBottom
            val travel = (DetailPanelDragPolicy.travel(maxHeight.value, surfaceHeight.value, layout.centeredDetails) - centerShift.value) * density
            val drag = remember(contentHeight, density, travel) {
                DetailPanelDrag(contentHeight.value * density, density, scope, travel) { currentDismiss() }
            }
            LaunchedEffect(page, drag) { drag.reset() }
            Box(Modifier.matchParentSize().drawBehind {
                drawRect(Color.Black.copy(alpha = .24f * drag.scrimFraction * presentation.value))
            }.pointerInput(requestDismiss) { detectTapGestures { requestDismiss() } })
            val panel = if (layout.centeredDetails) Modifier.align(Alignment.Center)
                .padding(start = 24.dp + systemPadding.calculateStartPadding(direction),
                    end = 24.dp + systemPadding.calculateEndPadding(direction), top = 16.dp, bottom = 16.dp)
                .widthIn(max = layout.panelWidth.dp).fillMaxWidth()
                else Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            Surface(panel.graphicsLayer {
                val distance = if (layout.centeredDetails) 18f else surfaceHeight.value
                translationY = drag.offset + centerShift.value * density + (1f - presentation.value) * distance * density
                alpha = if (layout.centeredDetails) presentation.value else 1f
            }.semantics { dismiss { requestDismiss(); true } },
                shape = if (layout.centeredDetails) RoundedCornerShape(28.dp)
                else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = C.Panel, contentColor = C.Ink, tonalElevation = 0.dp,
                shadowElevation = if (layout.centeredDetails) 8.dp else 0.dp) {
                val padding = if (layout.centeredDetails) Modifier else Modifier.padding(systemPadding)
                // The panel bounds already reserve these insets. Descendant editors must not
                // add the same keyboard space again through imePadding().
                Column(padding.consumeWindowInsets(safeInsets)) { body(drag, contentHeight) }
            }
        }
    }
}
