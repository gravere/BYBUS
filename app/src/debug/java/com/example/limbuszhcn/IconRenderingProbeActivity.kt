package com.example.limbuszhcn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import com.example.limbuszhcn.ui.theme.LauncherColors as C
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Render the real components in constrained slots; never talks to the game or network. */
class IconRenderingProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LimbusZhCNTheme {
                var busy by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val phase = rememberInfiniteTransition(label = "phase preview").animateFloat(0f, 1f,
                    infiniteRepeatable(tween(4800, easing = LinearEasing)), label = "phase")
                Surface(Modifier.fillMaxSize(), color = C.Background) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("图标状态与比例检查", style = MaterialTheme.typography.titleLarge)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ActionIconButton(Glyph.Refresh, "开始模拟检查", {
                                scope.launch { busy = true; delay(2400); busy = false }
                            }, busy = busy)
                            ActionIconButton(Glyph.Refresh, "禁用刷新", {}, enabled = false)
                            ActionIconButton(Glyph.Refresh, "静态刷新", {})
                            Text(if (busy) "检查中" else "空闲", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("方形、横向受限、纵向受限", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf(24.dp to 24.dp, 48.dp to 16.dp, 16.dp to 48.dp).forEach { (width, height) ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val slot = Modifier.size(width, height).background(C.Panel)
                                    LauncherGlyph(Glyph.Refresh, C.Accent, slot)
                                    LauncherGlyph(Glyph.Account, C.Accent, slot)
                                    LaunchPhaseGlyph(LaunchSymbol.Ready, phase, false, C.Accent, slot)
                                    LauncherSpinner(slot)
                                }
                            }
                        }
                        Glyph.entries.chunked(8).forEach { group ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                group.forEach { glyph ->
                                    Column(Modifier.width(80.dp).height(58.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        LauncherGlyph(glyph, C.Ink, Modifier.size(24.dp))
                                        Text(glyph.name, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LaunchSymbol.entries.forEach { symbol -> LaunchPhaseGlyph(symbol, phase, true, C.Accent, Modifier.size(24.dp)) }
                        }
                        DetailDisclosure("展开方向") { Text("展开后的内容") }
                    }
                }
            }
        }
    }
}
