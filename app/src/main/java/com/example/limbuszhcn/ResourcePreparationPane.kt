package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun ResourcePreparationPane(state: LauncherUiState, onPrepare: () -> Unit,
    onCheck: () -> Unit, onImport: () -> Unit) {
    val ready = state.translation?.indexActivated == true
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(if (ready) "汉化已准备好" else "还需下载游戏资源", style = MaterialTheme.typography.titleLarge)
        Text(if (ready) "完整汉化已校验，可以启动游戏。" else
            "零协汉化包已校验。首次使用还需要游戏的原始文本：请在游戏内完成资源下载，再返回 BYBUS，完整汉化会自动准备。",
            style = MaterialTheme.typography.bodyMedium, color = C.Muted)
        if (!ready) Text("准备期间部分内容仍为原文。已有资源可通过数据迁移导入。",
            style = MaterialTheme.typography.bodySmall, color = C.Muted)
        Button(onClick = if (ready) onCheck else onPrepare, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (ready) "启动汉化版" else "打开游戏下载资源")
        }
        if (!ready) DetailActionSection("已有资源") {
            SheetAction(Glyph.Refresh, "重新检查", enabled = !state.busy, onClick = onCheck)
            DetailActionDivider()
            SheetAction(Glyph.Import, "导入游戏资源", enabled = !state.busy, navigation = true, onClick = onImport)
        }
    }
}
