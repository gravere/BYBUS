package com.example.limbuszhcn

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.limbuszhcn.translation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiUpdateDialogTest {
    @get:Rule val compose = createComposeRule()
    private val offer = AiUpstreamOffer("ai-test", "revision", 2026111701, 2026111401, "{}", AiConfig(limit = 5), true)

    @Test fun defaultReuseAndKeepingCurrentNeverSelectPaidSupplement() {
        val selected = mutableListOf<AiUpdateChoice?>()
        compose.setContent { MaterialTheme { AiUpstreamDialog(offer, true, true, selected::add, {}) } }
        val update = compose.onNodeWithText("更新后进入游戏").fetchSemanticsNode().boundsInRoot
        val keep = compose.onNodeWithText("继续当前汉化进入").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText("取消").fetchSemanticsNode().boundsInRoot
        assertTrue(update.bottom < keep.top); assertTrue(keep.bottom < cancel.top)
        compose.onNodeWithText("更新后进入游戏").performClick()
        compose.onNodeWithText("继续当前汉化进入").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(listOf(AiUpdateChoice.Reuse, AiUpdateChoice.Current, null), selected) }
    }

    @Test fun supplementRequiresSavedKeyAndExplicitSelection() {
        val selected = mutableListOf<AiUpdateChoice?>()
        var configured = false
        compose.setContent { MaterialTheme { AiUpstreamDialog(offer, false, true, selected::add, { configured = true }) } }
        compose.onNodeWithText("更新后继续补译").assertIsNotEnabled()
        compose.onNodeWithText("设置").assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle { assertTrue(configured); assertTrue(selected.isEmpty()) }
        compose.onNodeWithText("只更新零协").performScrollTo().performClick()
        compose.onNodeWithText("更新后进入游戏").performClick()
        compose.runOnIdle { assertEquals(listOf(AiUpdateChoice.Official), selected) }
    }

    @Test fun explicitSupplementShowsLimitAndUsesDedicatedConfirmation() {
        var selected: AiUpdateChoice? = null
        compose.setContent { MaterialTheme { AiUpstreamDialog(offer, true, true, { selected = it }, {}) } }
        compose.onNodeWithText("更新后继续补译").performScrollTo().performClick()
        compose.onNodeWithText("保留可用 AI 译文，再补译最多 5 条。").assertExists()
        compose.onNodeWithText("消耗 API 额度").assertExists()
        compose.onNodeWithText("补译后进入游戏").performClick()
        compose.runOnIdle { assertEquals(AiUpdateChoice.Supplement, selected) }
    }
}
