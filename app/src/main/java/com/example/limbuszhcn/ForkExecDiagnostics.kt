package com.example.limbuszhcn

import java.io.File

/** Only sampled with a blocked Java process-launch stack, never starts another process. */
internal fun forkExecKernelSnapshot(
    ownerPid: Int,
    tids: List<Int>,
    read: (String, Int) -> String? = ::readBoundedProc
): List<String> {
    fun state(base: String): String {
        val wchan = read("$base/wchan", 128)?.trim()
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_.]{1,96}")) } ?: "unavailable"
        // Export only the syscall number. Arguments can be pointers or private data.
        val syscall = read("$base/syscall", 512)?.trim()?.substringBefore(' ')
            ?.let { if (it == "running") it else it.toLongOrNull()?.takeIf { n -> n in -1..65535 }?.toString() }
            ?: "unavailable"
        val status = read("$base/status", 4096).orEmpty()
        val taskState = Regex("(?m)^State:\\s+([A-Z])(?:\\s|$)").find(status)?.groupValues?.get(1) ?: "unknown"
        return "state=$taskState wchan=$wchan syscall=$syscall"
    }
    if (ownerPid <= 0) return emptyList()
    return buildList {
        var observed = 0
        for (tid in tids.asSequence().filter { it > 0 }.distinct().take(512)) {
            val base = "/proc/$ownerPid/task/$tid"
            if (read("$base/comm", 64)?.trim() != "UnityMain") continue
            add("thread=unity tid=$tid ${state(base)}")
            val children = read("$base/children", 512)
            if (children == null) add("thread=unity children=unavailable")
            else {
                val ids = children.trim().split(Regex("\\s+"))
                    .mapNotNull(String::toIntOrNull).filter { it > 0 && it != ownerPid }.distinct().take(4)
                add("thread=unity children=${ids.size}")
                for (pid in ids) {
                    val child = "/proc/$pid"
                    val status = read("$child/status", 4096).orEmpty()
                    val parent = Regex("(?m)^PPid:\\s+(\\d+)\\s*$").find(status)?.groupValues?.get(1)?.toIntOrNull()
                    if (parent == ownerPid) add("childPid=$pid ${state(child)}")
                    else add("childPid=$pid state=unavailable_or_exited")
                }
            }
            if (++observed == 2) break
        }
        if (isEmpty()) add("thread=unity kernelState=unavailable")
    }
}

internal fun execPhaseSnapshot(values: LongArray): List<String> = values.asList().chunked(3).take(16).mapNotNull { row ->
    if (row.size != 3 || row[0] !in 1..Int.MAX_VALUE.toLong()) return@mapNotNull null
    val phase = when (row[1]) {
        1L -> "entered"
        2L -> "path_ready"
        3L -> "elf_read"
        4L -> "exec_syscall_entered"
        5L -> "exec_failed"
        else -> return@mapNotNull null
    }
    "childPid=${row[0]} phase=$phase errno=${row[2].coerceIn(0, 4095)}"
}

private fun readBoundedProc(path: String, limit: Int): String? = runCatching {
    File(path).inputStream().use { input ->
        val bytes = ByteArray(limit)
        var used = 0
        while (used < limit) {
            val count = input.read(bytes, used, limit - used)
            if (count <= 0) break
            used += count
        }
        bytes.decodeToString(0, used)
    }
}.getOrNull()
