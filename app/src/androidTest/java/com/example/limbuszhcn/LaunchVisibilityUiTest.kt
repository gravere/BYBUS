package com.example.limbuszhcn

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchVisibilityUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lastStageKeepsMovingWhilePausedButVisibleAndStopsOnlyWhenHidden() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            val visible = rememberLifecycleAtLeast(owner.lifecycle, Lifecycle.State.STARTED).value
            LimbusZhCNTheme {
                Box(Modifier.width(360.dp).testTag("launch-ring")) {
                    LaunchButton(LaunchAction.Starting, enabled = false, step = 2, animated = visible,
                        progressText = "正在启动 · 8秒", onClick = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        val resumed = pixels()
        compose.mainClock.advanceTimeBy(320)
        assertFalse("resumed ring moves", resumed.contentEquals(pixels()))

        compose.runOnUiThread { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        val paused = pixels()
        compose.mainClock.advanceTimeBy(320)
        assertFalse("visible paused ring must keep moving during game handoff", paused.contentEquals(pixels()))

        compose.runOnUiThread { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        compose.mainClock.advanceTimeBy(600)
        val hidden = pixels()
        compose.mainClock.advanceTimeBy(320)
        assertArrayEquals("hidden home no longer animates", hidden, pixels())
    }

    private fun pixels(): IntArray {
        val bitmap = compose.onNodeWithTag("launch-ring").captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }
}
