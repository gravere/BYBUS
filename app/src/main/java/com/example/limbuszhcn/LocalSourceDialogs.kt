package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun ImportCoverageDialog(review: ImportCoverageReview, choose: (ImportFillChoice) -> Unit) {
    val report = review.report
    val canFill = report != null && report.missing > 0
    BybusDialog(onDismissRequest = { choose(ImportFillChoice.Keep) }, title = { Text("导入包覆盖") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(review.name, style = MaterialTheme.typography.bodyMedium)
            if (report != null) {
                Text("未覆盖 ${report.missing} 条 · 待核对 ${report.review} 条", style = MaterialTheme.typography.titleSmall)
                Text("已下载的${report.language.displayName}文本" + if (report.unreadable > 0) " · ${report.unreadable} 个文件未读到" else "",
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
                if (canFill) {
                    DetailActionSection("补齐方式") {
                        SheetAction(Glyph.Translation, "零协补齐", onClick = { choose(ImportFillChoice.Official) })
                        DetailActionDivider()
                        SheetAction(Glyph.Ai, "选择 AI 补译范围", onClick = { choose(ImportFillChoice.Ai) })
                    }
                    Text("零协只补空缺，保留原包译文。AI 补译需另行确认。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                } else Text(if (report.unreadable > 0) "已读文本未发现空缺；未读到的文件尚不能判断。"
                    else if (report.review > 0) "没有未覆盖条目；待核对内容可在汉化覆盖中查看。" else "当前文本已覆盖。",
                    color = C.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            review.issue?.let { Text(it, color = C.Muted, style = MaterialTheme.typography.bodyMedium) }
        }
    }, confirmButton = { TextButton({ choose(ImportFillChoice.Keep) }) { Text("保持原样") } })
}

@Composable
internal fun SourceUpdateLinkDialog(source: TranslationSource, enabled: Boolean, dismiss: () -> Unit,
    save: (String, Boolean) -> Unit) {
    var url by remember(source.id) { mutableStateOf(source.updateLink?.url.orEmpty()) }
    var automatic by remember(source.id) { mutableStateOf(source.updateLink?.automatic ?: true) }
    var remove by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (remove) BybusDialog(onDismissRequest = { remove = false }, destructive = true,
        title = { Text("移除更新链接？") }, text = { Text("不再从此链接检查更新，已导入的汉化包会保留。") },
        confirmButton = { TextButton({ save("", false) }, enabled = enabled) { Text("移除") } },
        dismissButton = { TextButton({ remove = false }) { Text("取消") } })
    else BybusDialog(onDismissRequest = dismiss, title = { Text("更新链接") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(url, { if (it.length <= 4096) { url = it; error = null } }, Modifier.fillMaxWidth(),
                label = { Text("汉化包下载链接") }, placeholder = { Text("https://…/汉化包.zip") },
                minLines = 1, maxLines = 4, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = C.Line, focusedBorderColor = C.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = error != null)
            Text("支持 HTTPS ZIP/7z 直链，也可使用 GitHub Releases 的 latest/download 链接。",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启动时自动更新", style = MaterialTheme.typography.bodyMedium)
                    Text("每小时最多检查一次", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(automatic, { automatic = it }, enabled = enabled)
            }
            error?.let { Text(it, color = C.Error, style = MaterialTheme.typography.bodySmall) }
            if (source.updateLink != null) TextButton({ remove = true }, enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = C.Error)) { Text("移除链接") }
        }
    }, confirmButton = { TextButton({
        val valid = runCatching { SourceUpdateLink.downloadUrl(url) }
        valid.onSuccess { save(it, automatic) }.onFailure { error = it.message }
    }, enabled = enabled && url.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } })
}
