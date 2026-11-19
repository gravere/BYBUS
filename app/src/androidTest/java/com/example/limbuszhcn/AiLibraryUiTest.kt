package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.gamefs.GameTextLanguage
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AiLibraryUiTest {
    @get:Rule val compose = createComposeRule()
    @Composable private fun Panel(page: String, content: @Composable () -> Unit) {
        val config = androidx.compose.ui.platform.LocalConfiguration.current
        val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        Box(Modifier.fillMaxSize()) {
            LauncherDetailSurface(LauncherLayout.calculate(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat(), scale), page, {}) { content() }
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ai-ui-check").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun versionsStayCollapsedUnderTheirOriginalPack() {
        val one = TranslationSource("ai-" + "a".repeat(32), "都市零协会 · 英语", "ai", "r1", baseId = "zero", language = "en", aiEntryCount = 24)
        val two = one.copy(id = "ai-" + "b".repeat(32), name = "都市零协会 · 韩语", language = "kr", aiEntryCount = 18)
        val state = LauncherUiState(sources = SourceState(listOf(TranslationSources.OFFICIAL, one, two), one.id))
        var opened: String? = null
        compose.setContent { LimbusZhCNTheme {
            Panel("ai-translation") {
                AiTranslationPane(state, TranslationActions(), onEntries = { opened = it })
            }
        } }
        compose.onNodeWithText("都市零协会 · 2 个版本").performScrollTo().performClick()
        compose.onNodeWithText(two.name).performScrollTo().assertIsDisplayed()
        screenshot("grouped-packs")
        compose.onNodeWithText(two.name).performClick(); compose.runOnIdle { assertEquals(two.id, opened) }
        compose.onNodeWithText("都市零协会 · 2 个版本").performScrollTo().performClick()
        compose.waitForIdle(); compose.onNodeWithText(two.name).assertDoesNotExist()
    }
    @Test fun individualAndMultipleSelectionReachTheSameTranslationSettings() {
        val report = AiEntryReport("a".repeat(64), "ai-" + "b".repeat(32), "revision", "本地汉化 · 英语", GameTextLanguage.English,
            "c".repeat(64), mapOf(AiEntryKind.Ai to 2), listOf("剧情与对话"))
        val rows = (1..2).map { AiEntry(it, it.toString().padStart(64, '0'), AiEntryKind.Ai, "剧情与对话", "en/StoryData/EN_scene.json",
            "d".repeat(64), "Original dialogue", "已翻译的对白 $it", "/dataList/$it/content", "$it") }
        val state = LauncherUiState(ai = AiUiState(library = AiLibraryState(report, sourceId = report.sourceId)))
        var requested: AiEntryRequest? = null; var removed: AiEntryRequest? = null
        compose.setContent { LimbusZhCNTheme {
            Panel("ai-entries") {
                AiEntriesPane(state, TranslationActions(aiEntries = { _, _, _, _ -> AiEntryPage(rows, 2, 0) }, deleteEntries = { removed = it }), { requested = it })
            }
        } }
        compose.waitUntil { compose.onAllNodesWithText("已翻译的对白 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("选本页").performClick(); compose.onNodeWithText("翻译所选").performClick()
        compose.runOnIdle { assertEquals(rows.map { it.id }.toSet(), requested!!.ids) }
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithText("已翻译的对白 1").performClick()
        compose.onNodeWithText("重译此条").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf(rows[0].id), requested!!.ids) }
        screenshot("entry-detail")
        compose.onNodeWithText("删除译文").performClick()
        compose.runOnIdle { assertNull(removed) }
        compose.onNodeWithText("取消").performClick(); compose.runOnIdle { assertNull(removed) }
    }
    @Test fun keyPickerRenamesAndDragReordersWithoutChangingSelection() {
        val initial = (1..3).map { AiKeyChoice("$it", "密钥 $it", "https://example.test/v1", "model") }
        var reordered: List<String>? = null; var renamed: String? = null; var selected: String? = null
        compose.setContent { LimbusZhCNTheme { AiKeyPicker(initial, "1", true, TranslationActions(
            reorderKeys = { reordered = it }, renameKey = { _, name -> renamed = name }, selectKey = { selected = it }), {}, {}) } }
        compose.onNodeWithContentDescription("修改密钥 2名称").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("备用接口")
        compose.onNodeWithText("保存").performClick(); compose.runOnIdle { assertEquals("备用接口", renamed); assertNull(selected) }
        val distance = compose.onNodeWithContentDescription("拖动排序 密钥 2").fetchSemanticsNode().boundsInRoot.center.y -
            compose.onNodeWithContentDescription("拖动排序 密钥 3").fetchSemanticsNode().boundsInRoot.center.y
        compose.onNodeWithContentDescription("拖动排序 密钥 3").performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(0f, distance), 180); up()
        }
        compose.runOnIdle { assertNotNull(reordered); assertEquals(setOf("1", "2", "3"), reordered!!.toSet()); assertNotEquals(initial.map { it.id }, reordered); assertNull(selected) }
        screenshot("key-management")
    }
}
