package com.example.limbuszhcn

import org.junit.Assert.*
import org.junit.Test

class GameStartupThreadsTest {
    @Test fun `repeated firebase workers cannot evict the blocked game thread`() {
        val unity = "thread=unity state=RUNNABLE frames=java.lang.UNIXProcess.forkAndExec:native"
        val main = "thread=main state=RUNNABLE frames=android.os.MessageQueue.nativePollOnce:native"
        val workers = List(20) { "thread=firebase state=WAITING frames=worker${it % 10}" }
        val result = selectStartupThreadSummaries(workers + main + unity + workers)
        assertEquals(listOf(unity, main), result.take(2))
        assertEquals(8, result.size)
        assertEquals(result.distinct(), result)
    }

    @Test fun `startup thread report retains blocking frames without thread names or file contents`() {
        val frames = arrayOf(
            StackTraceElement("java.lang.Object", "wait", "private-file.txt", -2),
            StackTraceElement("com.google.firebase.app.internal.cpp.CppThreadDispatcherContext", "execute", "email@example.com", 12),
            StackTraceElement("private.login.example", "secret", "token.txt", 4)
        )
        val result = startupThreadSummary("user@example.com", Thread.State.WAITING, frames)!!
        assertTrue(result.contains("thread=firebase state=WAITING"))
        assertTrue(result.contains("java.lang.Object.wait:native"))
        assertTrue(result.endsWith("external"))
        listOf("user@", "email@", "private-file", "token", "private.login", "secret").forEach {
            assertFalse(result.contains(it))
        }
    }

    @Test fun `unrelated threads are ignored and known stacks remain bounded`() {
        assertNull(startupThreadSummary("background private name", Thread.State.RUNNABLE,
            arrayOf(StackTraceElement("java.lang.Thread", "run", "Thread.java", 1))))
        val result = startupThreadSummary("UnityMain", Thread.State.RUNNABLE,
            Array(40) { StackTraceElement("com.unity3d.player.UnityPlayer", "nativeRender", null, -2) })!!
        assertEquals(12, result.substringAfter("frames=").split(',').size)
        assertTrue(result.contains("thread=unity"))
    }
}
