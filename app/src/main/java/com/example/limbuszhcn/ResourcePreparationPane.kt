package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun ResourcePreparationPane(state: LauncherUiState, onCheck: () -> Unit, onImport: () -> Unit) {
    val ready = state.translation?.let { it.liveResourcesReady || it.indexActivated } == true
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(if (ready) "汉化已准备好" else "汉化资源尚未就绪", style = MaterialTheme.typography.titleLarge)
        Text(if (ready) "完整汉化已校验，可以启动游戏。" else
            "请重新检查汉化包。准备完成后即可启动，游戏资源可在首次进入时正常下载。",
            style = MaterialTheme.typography.bodyMedium, color = C.Muted)
        Button(onClick = onCheck, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (ready) "启动汉化版" else "重新准备汉化")
        }
        if (!ready) DetailActionSection("已有资源") {
            SheetAction(Glyph.Import, "导入游戏资源", enabled = !state.busy, navigation = true, onClick = onImport)
        }
    }
}
