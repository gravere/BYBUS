package com.example.limbuszhcn

import android.content.Context
import android.content.ContextWrapper
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
class AiTaskConnectionUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pausedCardSelectsAnotherSavedApiWithoutStartingAndContinuesOnlyOnTap() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val first = AiConfig(endpoint = "https://first.example/v1", model = "first-model")
        val second = first.copy(endpoint = "https://second.example/v1", model = "second-model")
        val choices = listOf(AiKeyChoice("first", "原 API", first.endpoint, first.model), AiKeyChoice("second", "备用 API", second.endpoint, second.model))
        val task = AiTaskSnapshot("fixture", first, "zero", "都市零协会", "fixture", "汉化包", null,
            phase = AiTaskPhase.Paused, progress = AiProgress(completed = 9, limit = 49),
            plan = AiTaskPlan(mapOf("fixture" to (0..48).map { "$it" }.toSet()), emptyMap(), 49))
        var state by mutableStateOf(LauncherUiState(ai = AiUiState(config = first, hasKey = true,
            task = task, progress = task.progress, keys = choices, activeKeyId = "first")))
        var resumes = 0
        val actions = TranslationActions(selectKey = { id ->
            assertEquals("second", id)
            state = state.copy(ai = state.ai.copy(config = second, activeKeyId = id))
        }, resumeAi = {
            assertEquals(second, state.ai.config)
            resumes++
            state = state.copy(busy = true, ai = state.ai.copy(running = true))
        })
        compose.setContent { LimbusZhCNTheme {
            val c = LocalConfiguration.current
            LauncherDetailSurface(LauncherLayout.calculate(c.screenWidthDp.toFloat(), c.screenHeightDp.toFloat(), LocalDensity.current.fontScale), "ai-translation", {}) {
                AiTranslationPane(state, actions)
            }
        } }
        compose.onNodeWithText("更换 API").performScrollTo().performClick()
        compose.onNodeWithText("备用 API").performClick()
        compose.onNodeWithText("备用 API").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("second-model").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, resumes); assertEquals(task, state.ai.task); assertEquals(9, state.ai.progress.completed) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "ai-connection139").apply { mkdirs() }
        File(dir, "paused-api.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("继续").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, resumes) }
        compose.onNodeWithText("更换 API").assertDoesNotExist()
    }

    @Test fun savedConnectionPairsSelectedEndpointModelAndItsOwnEncryptedKey() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("connection139-fixture-$name", mode)
        }
        val prefs = context.getSharedPreferences("ai-translation-private", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val store = AiSettingsStore(context)
            store.saveKey("https://first.example/v1", "first-model", "fixture-first-key", "原 API")
            val firstId = store.choices().first!!
            store.saveKey("https://second.example/v1", "second-model", "fixture-second-key", "备用 API")
            val fresh = AiSettingsStore(context)
            assertEquals("https://second.example/v1", fresh.connection().config.endpoint)
            assertEquals("second-model", fresh.connection().config.model)
            assertEquals("fixture-second-key", fresh.connection().key)
            fresh.selectKey(firstId)
            assertEquals("fixture-first-key", fresh.connection().key)
            fresh.save(fresh.config().copy(endpoint = "https://third.example/v1"), "")
            assertThrows(IllegalStateException::class.java) { fresh.connection() }
            assertFalse(prefs.getString("keys", "")!!.contains("fixture-first-key"))
            assertFalse(prefs.getString("keys", "")!!.contains("fixture-second-key"))
        } finally { prefs.edit().clear().commit() }
    }
}
