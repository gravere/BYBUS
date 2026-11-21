package com.example.limbuszhcn

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
class BackgroundSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun settingsOnlyUseAppDetailsAndBatteryAndHandleUnavailablePages() {
        val calls = mutableListOf<Intent>()
        var failAll = false
        val wrapper = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                calls += intent
                assertNull(intent.component)
                assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                if (failAll || intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) throw ActivityNotFoundException()
            }
        }
        assertTrue(AiBackgroundAccess.openSettings(wrapper))
        assertEquals(listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_BATTERY_SAVER_SETTINGS), calls.map { it.action })
        assertEquals("package:${context.packageName}", calls.first().data.toString())
        calls.clear(); failAll = true
        assertFalse(AiBackgroundAccess.openSettings(wrapper))
        assertEquals(2, calls.size)
    }

    @Test fun systemPauseShowsSettingsEvenWhenDozeIsExemptAndToolsRemainUsableWhileBusy() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        fun exemption(allow: Boolean) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("dumpsys deviceidle whitelist ${if (allow) "+" else "-"}${context.packageName}"))
                .bufferedReader().use { it.readText() }
        }
        val previous = AiBackgroundAccess.allowed(context)
        exemption(true)
        assertTrue(AiBackgroundAccess.allowed(context))
        try {
        val task = AiTaskSnapshot("fixture", AiConfig(), "zero", "来源", "output", "汉化", null,
            phase = AiTaskPhase.Paused, error = "系统暂停了后台翻译，已保存的进度会保留", backgroundStopReason = 17,
            plan = AiTaskPlan(mapOf("x" to setOf("id")), emptyMap(), 1))
        val tools = mutableStateOf(false)
        var opened = false
        compose.setContent { LimbusZhCNTheme {
            if (!tools.value) AiTaskCard(LauncherUiState(ai = AiUiState(task = task)), {}, {}, {})
            else Column(Modifier.verticalScroll(rememberScrollState())) { LauncherDetails("tools", LauncherUiState(busy = true), onGameStore = {}, onAppUpdate = {},
                onTranslation = {}, onExport = {}, onSettings = { opened = true }, onMigration = {}, onRepair = {},
                onClearData = {}, onGoogleSettings = {}, onRefresh = {}, onAbout = {}) }
        } }
        compose.onNodeWithText(task.error!!).assertIsDisplayed()
        compose.onNodeWithText("后台运行设置").assertIsEnabled().performClick()
        compose.onNodeWithText("让翻译在切换应用或锁屏后继续，无需开启自启动。").assertIsDisplayed()
        compose.onNodeWithText("应用设置").assertIsDisplayed()
        compose.onNodeWithText("电池优化：已豁免").assertIsDisplayed()
        val folder = File(context.getExternalFilesDir(null), "background143").apply { mkdirs() }
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, "settings.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { tools.value = true }
        compose.onNodeWithText("后台运行").performScrollTo().assertIsEnabled().performClick()
        assertTrue(opened)
        } finally { if (!previous) exemption(false) }
    }
}
