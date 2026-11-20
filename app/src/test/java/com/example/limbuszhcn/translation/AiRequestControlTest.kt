package com.example.limbuszhcn.translation

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AiRequestControlTest {
    @Test fun stopIsIdempotentAndClosesTheActiveRequestOffCallerThread() {
        val control = AiRequestControl()
        val called = CountDownLatch(1)
        val caller = Thread.currentThread()
        control.attach { assertNotSame(caller, Thread.currentThread()); called.countDown() }.use {
            control.stop(); control.stop()
            assertTrue(called.await(2, TimeUnit.SECONDS))
            assertFalse(assertThrows(AiRequestCancelled::class.java) { control.check() }.sent)
        }
    }
    @Test fun completedRequestIsDetachedAndNewRunDoesNotInheritStop() {
        val control = AiRequestControl()
        var disconnected = false
        control.attach { disconnected = true }.close()
        control.stop()
        assertFalse(disconnected)
        AiRequestControl().check()
    }
    @Test fun cancelledBeforeSendingDoesNotRecordUnknownPaidUsage() {
        var usages = 0
        val client = AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _, _, _ -> throw AiRequestCancelled(false) })
        assertThrows(AiRequestCancelled::class.java) { client.translate(listOf(AiText("x", "Hello", "test")), onUsage = { usages++ }) }
        assertEquals(0, usages)
        val sent = AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _, _, _ -> throw AiRequestCancelled(true) })
        assertThrows(AiRequestCancelled::class.java) { sent.translate(listOf(AiText("x", "Hello", "test")), onUsage = { usages++ }) }
        assertEquals(1, usages)
    }
    @Test fun elapsedTimeTicksBetweenResponsesAndFreezesWithoutCountingPausedTime() {
        val p = AiProgress(elapsedMillis = 1200, sampledAt = 5000)
        assertEquals(4200L, p.elapsedAt(8000))
        val paused = p.freezeAt(8000)
        assertEquals(4200L, paused.elapsedAt(11000, false))
        assertEquals(5200L, paused.copy(sampledAt = 20000).elapsedAt(21000))
        assertEquals(4200L, paused.elapsedAt(1000)) // old boot timestamp is never serialized
    }
}
