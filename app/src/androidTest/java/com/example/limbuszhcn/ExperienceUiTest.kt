package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.limbuszhcn.tracking.*
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExperienceUiTest {
    @get:Rule val compose=createComposeRule()
    @Composable private fun Panel(page:String,compact:Boolean=false,content:@Composable ()->Unit) {
        val c=LocalConfiguration.current
        LauncherDetailSurface(LauncherLayout.calculate(c.screenWidthDp.toFloat(),c.screenHeightDp.toFloat(),LocalDensity.current.fontScale),page,{},compactContent=compact){content()}
    }
    private fun capture(name:String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        android.os.SystemClock.sleep(250) // Let SurfaceFlinger present the settled Compose frame.
        val i=InstrumentationRegistry.getInstrumentation()
        val dir=File(i.targetContext.getExternalFilesDir(null),"experience-ui").apply{mkdirs()}
        val mode=InstrumentationRegistry.getArguments().getString("screen","phone")
        i.uiAutomation.takeScreenshot().let { image-> File(dir,"$mode-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };image.recycle() }
    }
    @Test fun manualPricesAreEditableAndDialogActionsStayCentered() {
        var saved:AiPrice?=null
        var pricing by mutableStateOf(AiUiState())
        compose.setContent { LimbusZhCNTheme { Panel("ai-translation") {
            AiPricingCard("https://relay.example/v1","model",pricing,TranslationActions(savePrice={_,_,price->saved=price}),true)
        } } }
        compose.onNodeWithText("手动填写").performClick()
        compose.onNode(hasSetTextAction() and hasText("输入单价")).performTextInput("2")
        compose.onNode(hasSetTextAction() and hasText("输出单价")).performTextInput("8")
        compose.onNode(hasSetTextAction() and hasText("缓存输入（可选）")).performTextInput("0.5")
        compose.onNode(hasSetTextAction() and hasText("币种，例如 USD / CNY")).performTextReplacement("CNY")
        // A delayed public catalogue response cannot replace values being edited.
        compose.runOnIdle { pricing=pricing.copy(pricing=AiPricingState("https://relay.example/v1","model",AiPrice(10.0,20.0))) }
        compose.onNode(hasSetTextAction() and hasText("币种，例如 USD / CNY")).performImeAction()
        compose.onNodeWithText("保存").assertIsDisplayed();compose.onNodeWithText("取消").assertIsDisplayed()
        val left=compose.onNodeWithText("保存").fetchSemanticsNode().boundsInRoot
        val right=compose.onNodeWithText("取消").fetchSemanticsNode().boundsInRoot
        assertTrue(right.center.x>left.center.x);assertEquals(left.center.y,right.center.y,3f)
        capture("manual-price")
        compose.onNodeWithText("保存").performClick();compose.runOnIdle { assertEquals(AiPrice(2.0,8.0,.5,"CNY",true).copy(fetchedAt=0),saved!!.copy(fetchedAt=0)) }
    }
    @Test fun sourcesCollapseAndSwitchBackToDefaultWithoutLosingProjectLink() {
        val ai=TranslationSource("ai-"+"a".repeat(32),"都市零协会 · 英语","ai","b".repeat(64),baseId="zero",language="en",aiEntryCount=12)
        val state=LauncherUiState(sources=SourceState(listOf(TranslationSources.OFFICIAL,ai),ai.id))
        var project=false;var selected:String?=null
        compose.setContent { LimbusZhCNTheme { Panel("translation") {
            TranslationSourcesPane(state,TranslationActions(select={selected=it}),onProject={project=true},onAi={})
        } } }
        compose.onNodeWithText("首次启动游戏后可检查").assertExists()
        compose.onNodeWithText("本次检查未完成").assertDoesNotExist()
        compose.onNodeWithText("AI 版本 · 1").performScrollTo().performClick()
        compose.onAllNodesWithText(ai.name).onLast().performScrollTo().assertIsDisplayed()
        capture("sources-expanded")
        compose.onNodeWithContentDescription("查看零协项目状态").performScrollTo().performClick()
        compose.runOnIdle{assertTrue(project);assertNull(selected)}
        compose.onNodeWithText("都市零协会").performClick()
        compose.onNodeWithText("切换汉化源？").assertIsDisplayed();compose.onNodeWithText("切换").performClick()
        compose.runOnIdle{assertEquals("zero",selected)}
    }
    @Test fun explicitCoverageFailuresRemainVisible() {
        val state=LauncherUiState(coverage=CoverageState(error="游戏文本读取失败"))
        compose.setContent { LimbusZhCNTheme { CoverageSummary(state,{},{}) } }
        compose.onNodeWithText("本次检查未完成").assertIsDisplayed()
        compose.onNodeWithText("游戏文本读取失败").assertIsDisplayed()
        compose.onNodeWithText("首次启动游戏后可检查").assertDoesNotExist()
    }
    @Test fun initialProjectLoadingHasCompactContentAndClearStatus() {
        compose.setContent { LimbusZhCNTheme { Panel("project",true) {
            ProjectDetails(ProjectTrackingState(github=GitHubTrackerState(loading=true)),{},{},{})
        } } }
        compose.onNodeWithText("正在读取发布记录").assertIsDisplayed()
        compose.onNodeWithText("正在连接零协会 GitHub。").assertDoesNotExist()
        capture("project-loading")
        val label=compose.onNodeWithText("正在读取发布记录").fetchSemanticsNode().boundsInRoot
        assertTrue("Loading status must be visible without scrolling", label.bottom < compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom - 16)
    }
}
