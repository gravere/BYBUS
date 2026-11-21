package com.example.limbuszhcn.translation

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiBackgroundInterruptionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun unknownSystemStopSurvivesRecoveryWithoutLosingCompletedProgress() {
        val cache = temp.root.toPath()
        val store = AiTaskStore(cache)
        val saved = AiTaskSnapshot("a".repeat(32), AiConfig(limit = 49), "zero", "来源", "b".repeat(32), "汉化", null,
            phase = AiTaskPhase.Paused, progress = AiProgress(3, 49, elapsedMillis = 61_000),
            error = "系统暂停了后台翻译，已保存的进度会保留", backgroundStopReason = 17)
        store.write(saved)
        val recovered = AiTaskStore(cache).recover(TranslationSources(cache).read())!!
        assertEquals(17, recovered.backgroundStopReason)
        assertEquals(saved.error, recovered.error)
        assertEquals(3, recovered.progress.completed)
        assertEquals(61_000L, recovered.progress.elapsedMillis)
        assertEquals(AiTaskPhase.Paused, recovered.phase)
        store.write(recovered.copy(phase = AiTaskPhase.Running, error = null, backgroundStopReason = null))
        assertNull(AiTaskStore(cache).read()!!.backgroundStopReason)
    }

    @Test fun oldAndUserPausedTasksDoNotAcquireASystemFailure() {
        val cache = temp.root.toPath()
        val store = AiTaskStore(cache)
        store.write(AiTaskSnapshot("a".repeat(32), AiConfig(), "zero", "来源", "b".repeat(32), "汉化", null,
            phase = AiTaskPhase.Paused))
        val recovered = AiTaskStore(cache).recover(TranslationSources(cache).read())!!
        assertNull(recovered.backgroundStopReason)
        assertNull(recovered.error)
    }
}
