package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AiPriceDetailsUiTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String, dialog: Boolean = false) {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "ai-details-ui").apply { mkdirs() }
        val mode = InstrumentationRegistry.getArguments().getString("screen", "phone")
        val image = (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap()
        File(dir, "$mode-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Composable private fun Panel(content: @Composable () -> Unit) {
        val c = LocalConfiguration.current
        LauncherDetailSurface(LauncherLayout.calculate(c.screenWidthDp.toFloat(), c.screenHeightDp.toFloat(), LocalDensity.current.fontScale), "ai-translation", {}) { content() }
    }
    @Test fun searchAndSelectionKeepTheConfiguredModelAndRejectAChangedCataloguePrice() {
        val first = AiPriceOption("openai", "OpenAI", "model-a", "Fast Model", AiPrice(2.0, 8.0, .5))
        val second = AiPriceOption("relay", "Another Provider", "model-b", "Small Model", AiPrice(1.0, 3.0))
        var pricing by mutableStateOf(AiPricingState("https://relay.example/v1", "my-alias", options = listOf(first, second)))
        var chosen: AiPriceOption? = null
        compose.setContent { LimbusZhCNTheme {
            AiPricePicker("my-alias", pricing, true, {}, {}, { chosen = it })
        } }
        val field = compose.onNodeWithContentDescription("搜索价格模型")
        compose.onNodeWithText("没有找到，可缩短关键词或清空搜索").assertIsDisplayed()
        field.performTextReplacement("OPENAI Fast")
        compose.onNodeWithText("Fast Model").assertIsDisplayed(); compose.onNodeWithText("Small Model").assertDoesNotExist()
        field.performTextReplacement("model-b")
        compose.onNodeWithText("Small Model").assertIsDisplayed()
        field.performTextReplacement("Another Provider")
        compose.onNodeWithText("Small Model").performClick()
        compose.runOnIdle { pricing = pricing.copy(options = listOf(first, second.copy(price = AiPrice(4.0, 6.0)))) }
        compose.onNodeWithText("使用此价格").assertIsNotEnabled()
        compose.onNodeWithText("Small Model").performClick()
        val confirm = compose.onNodeWithText("使用此价格").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText("取消").fetchSemanticsNode().boundsInRoot
        assertTrue(cancel.center.x > confirm.center.x)
        capture("price-search", true)
        compose.onNodeWithText("使用此价格").performClick()
        compose.runOnIdle { assertEquals(4.0, chosen!!.price.input, 0.0); assertEquals("my-alias", pricing.model) }
    }
    @Test fun smallPriceActionsAndStructuredTestFeedbackRemainReadable() {
        compose.setContent { LimbusZhCNTheme { Panel {
            AiTestFeedback(AiTestResult("fixture", "model-a", "连接中…", 1234))
            AiPricingCard("https://relay.example/v1", "model-a", AiUiState(), TranslationActions(), true)
        } } }
        compose.onNodeWithText("原文：接続中…").assertIsDisplayed()
        compose.onNodeWithText("译文：连接中…").assertIsDisplayed()
        compose.onNodeWithText("1.2 秒").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("手动填写").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(12f, layouts.single().layoutInput.style.fontSize.value, .01f)
        capture("test-and-prices")
        val layout = layouts.single()
        // Text may retain a wider paragraph constraint after its node shrinks to intrinsic width.
        // Check the actual visible line and its glyph bounds, not the paragraph's unused extent.
        assertEquals(1, layout.lineCount)
        assertEquals("手动填写".length, layout.getLineEnd(0, visibleEnd = true))
        assertTrue("Last glyph exceeds label bounds", layout.getBoundingBox(3).right <= layout.size.width + 1f)
        assertFalse(layout.didOverflowHeight)
    }
    @Test fun undoShowsPackageTimeScopeExamplesAndTheExactRestoreTarget() {
        val source = TranslationSource("ai-" + "a".repeat(32), "我的汉化 · 英语", "ai", "b".repeat(64), baseId = "zero", language = "en")
        val summary = AiChangeSummary(AiChangeKind.Translation, 1795143660000, added = 1, replaced = 1, files = 1,
            categories = mapOf("战斗" to 2), examples = listOf(AiChangeExample("战斗", "Attack", "旧译文", "攻击")))
        val point = AiUndoPoint(source.id, source.revision, null, "zero", summary)
        val sources = SourceState(listOf(TranslationSources.OFFICIAL, source), source.id, undo = point)
        var confirming by mutableStateOf(false)
        compose.setContent { LimbusZhCNTheme { Panel { AiUndoCard(point, sources, true) { confirming = true } }
            if (confirming) BybusDialog(onDismissRequest = { confirming = false }, destructive = true, title = { Text("撤回这次改动？") },
                text = { AiUndoConfirmation(point, sources) }, confirmButton = { TextButton({}) { Text("撤回") } },
                dismissButton = { TextButton({ confirming = false }) { Text("取消") } })
        } }
        compose.onNodeWithText("我的汉化 · 英语").assertIsDisplayed()
        compose.onNodeWithText("AI 翻译 · 2 条").assertIsDisplayed()
        compose.onNodeWithText("查看改动").performClick()
        compose.onNodeWithText("改动前：旧译文").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("改动后：攻击").assertIsDisplayed()
        capture("undo-details")
        compose.onNodeWithText("撤回这次改动").performScrollTo().performClick()
        compose.onNodeWithText("这次新生成的汉化包将被移除。").assertIsDisplayed()
        compose.onNodeWithText("撤回后使用「都市零协会」。", substring = true).assertIsDisplayed()
        capture("undo-confirmation", true)
        compose.onNodeWithText("取消").performClick(); compose.onNodeWithText("撤回这次改动？").assertDoesNotExist()
    }
}
