package com.example.limbuszhcn.translation

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class AiTaskDispatchTest {
    @Test fun workWaitsForSystemAndIsDispatchedOnlyOnce() {
        val queue = mutableListOf<Runnable>()
        val dispatch = AiTaskDispatch()
        var calls = 0
        dispatch.execute(Executor { queue += it }, Runnable { calls++ })
        assertTrue(queue.isEmpty())
        repeat(3) { dispatch.release() }
        assertEquals(1, queue.size)
        queue.single().run()
        assertEquals(1, calls)
    }

    @Test fun systemCallbackBeforeRegistrationStillDispatchesOnce() {
        val queue = mutableListOf<Runnable>()
        val dispatch = AiTaskDispatch()
        dispatch.release()
        dispatch.execute(Executor { queue += it }, Runnable {})
        dispatch.release()
        assertEquals(1, queue.size)
        assertThrows(IllegalStateException::class.java) { dispatch.execute(Executor {}, Runnable {}) }
    }
}
