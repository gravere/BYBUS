package com.example.limbuszhcn

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class TranslationInteractionUiTest {
    @get:Rule val compose = createComposeRule()
    private val rows = listOf(
        CoverageEntry(1, CoverageKind.Missing, "剧情与对话", "jp/StoryData/JP_Story.json", "This is a story line.", null, "s1", "/dataList/0/content"),
        CoverageEntry(2, CoverageKind.Review, "战斗与技能", "jp/JP_Skill.json", "スキルの説明", "译文です", "2", "/dataList/0/desc"),
        CoverageEntry(3, CoverageKind.Missing, "战斗与技能", "jp/JP_Skill.json", "Another skill description.", null, "3", "/dataList/1/desc"))
    private val report = CoverageReport("都市零协会", 1000, 2, 3, 1, 2, 1, 2, 0,
        listOf(CoverageFile(rows[0].resource, "剧情与对话", 1, 0, emptyList()), CoverageFile(rows[1].resource, "战斗与技能", 1, 1, emptyList())),
        reviewEligible = 1, entryIndex = CoverageIndexRef("a".repeat(64), "11111111-1111-1111-1111-111111111111"))
    private val coverage = CoverageState(report = report, fingerprint = "checked", language = report.language)
    private val count: CoverageCountReader = { _, filter, numbers, _ -> rows.count { filter.includes(it) && (numbers == null || it.number in numbers) } }
    @Composable private fun Panel(page: String, content: @Composable () -> Unit) {
        val c = LocalConfiguration.current
        LauncherDetailSurface(LauncherLayout.calculate(c.screenWidthDp.toFloat(), c.screenHeightDp.toFloat(), LocalDensity.current.fontScale), page, {}) { content() }
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle(); android.os.SystemClock.sleep(600)
        val i = InstrumentationRegistry.getInstrumentation()
        val dir = File(i.targetContext.getExternalFilesDir(null), "interaction-ui").apply { mkdirs() }
        val mode = InstrumentationRegistry.getArguments().getString("screen", "phone")
        i.uiAutomation.takeScreenshot().let { image -> File(dir, "$mode-$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }; image.recycle() }
        if (name == "home-progress") File(dir, "$mode-home-content.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun coverageCategoryAndIndividualSelectionReachTheSameTaskSettings() {
        var request: CoverageRequest? = null
        compose.setContent { LimbusZhCNTheme { Panel("coverage") {
            var kind by remember { mutableStateOf<CoverageKind?>(null) }
            CoverageEntryBrowser(report, kind, { kind = it }, { _, filter, page, _ -> rows.filteredPage(filter, page) },
                coverage, count, true) { request = it }
        } } }
        compose.onNodeWithContentDescription("筛选内容类别").performClick()
        compose.onNode(hasText("剧情与对话") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("选择第 1 条").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithText("翻译所选 1 条").assertIsEnabled() }.isSuccess }
        capture("coverage-selection")
        compose.onNodeWithText("翻译所选 1 条").performClick()
        compose.runOnIdle {
            assertEquals(setOf(1), request!!.numbers); assertEquals("剧情与对话", request!!.filter.category)
            assertEquals(1, request!!.count); assertEquals("checked", request!!.fingerprint)
        }
    }
    @Test fun reviewAndContentCategoryNeverExpandIntoAllMissingEntries() {
        var request: CoverageRequest? = null
        val state = LauncherUiState(coverage = coverage, ai = AiUiState(hasKey = true))
        compose.setContent { LimbusZhCNTheme { Panel("ai-translation") {
            AiTranslationPane(state, TranslationActions(coverageCount = count, generateCoverage = { _, _, chosen -> request = chosen }))
        } } }
        compose.onNodeWithText("待核对").performScrollTo().performClick()
        compose.onNodeWithContentDescription("筛选内容类别").performScrollTo().performClick()
        compose.onNodeWithText("战斗与技能").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithText("当前范围 1 条").assertExists() }.isSuccess }
        compose.onNodeWithText("生成并使用").performScrollTo()
        capture("ai-filtered")
        compose.onNodeWithText("生成并使用").performClick()
        compose.onNodeWithText("开始翻译").performClick()
        compose.runOnIdle { assertEquals(CoverageKind.Review, request!!.filter.kind); assertEquals("战斗与技能", request!!.filter.category); assertEquals(1, request!!.count) }
    }
    @Test fun homeShowsActualWorkAndReopensItsDetails() {
        var opened = false
        val state = LauncherUiState(busy = true, stage = "检查本地文本", workProgress = WorkProgress(40, 100, android.os.SystemClock.elapsedRealtime()), taskPage = "coverage")
        compose.setContent { LimbusZhCNTheme { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PrimaryAction(state, {}, {}, { opened = true }, false)
            }
        } } }
        compose.onNodeWithText("检查本地文本").assertIsDisplayed()
        compose.onNode(hasText("40/100", substring = true)).assertIsDisplayed()
        capture("home-progress")
        compose.onNodeWithText("检查本地文本").performClick()
        compose.runOnIdle { assertTrue(opened); assertEquals("coverage", state.taskDestination()) }
    }
}
