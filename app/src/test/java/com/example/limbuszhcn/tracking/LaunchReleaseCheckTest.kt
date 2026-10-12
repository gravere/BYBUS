package com.example.limbuszhcn.tracking

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.Executor

class LaunchReleaseCheckTest {
    @Test fun localPreparationConsumesBudgetAndSlowChecksAreSharedWithoutQueuing() {
        val pending = mutableListOf<Runnable>()
        var tick = 0L
        var calls = 0
        val checker = LaunchReleaseCheck(Executor { pending += it }, { calls++; "release" }, { tick })
        val first = checker.start(4_000)
        tick = 4_000_000_001
        assertThrows(SocketTimeoutException::class.java) { first.await() }
        val next = checker.start(4_000)
        assertEquals(1, pending.size)
        pending.single().run()
        assertEquals("release", next.await())
        assertEquals(1, calls)
        checker.start(4_000)
        assertEquals(2, pending.size)
    }

    @Test fun completedChecksAndOriginalQuotaFailuresAreReturnedWithoutAnotherWait() {
        var tick = 0L
        val direct = Executor { it.run() }
        val success = LaunchReleaseCheck(direct, { "release" }, { tick }).start(4_000)
        tick = 20_000_000_000
        assertEquals("release", success.await())
        val failure = ProjectFetchException(ProjectIssue.RateLimited, 50_000)
        val pending = LaunchReleaseCheck(direct, { throw failure }).start(4_000)
        assertSame(failure, assertThrows(ProjectFetchException::class.java) { pending.await() })
    }
}
