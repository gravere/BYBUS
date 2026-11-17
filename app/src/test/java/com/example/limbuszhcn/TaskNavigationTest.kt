package com.example.limbuszhcn

import com.example.limbuszhcn.migration.MigrationOperation
import com.example.limbuszhcn.migration.MigrationState
import org.junit.Assert.*
import org.junit.Test

class TaskNavigationTest {
    @Test fun damagedPendingTaskStillHasAReachableCancelPage() {
        val state = LauncherUiState(ai = com.example.limbuszhcn.translation.AiUiState(recoveryIssue = true))
        assertEquals("ai-translation", state.taskDestination())
    }
    @Test fun progressChangesNeverChangeTheOwningPageAndCompletionReleasesIt() {
        for (page in listOf("repair", "ai-translation", "translation", "clear-data", "updates", "google", "status")) {
            val busy = LauncherUiState().beginOperation("初始阶段", false).copy(taskPage = page, stage = "之后的阶段")
            assertEquals(page, busy.taskDestination())
            assertNull(busy.finishOperation("完成", false, false).taskPage)
        }
    }
    @Test fun externalMigrationRemainsVisibleWithoutTurningAVisitIntoAnotherOperation() {
        val initial = LauncherUiState(stage = "首页", message = "原状态")
        val task = MigrationState(busy = true, operation = MigrationOperation.Resources, message = "正在复制资源")
        val visible = initial.withMigrationTask(task)
        assertTrue(visible.busy); assertEquals("migration", visible.taskDestination())
        assertEquals(task.message, visible.stage)
        assertEquals(initial, initial.withMigrationTask(task.copy(busy = false, done = true)))
    }
}
