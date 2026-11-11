package com.example.limbuszhcn

import android.os.Build
import android.view.View
import android.view.Gravity
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.example.limbuszhcn.ui.theme.LauncherColors as C

/** Fixed header and a bounded scrolling body on both tablets and compact windows. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LauncherDetailSurface(layout: LauncherLayout, page: String,
    onDismiss: () -> Unit, onBack: (() -> Unit)? = null,
    navigation: @Composable (String) -> Unit = {},
    footer: @Composable (String) -> Unit = {}, content: @Composable (String) -> Unit) {
    // Keep every detail page at the same height; long menus scroll within the panel.
    val panelHeight = layout.panelHeight.dp
    // Read the host's live insets: older floating dialogs dispatch zero bar insets.
    val systemPadding = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).asPaddingValues()
    val pageStates = rememberSaveableStateHolder()
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismiss)
    val drag = remember(panelHeight, density) { DetailPanelDrag(panelHeight.value * density, density, scope) { currentDismiss() } }
    LaunchedEffect(page, drag) { drag.reset() }
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
    val body: @Composable () -> Unit = {
        Column(Modifier.panelBackHandler(onBack ?: onDismiss).fillMaxWidth().height(panelHeight)) {
            Column(Modifier.fillMaxWidth().draggable(rememberDraggableState { drag.drag(it) },
                Orientation.Vertical, startDragImmediately = drag.offset > 0,
                onDragStarted = { drag.stop() }, onDragStopped = { drag.settle(it) })) {
                Box(Modifier.fillMaxWidth().height(if (layout.compactHeight) 12.dp else 22.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape).background(C.Line)
                        .semantics { contentDescription = "下拉关闭面板" })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = layout.detailPadding.dp,
                    vertical = if (layout.compactHeight) 0.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(21.dp).rotate(180f), "返回上一页")
                }
                Crossfade(page, animationSpec = tween(180), modifier = Modifier.weight(1f).padding(end = 12.dp), label = "detail title") { active ->
                    Text(when (active) {
                        "tools" -> "设置与工具"; "status" -> "运行状态"; "error" -> "准备未完成"
                        "project" -> "翻译项目"; "updates" -> "游戏更新"; "app-update" -> "更新"
                        "repair" -> "修复"; "resource-preparation" -> "准备游戏资源"; "google" -> "Google 账号与付款"; else -> "关于"
                    }, style = MaterialTheme.typography.headlineSmall)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    LauncherGlyph(Glyph.Close, C.Muted, Modifier.size(21.dp), "关闭面板")
                }
                }
            }
            HorizontalDivider(color = C.Line)
            if (!layout.compactHeight) navigation(page)
            // A child page starts at its top; returning restores its parent's scroll and disclosure state.
            Crossfade(page, animationSpec = tween(180), modifier = Modifier.weight(1f).fillMaxWidth()
                .nestedScroll(bodyDrag), label = "detail page") { active ->
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                    pageStates.SaveableStateProvider(active) {
                        // Keep edge drags inside an opaque viewport; do not stretch the scrolling layer.
                        Column(Modifier.fillMaxSize().background(C.Panel).clipToBounds()
                            .verticalScroll(rememberScrollState())) {
                            // A short window must keep the body reachable even with tall actions.
                            if (layout.compactHeight) navigation(active)
                            Column(Modifier.padding(layout.detailPadding.dp)) { content(active) }
                            if (layout.compactHeight) footer(active)
                        }
                    }
                }
            }
            if (!layout.compactHeight) footer(page)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        dismissOnBackPress = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val lightNavigation = C.Panel.luminance() > .5f
        SideEffect {
            window?.setGravity(if (layout.centeredDetails) Gravity.CENTER else Gravity.BOTTOM)
            window?.setDimAmount(.36f)
            window?.let {
                // Older Compose floating themes retain the native fit-inset mask.
                // Let this window paint to its edge; the Surface below pads controls.
                if (Build.VERSION.SDK_INT >= 30 && it.attributes.fitInsetsTypes != 0) {
                    it.attributes = it.attributes.apply { setFitInsetsTypes(0) }
                }
                @Suppress("DEPRECATION")
                it.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= 28) {
                    @Suppress("DEPRECATION")
                    it.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
                }
                if (Build.VERSION.SDK_INT >= 29) it.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightNavigationBars = lightNavigation
            }
        }
        val panel = if (layout.centeredDetails) Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            .widthIn(max = layout.panelWidth.dp).fillMaxWidth() else Modifier.fillMaxWidth()
        Surface(panel.graphicsLayer { translationY = drag.offset }.semantics { dismiss { onDismiss(); true } },
            shape = if (layout.centeredDetails) RoundedCornerShape(28.dp)
            else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = C.Panel, contentColor = C.Ink, tonalElevation = 0.dp) {
            // Paint the bar area with the panel, but keep every control above it.
            // A centered dialog does not touch the bar and must not add a phantom footer.
            val safeInsets = if (layout.centeredDetails) Modifier else Modifier.padding(systemPadding)
            Column(safeInsets) { body() }
        }
    }
}

/** Handle back in the panel's own window, before a modal can hide itself. */
@Composable
private fun Modifier.panelBackHandler(onBack: () -> Unit): Modifier {
    val view = LocalView.current
    val currentBack by rememberUpdatedState(onBack)
    val focus = remember { FocusRequester() }
    DisposableEffect(view) {
        var unregister: (() -> Unit)? = null
        fun register() {
            unregister?.invoke()
            unregister = if (Build.VERSION.SDK_INT >= 33) {
                PanelBackApi33.register(view) { currentBack() }
            } else null
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = register()
            override fun onViewDetachedFromWindow(v: View) {
                unregister?.invoke()
                unregister = null
            }
        }
        view.addOnAttachStateChangeListener(listener)
        if (view.isAttachedToWindow) register()
        onDispose {
            view.removeOnAttachStateChangeListener(listener)
            unregister?.invoke()
        }
    }
    LaunchedEffect(focus) { focus.requestFocus() }
    return onPreviewKeyEvent { event ->
        if (event.key != Key.Back) false
        else {
            if (event.type == KeyEventType.KeyUp && !event.nativeKeyEvent.isCanceled) currentBack()
            true
        }
    }.focusRequester(focus).focusable()
}

@RequiresApi(33)
private object PanelBackApi33 {
    fun register(view: View, onBack: () -> Unit): (() -> Unit)? {
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
        val callback = OnBackInvokedCallback(onBack)
        // Dialog has its own default-priority callback; consume back in this modal window first.
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        return { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}
