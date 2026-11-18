package com.example.limbuszhcn

import android.os.Build
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.limbuszhcn.migration.MigrationActivity
import com.example.limbuszhcn.migration.MigrationModel
import com.example.limbuszhcn.migration.MigrationOperation
import com.example.limbuszhcn.migration.MigrationState
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationReentryTest {
    @get:Rule val ui = createEmptyComposeRule()
    private fun expect(text: String) = ui.waitUntil(20_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun completedTransferSurvivesActivityRecreationButNewVisitCanStartAnotherTransfer() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        ActivityScenario.launch(MigrationActivity::class.java).use { screen ->
            expect("迁出")
            // Seed only the terminal worker callback. The activity, back stack, screen and model are real.
            screen.onActivity { activity ->
                val model = MigrationModel.get(activity.application)
                val setter = model.javaClass.getDeclaredMethod("setState", MigrationState::class.java)
                setter.isAccessible = true
                setter.invoke(model, MigrationState(done = true, operation = MigrationOperation.Export))
            }
            expect("资源包已保存")
            screen.recreate()
            expect("资源包已保存")
        }
        ActivityScenario.launch(MigrationActivity::class.java).use { screen ->
            expect("迁入"); expect("迁出")
            screen.onActivity { assertFalse(MigrationModel.get(it.application).state.done) }
        }
    }
}
