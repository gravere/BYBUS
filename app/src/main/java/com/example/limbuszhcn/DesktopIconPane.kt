package com.example.limbuszhcn

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun DesktopIconPane() {
    val context = LocalContext.current
    val icons = remember(context) { DesktopIcons(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(icons.selected()) }
    var custom by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { custom = withContext(Dispatchers.IO) { icons.readCustom() } }
    DisposableEffect(icons) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "pinned_at") { message = "自定义图标已添加到桌面"; failed = false }
        }
        icons.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { icons.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun work(action: suspend () -> String) {
        if (busy) return
        busy = true; message = null; failed = false
        scope.launch {
            try { message = action() }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failed = true; message = error.message ?: "暂时无法设置桌面图标"
            } finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            custom = withContext(Dispatchers.IO) { icons.importImage(uri) }
            "图片已准备好，点「添加到桌面」即可。"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("内置样式", style = MaterialTheme.typography.titleMedium)
        Text("直接切换 BYBUS 的原桌面图标。", style = MaterialTheme.typography.bodyMedium, color = C.Muted)
        val onStyle: (DesktopIconStyle) -> Unit = { style -> work {
            withContext(Dispatchers.IO) { icons.select(style) }
            selected = icons.selected()
            "已切换为${style.label}，桌面可能需要片刻刷新。"
        } }
        DesktopIconGrid(listOf(DesktopIconStyle.Default, DesktopIconStyle.Light, DesktopIconStyle.Dark),
            selected, !busy, 3, onStyle)
        Text("LIMBUS COMPANY", style = MaterialTheme.typography.labelLarge, color = C.Muted)
        DesktopIconGrid(listOf(DesktopIconStyle.Locomotive, DesktopIconStyle.StackedWordmark,
            DesktopIconStyle.Wordmark, DesktopIconStyle.FullLogo), selected, !busy, 4, onStyle)
        HorizontalDivider(color = C.Line)
        Text("自选图片", style = MaterialTheme.typography.titleMedium)
        Text("添加一个新的桌面入口，原图标会保留。若桌面只有「卸载」，请勿用它移除原图标。",
            style = MaterialTheme.typography.bodyMedium, color = C.Muted)
        custom?.let { bitmap ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(bitmap.asImageBitmap(), "自选图标预览", Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)))
            }
        }
        OutlinedButton(onClick = { runCatching { picker.launch(arrayOf("image/*")) }.onFailure {
            failed = true; message = "无法打开图片选择器"
        } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (custom == null) "选择图片" else "更换图片") }
        Button(onClick = { work { icons.requestCustom() } }, enabled = !busy && custom != null && icons.pinSupported,
            modifier = Modifier.fillMaxWidth()) { Text("添加到桌面") }
        if (!icons.pinSupported) Text("当前桌面不支持自定义入口，可使用上方内置样式。", style = MaterialTheme.typography.bodyMedium)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = if (failed) C.Error else C.Accent, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun DesktopIconGrid(styles: List<DesktopIconStyle>, selected: DesktopIconStyle,
    enabled: Boolean, maxColumns: Int, onSelect: (DesktopIconStyle) -> Unit) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fitting = ((maxWidth.value + 12f) / (92f * fontScale.coerceAtLeast(1f) + 12f)).toInt()
            .coerceIn(1, maxColumns)
        val columns = if (styles.size == 4 && fitting == 3) 2 else fitting
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            styles.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { style ->
                        val active = style == selected
                        val color by animateColorAsState(if (active) C.AccentSoft else C.Background,
                            label = "desktop icon selection")
                        val bitmap = remember(style, context.resources.configuration) {
                            val drawable = requireNotNull(context.getDrawable(style.drawable))
                            Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also {
                                drawable.setBounds(0, 0, 192, 192); drawable.draw(Canvas(it))
                            }.asImageBitmap()
                        }
                        Surface(Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                            .border(if (active) 2.dp else 1.dp, if (active) C.Accent else C.Line, RoundedCornerShape(20.dp))
                            .semantics { this.selected = active }
                            .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(style) }, color = color) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Image(bitmap, null, Modifier.size(60.dp))
                                Text(style.label, style = MaterialTheme.typography.labelLarge,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
