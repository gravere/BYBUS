package com.example.limbuszhcn

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LauncherOperationFeedbackTest {
    @Test fun aiTestFailureDoesNotChangeGameReadinessOrAnExistingOperation() {
        for (initial in listOf(LauncherUiState(), LauncherUiState(busy = true, failed = true, stage = "准备启动", message = "原有错误", launchStep = 2))) {
            val testing = initial.beginAiSettings("正在测试翻译")
            assertEquals(initial, testing.copy(ai = initial.ai))
            val failed = testing.finishAiSettings(Result.failure(IllegalArgumentException("API 授权失败")))
            assertTrue(failed.ai.failed); assertFalse(failed.ai.settingsBusy)
            assertEquals("API 授权失败", failed.ai.message)
            assertEquals(initial, failed.copy(ai = initial.ai))
            val success = failed.beginAiSettings("重试").finishAiSettings(Result.success("测试成功"))
            assertFalse(success.ai.failed)
            assertEquals(initial, success.copy(ai = initial.ai))
        }
    }
    @Test fun resumeRefreshRetainsFailureUntilAnExplicitOperation() {
        val failure = LauncherUiState(failed = true, message = "资源校验失败", stage = "准备启动")
        val refreshed = failure.beginOperation("正在检测游戏", true)
            .finishOperation("已匹配当前安装版本", false, true)
        assertTrue(refreshed.failed)
        assertEquals(failure.message, refreshed.message)
        assertEquals(failure.stage, refreshed.stage)
        assertFalse(refreshed.busy)
        val retried = refreshed.beginOperation("正在检测游戏", false)
            .finishOperation("已匹配当前安装版本", false, false)
        assertFalse(retried.failed)
        assertEquals("已匹配当前安装版本", retried.message)
    }

    @Test fun persistedExportCancellationCannotBecomeSavedFeedback() {
        val directory = Files.createTempDirectory("cancel-export-feedback")
        val archive = directory.resolve("limbus-diagnostics-20261106-120000.zip")
        Files.write(archive, byteArrayOf(80, 75, 3, 4))
        DiagnosticExportState(directory).begin(archive)
        DiagnosticExportState(directory).selected(null)
        val pending = DiagnosticExportState(directory).read()!!
        assertEquals("已取消导出", completedOperationFeedback(diagnosticExportStage(pending.cancelled)))
        assertEquals("诊断包已保存", completedOperationFeedback(diagnosticExportStage(false)))
    }

    @Test fun everyUpdateNavigationCanUseTheSameMonotonicIdPolicy() {
        val first = nextUiRequestId(0, 1000)
        assertEquals(1000L, first)
        assertEquals(1001L, nextUiRequestId(first, 1000))
        assertNotEquals(first, nextUiRequestId(0, 1200))
    }
}
