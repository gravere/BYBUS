package com.example.limbuszhcn

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.limbuszhcn.migration.*
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootImportUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun rootEntryCanBeReachedAndOpensOnlyOnAnExplicitClick() {
        var clicked = false
        ui.setContent { LimbusZhCNTheme {
            MigrationScreen(MigrationState(), {}, {}, {}, {}, {}, {}, onRootImport = { clicked = true })
        } }
        ui.onNodeWithText("从原版导入（Root）").performScrollTo().assertIsDisplayed().performClick()
        ui.runOnIdle { assertTrue(clicked) }
    }
    @Test fun rootCopyHasAnAccessibleCancelAction() {
        var cancelled = false
        ui.setContent { LimbusZhCNTheme {
            MigrationScreen(MigrationState(busy = true, operation = MigrationOperation.RootResources,
                copied = 50, total = 100, message = "正在校验资源"), {}, {}, {}, {}, {}, {},
                onCancelRootImport = { cancelled = true })
        } }
        ui.onNodeWithText("取消导入").performScrollTo().performClick()
        ui.runOnIdle { assertTrue(cancelled) }
    }
}
