package com.example.limbuszhcn

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.limbuszhcn.translation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run seed, force-stop the owned emulator app, then run recovered. No API configuration is created. */
@RunWith(AndroidJUnit4::class)
class AiTaskRecoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun seed() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val store = AiTaskStore(compose.activity.filesDir.toPath().resolve("translation-cache"))
        check(!store.exists())
        val task = store.create(AiConfig(limit = 10, includeMissing = false, includeReview = true),
            TranslationSources.OFFICIAL, null, null, identityOverride = "c".repeat(32))
        store.write(task.copy(progress = AiProgress(8, 10, message = "已完成 8/10 条"),
            plan = AiTaskPlan(mapOf("jp/JP_Test.json" to (0..9).map { it.toString().padStart(64, '0') }.toSet()),
                mapOf("jp/JP_Test.json" to "d".repeat(64)), 10)))
        assertEquals(AiTaskPhase.Running, store.read()!!.phase)
    }
    @Test fun recovered() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val cache = compose.activity.filesDir.toPath().resolve("translation-cache")
        val store = AiTaskStore(cache)
        try {
            compose.waitUntil(30_000) { store.read()?.phase == AiTaskPhase.Paused }
            compose.waitUntil(30_000) { compose.onAllNodesWithText("翻译已暂停 · 查看任务").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("翻译已暂停 · 查看任务").performClick()
            compose.onNodeWithText("已完成 8 / 10 条").assertIsDisplayed()
            compose.onNodeWithText("继续翻译").assertExists()
            compose.onNodeWithText("取消任务").performClick()
            compose.onNodeWithText("取消并删除").performClick()
            compose.waitUntil(15_000) { !store.exists() }
        } finally {
            store.discard()
            val fixture = cache.resolve("ai-jobs/${"c".repeat(32)}")
            if (java.nio.file.Files.exists(fixture)) com.example.limbuszhcn.storage.AtomicFiles.deleteOwnedTree(cache, fixture)
        }
    }
}
