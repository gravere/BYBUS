package com.example.limbuszhcn

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
class LocalSourceDialogsTest {
    @get:Rule val compose = createComposeRule()
    private fun shot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val folder = File(context.getExternalFilesDir(null), "source144").apply { mkdirs() }
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun importOffersChoicesAndAiOnlyDispatchesAnExplicitSelection() {
        var choice: ImportFillChoice? = null
        val report = CoverageReport("自定义汉化", 1, 2, 100, 70, 30, 2, 30, 0, emptyList())
        compose.setContent { LimbusZhCNTheme { ImportCoverageDialog(ImportCoverageReview("id", "hash", "自定义汉化", report)) { choice = it } } }
        compose.onNodeWithText("未覆盖 30 条 · 待核对 2 条").assertIsDisplayed()
        compose.onNodeWithText("零协补齐").assertIsDisplayed()
        compose.onNodeWithText("保持原样").assertIsDisplayed()
        assertNull(choice)
        shot("import")
        compose.onNodeWithText("选择 AI 补译范围").performClick()
        assertEquals(ImportFillChoice.Ai, choice)
    }
    @Test fun missingGameOriginalsDoNotAppearAsCompleteCoverage() {
        var choice: ImportFillChoice? = null
        compose.setContent { LimbusZhCNTheme { ImportCoverageDialog(ImportCoverageReview("id", "hash", "自定义汉化",
            issue = "游戏文本尚未下载，暂时无法判断覆盖。")) { choice = it } } }
        compose.onNodeWithText("游戏文本尚未下载，暂时无法判断覆盖。").assertIsDisplayed()
        compose.onNodeWithText("零协补齐").assertDoesNotExist()
        compose.onNodeWithText("保持原样").performClick()
        assertEquals(ImportFillChoice.Keep, choice)
    }
    @Test fun updateLinkCanBeEditedButRemovingRequiresConfirmation() {
        var saved: Pair<String, Boolean>? = null
        val source = TranslationSource("id", "自定义汉化", updateLink = SourceUpdateLink("https://example.com/latest.zip"))
        compose.setContent { LimbusZhCNTheme { SourceUpdateLinkDialog(source, true, {}) { url, automatic -> saved = url to automatic } } }
        compose.onNodeWithText("启动时自动更新").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsDisplayed()
        shot("link")
        compose.onNodeWithText("移除链接").performClick()
        assertNull(saved)
        compose.onNodeWithText("移除更新链接？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertNull(saved)
        compose.onNodeWithText("保存").performClick()
        assertEquals(source.updateLink!!.url to true, saved)
    }
}
