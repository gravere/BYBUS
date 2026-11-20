package com.example.limbuszhcn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.limbuszhcn.translation.AiKeyChoice
import kotlinx.coroutines.launch
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun AiKeyPicker(keys: List<AiKeyChoice>, active: String?, enabled: Boolean, actions: TranslationActions,
    onRemove: (AiKeyChoice) -> Unit, onDismiss: () -> Unit, error: String? = null) {
    var order by remember { mutableStateOf(keys) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var start by remember { mutableFloatStateOf(0f) }
    var delta by remember { mutableFloatStateOf(0f) }
    var renaming by remember { mutableStateOf<AiKeyChoice?>(null) }
    var name by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(keys, enabled) { if (dragging == null && enabled) order = keys }
    fun move(from: Int, to: Int) {
        if (from !in order.indices || to !in order.indices || from == to) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun commit() { dragging = null; delta = 0f; if (order.map { it.id } != keys.map { it.id }) actions.reorderKeys(order.map { it.id }) }
    BybusDialog(onDismissRequest = { if (dragging == null) onDismiss() }, title = { Text("已保存的密钥") }, text = {
        Column {
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.Error) }
            Text("长按右侧手柄拖动排序", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), state = list) {
                items(order, key = { it.id }) { choice ->
                    val moving = dragging == choice.id
                    val offset = if (moving) start + delta - (list.layoutInfo.visibleItemsInfo.find { it.key == choice.id }?.offset ?: 0) else 0f
                    Surface(Modifier.fillMaxWidth().zIndex(if (moving) 1f else 0f).graphicsLayer { translationY = offset },
                        color = if (moving) C.AccentSoft else androidx.compose.ui.graphics.Color.Transparent,
                        shadowElevation = if (moving) 4.dp else 0.dp) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable(enabled = enabled && dragging == null) { actions.selectKey(choice.id); onDismiss() }
                                .padding(vertical = 10.dp, horizontal = 2.dp)) {
                                Text(choice.name, color = if (choice.id == active) C.Accent else C.Ink, style = MaterialTheme.typography.bodyMedium)
                                Text(if (choice.id == active) "当前使用" else choice.endpoint.removePrefix("https://"), color = C.Muted,
                                    style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            }
                            ActionIconButton(Glyph.Edit, "修改${choice.name}名称", { renaming = choice; name = choice.name }, enabled = enabled && dragging == null)
                            ActionIconButton(Glyph.Delete, "移除${choice.name}", { onRemove(choice) }, enabled = enabled && dragging == null)
                            val ink = C.Muted
                            Box(Modifier.size(36.dp, 48.dp).semantics {
                                contentDescription = "拖动排序 ${choice.name}"
                                customActions = listOf(CustomAccessibilityAction("上移") {
                                    if (enabled) { val i = order.indexOfFirst { it.id == choice.id }; move(i, i - 1); commit() }; enabled
                                }, CustomAccessibilityAction("下移") {
                                    if (enabled) { val i = order.indexOfFirst { it.id == choice.id }; move(i, i + 1); commit() }; enabled
                                })
                            }.pointerInput(choice.id, enabled) {
                                if (enabled) detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragging = choice.id; delta = 0f
                                        start = list.layoutInfo.visibleItemsInfo.find { it.key == choice.id }?.offset?.toFloat() ?: 0f
                                    }, onDragEnd = { commit() }, onDragCancel = { dragging = null; delta = 0f; order = keys },
                                    onDrag = { change, amount ->
                                        change.consume(); delta += amount.y
                                        val info = list.layoutInfo.visibleItemsInfo.find { it.key == choice.id }
                                        if (info != null) {
                                            val center = start + delta + info.size / 2f
                                            val target = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key != choice.id && center in it.offset.toFloat()..(it.offset + it.size).toFloat() }
                                            if (target != null) move(order.indexOfFirst { it.id == choice.id }, order.indexOfFirst { it.id == target.key })
                                            if (center > list.layoutInfo.viewportEndOffset - 32 || center < list.layoutInfo.viewportStartOffset + 32)
                                                scope.launch { val scrolled = list.scrollBy(amount.y); start -= scrolled }
                                        }
                                    })
                            }, contentAlignment = Alignment.Center) {
                                Canvas(Modifier.size(14.dp, 22.dp)) {
                                    for (x in listOf(.25f, .75f)) for (y in listOf(.2f, .5f, .8f))
                                        drawCircle(ink, 1.4.dp.toPx(), Offset(size.width * x, size.height * y))
                                }
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss, enabled = dragging == null) { Text("关闭") } })
    renaming?.let { choice ->
        val invalid = runCatching { com.example.limbuszhcn.translation.AiKeyOrder.name(name, choice.id, keys) }.exceptionOrNull()?.message
        BybusDialog(onDismissRequest = { renaming = null }, title = { Text("修改密钥名称") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(name, { name = it.take(48) }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium)
        if (name.isNotBlank() && invalid != null) Text(invalid, color = C.Error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ renaming = null; actions.renameKey(choice.id, name) }, enabled = enabled && invalid == null) { Text("保存") } },
        dismissButton = { TextButton({ renaming = null }) { Text("取消") } }) }
}
