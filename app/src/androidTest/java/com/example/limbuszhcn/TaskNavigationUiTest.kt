package com.example.limbuszhcn

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import com.example.limbuszhcn.selfupdate.AppUpdateState
import com.example.limbuszhcn.tracking.ProjectTrackingState
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises production navigation and controls without a game, credentials or API requests. */
@RunWith(AndroidJUnit4::class)
class TaskNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private fun task() = AiTaskSnapshot("a".repeat(32), AiConfig(includeMissing = false, includeReview = true),
        "zero", "都市零协会", "b".repeat(32), "AI 汉化", 2026111401,
        progress = AiProgress(8, 10, message = "已完成 8/10 条"),
        plan = AiTaskPlan(mapOf("jp/JP_Test.json" to (0..9).map { "$it" }.toSet()), mapOf("jp/JP_Test.json" to "hash"), 10))
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val width = context.resources.displayMetrics.widthPixels
        val file = java.io.File(context.getExternalFilesDir(null), "$name-$width.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable private fun Screen(state: LauncherUiState, actions: TranslationActions = TranslationActions(), migration: () -> Unit = {}) {
        LimbusZhCNTheme {
            LauncherScreen(state, ProjectTrackingState(), AppUpdateState(108), resumed = false,
                onCheckAppUpdate = {}, onDownloadAppUpdate = {}, onInstallAppUpdate = {}, onCancelAppUpdate = {}, onOpenAppRelease = {},
                onLaunch = { error("Viewing a task must not launch the game") }, onRefresh = {}, onUpdate = {}, onCheckGameUpdate = {},
                onSelectGameSource = {}, onOpenGamePage = {}, onOpenInstallSource = {}, onImportGame = {}, onInstallGame = {},
                onCancelGameInstall = {}, onMigration = migration, onExport = {}, onSettings = {}, onGoogleSettings = {},
                onRefreshProject = {}, onSelectProjectSource = {}, onOpenLink = {}, translationActions = actions)
        }
    }

    @Test fun runningTranslationReopensAfterLeavingAndPauseResumeCancelRemainReachable() {
        val task = task()
        var state by mutableStateOf(LauncherUiState(busy = true, taskPage = "ai-translation", canResumeGame = true,
            ai = AiUiState(hasKey = true, running = true, task = task, progress = task.progress)))
        var resumed = 0; var cancelled = 0
        val actions = TranslationActions(pauseAi = {
            state = state.copy(busy = false, ai = state.ai.copy(running = false, task = task.copy(phase = AiTaskPhase.Paused)))
        }, resumeAi = { resumed++; state = state.copy(busy = true, ai = state.ai.copy(running = true)) },
            cancelAi = { cancelled++; state = state.copy(busy = false, ai = state.ai.copy(running = false, task = null)) })
        compose.setContent { Screen(state, actions) }
        compose.onNodeWithText("正在处理").assertIsEnabled().performClick()
        compose.onNodeWithText("已完成 8 / 10 条").assertIsDisplayed()
        capture("task-running")
        compose.onNodeWithContentDescription("关闭面板").performClick()
        compose.onNodeWithText("正在处理").performClick()
        compose.onNodeWithText("暂停").performClick()
        compose.onNodeWithText("继续翻译").assertIsEnabled()
        capture("task-paused")
        compose.onNodeWithContentDescription("关闭面板").performClick()
        compose.onNodeWithText("翻译已暂停 · 查看任务").performClick()
        compose.onNodeWithText("继续翻译").performClick()
        compose.onNodeWithText("取消任务").performClick()
        compose.runOnIdle { assertEquals(0, cancelled) }
        compose.onNodeWithText("取消并删除").performClick()
        compose.runOnIdle { assertEquals(1, resumed); assertEquals(1, cancelled) }
    }

    @Test fun repairAndMigrationOpenTheirOwnDetailsWhileWorkIsLocked() {
        var state by mutableStateOf(LauncherUiState(busy = true, taskPage = "repair",
            repair = RepairState(RepairPhase.Running, 1, "正在校验中文字体")))
        var openedMigration = 0
        compose.setContent { Screen(state, migration = { openedMigration++ }) }
        compose.onNodeWithText("正在处理").performClick()
        compose.onNodeWithText("修复汉化与启动文件").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭面板").performClick()
        compose.runOnIdle { state = state.copy(taskPage = "migration") }
        compose.onNodeWithText("正在处理").performClick()
        compose.runOnIdle { assertEquals(1, openedMigration) }
    }

    @Test fun initialCoverageCheckStaysInlineAndStaleResultsRemainReadable() {
        var state by mutableStateOf(LauncherUiState())
        var scans = 0
        val actions = TranslationActions(inspectCoverage = { scans++; state = state.copy(coverage = CoverageState(loading = true)) })
        compose.setContent { Screen(state, actions) }
        compose.onNodeWithContentDescription("设置与工具").performClick()
        compose.onNodeWithText("汉化源与更新").performScrollTo().performClick()
        compose.onNodeWithText("检查").performClick()
        compose.onNodeWithText("检查中").assertExists()
        compose.onNodeWithText("导入汉化包").assertExists()
        capture("coverage-inline")
        compose.runOnIdle {
            assertEquals(1, scans)
            val report = CoverageReport("都市零协会 · AI 补全", 17L, 2460, 183682, 183664, 18, 83, 18, 0,
                listOf(CoverageFile("jp/StoryData/JP_Story.json", "剧情与对话", 18, 70,
                    listOf(CoverageSample("待翻译的原文", false, reason = "没有可用译文"),
                        CoverageSample("待核对的原文", true, "包含原文的现有译文", "译文中仍含原文"))),
                    CoverageFile("jp/JP_Skills.json", "战斗与技能", 0, 13, emptyList())), reviewEligible = 83)
            state = state.copy(coverage = CoverageState(report = report, fingerprint = "current"))
        }
        compose.onNodeWithText("查看结果").performClick()
        compose.onNodeWithText("检查结果").assertIsDisplayed()
        capture("coverage-result")
        compose.onNodeWithText("剧情与对话").performScrollTo().performClick()
        compose.onNodeWithText("没有可用译文").performScrollTo().assertIsDisplayed()
        capture("coverage-expanded")
        compose.runOnIdle { state = state.copy(coverage = state.coverage.copy(stale = true)) }
        compose.onNodeWithText("上次检查结果").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("设置 AI 翻译").assertIsNotEnabled()
        capture("coverage-stale")
    }

    @Test fun newTranslationRequiresCoverageAndCannotUseAStaleConfirmation() {
        var state by mutableStateOf(LauncherUiState(ai = AiUiState(hasKey = true)))
        var scans = 0; var generations = 0
        val actions = TranslationActions(inspectCoverage = { scans++; state = state.copy(coverage = CoverageState(loading = true)) },
            generateAi = { _, _ -> generations++ })
        compose.setContent { Screen(state, actions) }
        compose.onNodeWithContentDescription("设置与工具").performClick()
        compose.onNodeWithText("汉化源与更新").performScrollTo().performClick()
        compose.onNodeWithText("AI 翻译").performScrollTo().performClick()
        compose.onNodeWithText("生成并使用").assertDoesNotExist()
        compose.onNodeWithText("先检查汉化覆盖").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, scans)
            state = state.copy(coverage = CoverageState(report = CoverageReport("source", 1, 1, 10, 10, 0, 2, 0, 0,
                emptyList(), reviewEligible = 2), fingerprint = "current"))
        }
        compose.onNodeWithText("所选范围已无需补译").assertIsNotEnabled()
        compose.onNodeWithText("待核对").performScrollTo().performClick()
        compose.onNodeWithText("生成并使用").performScrollTo().performClick()
        compose.onNodeWithText("开始翻译").assertIsEnabled()
        compose.runOnIdle { state = state.copy(coverage = state.coverage.copy(stale = true)) }
        compose.onNodeWithText("开始翻译").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, generations) }
    }
}
