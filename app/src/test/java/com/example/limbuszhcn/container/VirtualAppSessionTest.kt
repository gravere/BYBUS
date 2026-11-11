package com.example.limbuszhcn.container

import org.junit.Assert.*
import org.junit.Test

class VirtualAppSessionTest {
    private val game = LocalContainerRuntime.GAME_PACKAGE
    private fun task(id: Int = 8, activities: Int = 1, pkg: String = game) =
        SessionTask(id, activities, "$pkg/$pkg.Main", "com.lody.virtual.client.stub.ShadowActivity\$P1", null)
    private class Access(var state: SessionSnapshot, var fail: Boolean = false) : SessionTaskAccess {
        val moved = mutableListOf<Int>()
        override fun snapshot() = state
        override fun moveToFront(taskId: Int) {
            check(!fail) { "task move failed" }
            moved += taskId
        }
    }

    @Test fun returnsSameGameTaskWithoutRecreatingIt() {
        val access = Access(SessionSnapshot(game, setOf(123), listOf(task())))
        assertTrue(VirtualAppSession(access).resume())
        assertEquals(listOf(8), access.moved)
        assertEquals(setOf(123), access.state.pids)
    }

    @Test fun deadGuestAndEmptyRecentsCannotResume() {
        for (state in listOf(SessionSnapshot(game, emptySet(), listOf(task())),
            SessionSnapshot(game, setOf(123), listOf(task(activities = 0))),
            SessionSnapshot(game, setOf(123), emptyList()))) {
            val access = Access(state)
            assertFalse(VirtualAppSession(access).resume())
            assertTrue(access.moved.isEmpty())
        }
    }

    @Test fun doesNotReturnHostOrGoogleTasks() {
        val host = SessionTask(1, 1, null, "com.example.limbuszhcn.MainActivity", null)
        val wrongClass = task().copy(base = host.base)
        val access = Access(SessionSnapshot(game, setOf(123), listOf(host, task(pkg = "com.google.android.gms"), wrongClass, task())))
        assertTrue(VirtualAppSession(access).resume())
        assertEquals(listOf(8), access.moved)
    }

    @Test fun failurePropagatesInsteadOfPermittingColdLaunch() {
        val access = Access(SessionSnapshot(game, setOf(123), listOf(task())), fail = true)
        assertThrows(IllegalStateException::class.java) { VirtualAppSession(access).resume() }
        assertTrue(access.moved.isEmpty())
    }

    @Test fun taskIsRequeriedWhenUserClicks() {
        val access = Access(SessionSnapshot(game, setOf(123), listOf(task())))
        val session = VirtualAppSession(access)
        assertTrue(session.snapshot().resumableTasks.isNotEmpty())
        access.state = access.state.copy(pids = emptySet())
        assertFalse(session.resume())
        assertTrue(access.moved.isEmpty())
    }

    @Test fun diagnosticContainsOnlyBoundedOwnGameMetadata() {
        val info = SessionSnapshot(game, setOf(123), listOf(task(), task(pkg = "private.other"))).diagnostic()
        assertTrue(info.contains("liveGuestPids=123"))
        assertTrue(info.contains("taskId=8 activities=1 resumable=true"))
        assertFalse(info.contains("private.other"))
    }
}
