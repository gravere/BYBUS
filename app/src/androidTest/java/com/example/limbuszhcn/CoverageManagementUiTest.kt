package com.example.limbuszhcn

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.limbuszhcn.container.ContainerStatus
import com.example.limbuszhcn.gamefs.GameTextLanguage
import com.example.limbuszhcn.translation.CoverageReport
import com.example.limbuszhcn.translation.CoverageState
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverageManagementUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inspectionAvailabilityUpdatesWhenReturningFromGame() {
        val state = mutableStateOf(LauncherUiState())
        var checks = 0
        compose.setContent { LimbusZhCNTheme { CoverageSummary(state.value, {}, { checks++ }) } }
        compose.onNodeWithText("检查").assertIsNotEnabled()
        compose.runOnIdle { state.value = LauncherUiState(canResumeGame = true,
            container = ContainerStatus(true, true, "ready"), coverage = CoverageState(language = GameTextLanguage.English)) }
        compose.onNodeWithText("检查").assertIsEnabled().performClick()
        assertEquals(1, checks)
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithText("检查").assertIsNotEnabled()
    }

    @Test fun restoredCoverageKeepsItsCheckTimeAndOpensItsExistingDetails() {
        val report = CoverageReport("本地汉化", 1795238000000L, 2, 100, 98, 2, 0, 2, 0, emptyList())
        var opens = 0
        val state = LauncherUiState(container = ContainerStatus(true, true, "ready"),
            coverage = CoverageState(report = report, fingerprint = "saved", language = GameTextLanguage.Japanese))
        compose.setContent { LimbusZhCNTheme { CoverageSummary(state, { opens++ }, {}) } }
        compose.onNodeWithText("检查于 ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2 条未覆盖 · 0 条待核对").performClick()
        assertEquals(1, opens)
        compose.onNodeWithContentDescription("检查内容变化").assertIsEnabled()
    }
}
