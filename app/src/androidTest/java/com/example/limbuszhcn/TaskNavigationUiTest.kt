package com.example.limbuszhcn

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.selfupdate.AppUpdateState
import com.example.limbuszhcn.tracking.ProjectTrackingState
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises production navigation and controls without a game, credentials or API requests. */
@RunWith(AndroidJUnit4::class)
class TaskNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun savedKeyCanBeChosenAndAFailedSaveKeepsTheDraft() {
        val first = AiKeyChoice("one", "个人账号", "https://example.test/v1", "old-model")
        val other = AiKeyChoice("two", "备用账号", "https://elsewhere.test/v1", "other-model")
        var ui by mutableStateOf(LauncherUiState(ai = AiUiState(config = AiConfig(first.endpoint, first.model),
            hasKey = true, keys = listOf(first, other), activeKeyId = first.id)))
        var selected = ""
        var saved = ""
        val actions = TranslationActions(selectKey = { id ->
            selected = id
            ui = ui.copy(ai = ui.ai.copy(config = AiConfig(other.endpoint, other.model), activeKeyId = id))
        }, saveKey = { _, _, value, _ ->
            saved = value
            ui = ui.copy(ai = ui.ai.copy(message = "保存未完成", failed = true))
        })
        compose.setContent { Screen(ui, actions) }
        compose.onNodeWithContentDescription("设置与工具").performClick()
        compose.onNodeWithText("汉化源与更新").performScrollTo().performClick()
        compose.onNodeWithText("AI 翻译").performScrollTo().performClick()
        compose.onNodeWithText("切换密钥（2）").performScrollTo().performClick()
        compose.onNodeWithText("备用账号").performClick()
        compose.runOnIdle { assertEquals(other.id, selected) }
        compose.onNodeWithContentDescription("API Key").performScrollTo().performTextInput("fixture-ui")
        compose.onNodeWithText("保存密钥").performScrollTo().performClick()
        compose.onNodeWithText("保存", substring = false).performClick()
        compose.runOnIdle { assertEquals("fixture-ui", saved) }
        compose.onNodeWithText("保存未完成").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("显示").performScrollTo().performClick()
        compose.onNodeWithContentDescription("API Key").assertTextContains("fixture-ui")
    }
    private fun task() = AiTaskSnapshot("a".repeat(32), AiConfig(includeMissing = false, includeReview = true),
        "zero", "都市零协会", "b".repeat(32), "AI 汉化", 2026111401,
        progress = AiProgress(8, 10, message = "已完成 8/10 条"),
        plan = AiTaskPlan(mapOf("jp/JP_Test.json" to (0..9).map { "$it" }.toSet()), mapOf("jp/JP_Test.json" to "hash"), 10))
    private fun capture(name: String, popup: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val width = context.resources.displayMetrics.widthPixels
        val file = java.io.File(context.getExternalFilesDir(null), "$name-$width.png")
        val node = if (popup) compose.onNode(isPopup()) else compose.onRoot()
        file.outputStream().use { node.captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
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
        compose.waitUntil(5500) { compose.onAllNodesWithText("样例 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("样例 1").performScrollTo().performClick()
        compose.onNodeWithText("没有可用译文").performScrollTo().assertIsDisplayed()
        capture("coverage-expanded")
        compose.runOnIdle { state = state.copy(coverage = state.coverage.copy(stale = true)) }
        compose.onNodeWithText("上次检查结果").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("设置 AI 翻译").assertIsNotEnabled()
        capture("coverage-stale")
    }

    @Test fun savedCoverageAndRefreshHaveSeparateTouchTargetsEvenWhenTheReportIsComplete() {
        val previous = CoverageReport("都市零协会", 1789000000000L, 12, 100, 99, 1, 0, 1, 0, emptyList())
        var state by mutableStateOf(LauncherUiState(coverage = CoverageState(report = previous, stale = true)))
        var scans = 0
        val actions = TranslationActions(inspectCoverage = {
            scans++; state = state.copy(coverage = state.coverage.copy(loading = true))
        })
        compose.setContent { Screen(state, actions) }
        compose.onNodeWithContentDescription("设置与工具").performClick()
        compose.onNodeWithText("汉化源与更新").performScrollTo().performClick()
        val history = compose.onNodeWithText("上次结果")
        history.performScrollTo().assertIsDisplayed()
        capture("coverage-summary-history")
        val refresh = compose.onNodeWithContentDescription("重新检查")
        history.assertHeightIsAtLeast(48.dp)
        refresh.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertTrue(history.getUnclippedBoundsInRoot().right <= refresh.getUnclippedBoundsInRoot().left)
        compose.runOnIdle { state = state.copy(coverage = state.coverage.copy(report = previous.copy(covered = 100, missing = 0, aiEligible = 0))) }
        history.performClick()
        compose.onNodeWithText("上次检查结果").assertIsDisplayed()
        capture("coverage-history-detail")
        compose.runOnIdle { assertEquals(0, scans) }
        compose.onNodeWithContentDescription("返回上一页").performClick()
        refresh.performScrollTo().performClick()
        refresh.assertIsNotEnabled()
        history.assertIsEnabled().performClick()
        compose.onNodeWithText("上次检查结果").assertExists()
        compose.runOnIdle { assertEquals(1, scans) }
        compose.runOnIdle { state = state.copy(coverage = CoverageState(report = previous.copy(files = 0, total = 0,
            covered = 0, missing = 0, aiEligible = 0, unreadable = 3), fingerprint = "current")) }
        compose.onNodeWithText("本次没有读到游戏文本，暂时无法判断汉化覆盖情况。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("返回上一页").performClick()
        compose.onNodeWithText("3 个文件未能检查").performScrollTo().assertIsDisplayed()
        val imported = TranslationSource("local-1111111111111111", "本地汉化", updatedAtMillis = 1789000000000L)
        val generated = TranslationSource("ai-" + "2".repeat(32), "我的补译", "ai", updatedAtMillis = 1789100000000L)
        compose.runOnIdle { state = state.copy(sources = SourceState(listOf(TranslationSources.OFFICIAL, imported, generated))) }
        compose.onNodeWithText(state.packageTimeLabel(imported)!!).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(state.packageTimeLabel(generated)!!).performScrollTo().assertIsDisplayed()
        capture("source-times")
    }

    @Test fun coverageBrowserSeparatesStatusesAndKeepsEntryNumbersThroughFiltersAndPages() {
        val script = "剧情与对话"; val battle = "战斗与技能"
        val rows = (1..10).map { n ->
            val kind = if (n == 7 || n == 9) CoverageKind.Review else CoverageKind.Missing
            CoverageEntry(n, kind, if (n <= 7) script else battle, if (n <= 7) "jp/StoryData/JP_A.json" else "jp/JP_Skills.json",
                "台詞 $n", if (kind == CoverageKind.Review) "仍含原文 $n です" else null, "R$n", "/dataList/${n-1}/content")
        }
        val report = CoverageReport("本地汉化", 1800000000000L, 2, 12, 4, 8, 2, 8, 0,
            listOf(CoverageFile("a",script,6,1,emptyList()), CoverageFile("b",battle,2,1,emptyList())), reviewEligible = 2,
            entryIndex = CoverageIndexRef("a".repeat(64), java.util.UUID.randomUUID().toString()))
        var scans = 0
        val reviewStarted = CountDownLatch(1)
        val allowReview = CountDownLatch(1)
        val actions = TranslationActions(inspectCoverage = { scans++ }, coverageEntries = { _, filter, page, _ ->
            if (filter.kind == CoverageKind.Review && reviewStarted.count != 0L) {
                reviewStarted.countDown()
                check(allowReview.await(8, TimeUnit.SECONDS))
            }
            rows.filteredPage(filter, page)
        })
        compose.setContent { Screen(LauncherUiState(coverage = CoverageState(report = report, fingerprint = "saved")), actions) }
        compose.onNodeWithContentDescription("设置与工具").performClick()
        compose.onNodeWithText("汉化源与更新").performScrollTo().performClick()
        compose.onNodeWithText("查看结果").performClick()
        fun waitForText(text: String) {
            compose.waitUntil(5500) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }
        waitForText("10 条")
        compose.onNodeWithContentDescription("搜索条目").assertDoesNotExist()
        val all = compose.onNodeWithContentDescription("查看全部状态")
        val missing = compose.onNodeWithContentDescription("只看未覆盖")
        val review = compose.onNodeWithContentDescription("只看待核对")
        all.performScrollTo().assertIsSelected()
        listOf(all, missing, review).forEach { it.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp) }
        capture("coverage-controls")
        val allBounds = all.getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        try {
            missing.performClick()
            compose.mainClock.advanceTimeBy(80)
            assertEquals(allBounds, all.getUnclippedBoundsInRoot())
            all.performClick()
            compose.mainClock.advanceTimeBy(240)
            all.assertIsSelected()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithText("下一页").performScrollTo().performClick()
        waitForText("#7")
        review.performScrollTo().performClick()
        try {
            compose.waitUntil(5000) { reviewStarted.count == 0L }
            compose.onNodeWithText("#7").assertIsNotEnabled()
        } finally {
            allowReview.countDown()
        }
        waitForText("2 条")
        review.assertIsSelected()
        compose.onNodeWithText("#1").assertDoesNotExist()
        compose.onNodeWithText("#7").performScrollTo().performClick()
        compose.onNodeWithText("现有译文").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("jp/StoryData/JP_A.json\n/dataList/6/content").assertExists()
        capture("coverage-review-rows")
        compose.onNodeWithContentDescription("筛选内容类别").performScrollTo().performClick()
        compose.onNode(hasText("全部类别") and hasAnyAncestor(isPopup())).assertIsSelected()
        capture("coverage-categories", popup = true)
        compose.onNode(hasText(battle) and hasAnyAncestor(isPopup())).performClick()
        waitForText("1 条")
        compose.onNodeWithText("#9").assertExists()
        compose.onNodeWithText("#7").assertDoesNotExist()
        all.performScrollTo().performClick()
        compose.onNodeWithContentDescription("打开条目搜索").performScrollTo().performClick()
        compose.onNodeWithContentDescription("搜索条目").performScrollTo().performTextInput("#9")
        waitForText("1 条")
        compose.onNode(hasText("#9") and hasText("游戏 ID R9")).assertExists()
        missing.performScrollTo().performClick()
        waitForText("没有符合条件的条目")
        compose.onNodeWithContentDescription("清除搜索").performScrollTo().performClick()
        waitForText("2 条")
        compose.onNodeWithContentDescription("搜索条目").performScrollTo().performTextInput("#9")
        waitForText("没有符合条件的条目")
        compose.onNodeWithContentDescription("关闭条目搜索").performScrollTo().performClick()
        waitForText("2 条")
        compose.onNodeWithContentDescription("搜索条目").assertDoesNotExist()
        missing.assertIsSelected()
        compose.runOnIdle { assertEquals(0, scans) }
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
