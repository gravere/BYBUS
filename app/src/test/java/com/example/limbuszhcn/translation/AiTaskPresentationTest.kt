package com.example.limbuszhcn.translation

import org.junit.Assert.*
import org.junit.Test

class AiTaskPresentationTest {
    @Test fun compactSurfacesDoNotReceiveBatchSentencesOrCounters() {
        val examples = mapOf("等待翻译结果 · 本批 1 条" to "等待回复", "正在翻译所选文本" to "正在翻译",
            "读取已保存的汉化 123/5678" to "准备翻译", "系统休眠限制联网，等待恢复" to "等待网络",
            "正在校验本批译文" to "核对译文", "正在停止，保留已完成进度" to "正在暂停")
        examples.forEach { (message, expected) -> assertEquals(expected, compactAiTaskLabel(message)) }
        assertEquals("正在翻译", compactAiTaskLabel("很长的未知阶段描述".repeat(30)))
    }
    @Test fun elapsedTimeHasAStableCompactWidth() {
        assertEquals("02:51", formatTaskDuration(171000))
        assertEquals("11:56", formatTaskDuration(716000))
        assertEquals("1:00:00", formatTaskDuration(3600000))
        assertEquals("00:00", formatTaskDuration(-1))
    }
    @Test fun oldCancellationCannotDeleteANewTaskAndStillAppliesToTheSamePausedTask() {
        val request = AiCancelPrompt("session-a", "task-a")
        assertTrue(request.matches("session-a", "task-a"))
        assertTrue(request.matches(null, "task-a"))
        assertFalse(request.matches("session-b", "task-b"))
        assertFalse(request.matches(null, null))
        assertFalse(AiCancelPrompt("session-a", null).matches(null, "task-b"))
    }
}
