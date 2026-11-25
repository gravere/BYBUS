package com.example.limbuszhcn

import org.junit.Assert.*
import org.junit.Test

class ForkExecDiagnosticsTest {
    @Test fun `blocked child is correlated without arguments or arbitrary thread data`() {
        val data = mapOf(
            "/proc/20/task/21/comm" to "UnityMain\n",
            "/proc/20/task/22/comm" to "private@example.com",
            "/proc/20/task/21/syscall" to "220 0xDEADBEEF SECRET_ARGUMENTS 3 4",
            "/proc/20/task/21/wchan" to "wait_for_vfork_done\n",
            "/proc/20/task/21/status" to "Name:\tsecret\nState:\tD (disk sleep)\n",
            "/proc/20/task/21/children" to "30 31",
            "/proc/30/status" to "PPid:\t20\nState:\tS (sleeping)\n",
            "/proc/30/wchan" to "futex_wait_queue_me\n",
            "/proc/30/syscall" to "98 0x12345678 private-token",
            "/proc/31/status" to "PPid:\t99\nState:\tS (sleeping)\n"
        )
        val accesses = mutableListOf<String>()
        val result = forkExecKernelSnapshot(20, listOf(21, 22)) { path, limit ->
            accesses += path
            data[path]?.take(limit)
        }.joinToString("\n")
        assertTrue(result.contains("tid=21 state=D wchan=wait_for_vfork_done syscall=220"))
        assertTrue(result.contains("childPid=30 state=S wchan=futex_wait_queue_me syscall=98"))
        assertTrue(result.contains("childPid=31 state=unavailable_or_exited"))
        listOf("SECRET", "private", "secret", "DEADBEEF", "12345678").forEach { assertFalse(result.contains(it)) }
        assertFalse(accesses.contains("/proc/31/syscall"))
    }

    @Test fun `denied kernel data and incomplete exec snapshots stay explicit`() {
        assertEquals(listOf("thread=unity kernelState=unavailable"), forkExecKernelSnapshot(20, listOf(21)) { _, _ -> null })
        assertEquals(listOf("childPid=30 phase=exec_syscall_entered errno=0", "childPid=31 phase=exec_failed errno=2"),
            execPhaseSnapshot(longArrayOf(30, 4, 0, 31, 5, 2, -1, 9, 22, 32)))
    }
}
