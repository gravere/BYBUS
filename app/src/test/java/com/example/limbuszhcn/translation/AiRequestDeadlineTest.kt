package com.example.limbuszhcn.translation

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AiRequestDeadlineTest {
    @Test fun heartbeatsCannotKeepAnEmptyRequestAliveButRealContentCanExtendIdleTime() {
        val clock = AtomicLong(0); val closed = AtomicInteger()
        AiRequestDeadline({ closed.incrementAndGet() }, clock::get, 120_000, 300_000).use { deadline ->
            clock.set(119_000)
            deadline.progress(AiResponseProgress(AiResponseStage.Thinking, characters = 10))
            clock.set(238_000); deadline.tick(); deadline.check()
            assertEquals(0, closed.get())
            deadline.progress(AiResponseProgress(AiResponseStage.Waiting, bytes = 10000, heartbeats = 100, characters = 10))
            clock.set(239_000); deadline.tick()
            assertFalse(assertThrows(AiRequestTimeout::class.java) { deadline.check() }.total)
            deadline.tick(); assertEquals(1, closed.get())
        }
    }

    @Test fun continuousThinkingCannotExceedTotalBudgetAndCompletionDisarmsTheTimer() {
        val clock = AtomicLong(0); val closed = AtomicInteger()
        AiRequestDeadline({ closed.incrementAndGet() }, clock::get, 120_000, 300_000).use { deadline ->
            for (second in listOf(100L, 200L, 299L)) {
                clock.set(second * 1000)
                deadline.progress(AiResponseProgress(AiResponseStage.Thinking, characters = second))
                deadline.tick(); deadline.check()
            }
            clock.set(300_000); deadline.tick()
            assertTrue(assertThrows(AiRequestTimeout::class.java) { deadline.complete() }.total)
            assertEquals(1, closed.get())
        }
        AiRequestDeadline({ closed.incrementAndGet() }, clock::get).use { deadline ->
            deadline.complete(); clock.set(999_999); deadline.tick(); deadline.check()
        }
        assertEquals(1, closed.get())
    }
}
