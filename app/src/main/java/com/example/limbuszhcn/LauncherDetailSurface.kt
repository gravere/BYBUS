package com.example.limbuszhcn

import android.os.Build
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.limbuszhcn.ui.theme.LauncherColors as C

/** Fixed header and a bounded scrolling body on both tablets and compact windows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LauncherDetailSurface(layout: LauncherLayout, page: String,
    onDismiss: () -> Unit, onBack: (() -> Unit)? = null, content: @Composable (String) -> Unit) {
    // Keep every detail page at the same height; long menus scroll within the panel.
    val panelHeight = layout.panelHeight.dp
    val pageStates = rememberSaveableStateHolder()
    val body: @Composable () -> Unit = {
        Column(Modifier.panelBackHandler(onBack ?: onDismiss).fillMaxWidth().height(panelHeight)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = layout.detailPadding.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    LauncherGlyph(Glyph.Chevron, C.Muted, Modifier.size(21.dp).rotate(180f), "返回上一页")
                }
                Crossfade(page, animationSpec = tween(180), modifier = Modifier.weight(1f).padding(end = 12.dp), label = "detail title") { active ->
                    Text(when (active) {
                        "tools" -> "设置与工具"; "status" -> "运行状态"; "error" -> "准备未完成"
                        "project" -> "翻译项目"; "updates" -> "游戏更新"; "app-update" -> "更新"; else -> "关于"
                    }, style = MaterialTheme.typography.headlineSmall)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    LauncherGlyph(Glyph.Close, C.Muted, Modifier.size(21.dp), "关闭面板")
                }
            }
            HorizontalDivider(color = C.Line)
            // A child page starts at its top; returning restores its parent's scroll and disclosure state.
            Crossfade(page, animationSpec = tween(180), modifier = Modifier.weight(1f).fillMaxWidth(), label = "detail page") { active ->
                pageStates.SaveableStateProvider(active) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(layout.detailPadding.dp)) { content(active) }
                }
            }
        }
    }
    if (layout.centeredDetails) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
            dismissOnBackPress = false, usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(horizontal = 24.dp, vertical = 16.dp).widthIn(max = layout.panelWidth.dp).fillMaxWidth(),
                shape = RoundedCornerShape(28.dp), color = C.Panel, contentColor = C.Ink) { body() }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            properties = ModalBottomSheetDefaults.properties(shouldDismissOnBackPress = false),
            containerColor = C.Panel, contentColor = C.Ink,
            scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = .36f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) { body() }
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
