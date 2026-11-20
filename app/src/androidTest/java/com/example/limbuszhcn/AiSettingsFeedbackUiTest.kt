package com.example.limbuszhcn

import androidx.compose.runtime.*
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
class AiSettingsFeedbackUiTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "ai-settings137").apply { mkdirs() }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun pausedTaskDoesNotHideSuccessOrFailureAndCollapsingSettingsKeepsTheResultVisible() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val task = AiTaskSnapshot("fixture", AiConfig(), "zero", "都市零协会", "fixture-output", "汉化包", null,
            progress = AiProgress(completed = 1, limit = 49))
        var state by mutableStateOf(LauncherUiState(ai = AiUiState(hasKey = true, task = task, progress = task.progress,
            failed = true, message = "翻译服务响应超时，进度已保存")))
        val actions = TranslationActions(saveAi = { config, key, test ->
            check(test)
            state = state.beginAiSettings("正在测试翻译").copy(ai = state.ai.copy(
                testResult = AiTestResult(AiTestResult.fingerprint(config, key), config.model, "连接中…", 1200)))
                .finishAiSettings(Result.success("测试通过"))
        })
        compose.setContent { LimbusZhCNTheme {
            val c = LocalConfiguration.current
            LauncherDetailSurface(LauncherLayout.calculate(c.screenWidthDp.toFloat(), c.screenHeightDp.toFloat(), LocalDensity.current.fontScale), "ai-translation", {}) {
                AiTranslationPane(state, actions)
            }
        } }
        compose.onNodeWithText("查看翻译设置").performScrollTo().performClick()
        compose.onNodeWithText("测试翻译").performScrollTo().performClick()
        compose.onNodeWithText("测试通过").performScrollTo().assertIsDisplayed()
        capture("expanded-success")
        compose.onNodeWithText("收起翻译设置").performScrollTo().performClick()
        compose.onNodeWithText("测试通过").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("译文：连接中…").assertIsDisplayed()
        capture("collapsed-success")
        compose.runOnIdle {
            assertTrue(state.ai.failed)
            assertEquals(1, state.ai.progress.completed)
            assertEquals("翻译服务响应超时，进度已保存", state.ai.message)
            state = state.beginAiSettings("正在测试翻译").finishAiSettings(Result.failure(AiHttpException(524)))
        }
        compose.onNodeWithText("翻译服务响应超时（HTTP 524），本批未完成。请稍后继续，已完成的译文会保留。").performScrollTo().assertIsDisplayed()
        capture("visible-failure")
    }
}
