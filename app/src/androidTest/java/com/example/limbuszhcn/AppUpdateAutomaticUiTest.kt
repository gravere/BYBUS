package com.example.limbuszhcn

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.limbuszhcn.selfupdate.AppUpdateState
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppUpdateAutomaticUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wholeRowSwitchIsSingleAccessibleControlAndManualCheckRemainsAvailable() {
        var state by mutableStateOf(AppUpdateState(155))
        var changes = 0
        var checks = 0
        compose.setContent { LimbusZhCNTheme { Surface { Column(Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState()).padding(24.dp)) {
            AppUpdatesPane(state, { checks++ }, {}, { state = state.copy(automaticUpdates = it); changes++ })
        } } } }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        assertEquals(1, changes)
        compose.onNodeWithContentDescription("检查应用更新").performClick()
        assertEquals(1, checks)
        compose.onNodeWithText("空闲时通过非计费网络下载，安装前仍需确认").assertIsDisplayed()
        if (android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.getExternalFilesDir(null), "update159.png")
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        compose.onNode(isToggleable()).performClick().assertIsOff()
        assertEquals(2, changes)
    }
}
