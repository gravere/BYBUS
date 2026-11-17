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
        compose.setContent { MaterialTheme { AiUpstreamDialog(offer, false, true, selected::add, {}) } }
        compose.onNodeWithText("复用后补译一批").assertIsNotEnabled()
        compose.onNodeWithText("仅使用零协译文").performClick()
        compose.onNodeWithText("更新后进入游戏").performClick()
        compose.runOnIdle { assertEquals(listOf(AiUpdateChoice.Official), selected) }
    }

    @Test fun explicitSupplementShowsLimitAndUsesDedicatedConfirmation() {
        var selected: AiUpdateChoice? = null
        compose.setContent { MaterialTheme { AiUpstreamDialog(offer, true, true, { selected = it }, {}) } }
        compose.onNodeWithText("复用后补译一批").performClick()
        compose.onNodeWithText("先复用已有译文，再补译最多 5 条未覆盖内容。使用已保存的 API 设置，新增请求会消耗额度。").assertExists()
        compose.onNodeWithText("补译后进入游戏").performClick()
        compose.runOnIdle { assertEquals(AiUpdateChoice.Supplement, selected) }
    }
}
