package com.example.limbuszhcn.storage

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Covers UI and debug writers, including separate host processes. */
internal object OperationLock {
    private val local = ConcurrentHashMap<Path, ReentrantLock>()
    fun <T> run(root: Path, task: () -> T): T {
        val key = root.toAbsolutePath().normalize()
        val mutex = local.computeIfAbsent(key) { ReentrantLock() }
        mutex.lockInterruptibly()
        try {
            Files.createDirectories(key)
            FileChannel.open(key.resolve("launcher-operation.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val lock = channel.tryLock() ?: throw IllegalStateException("另一进程正在处理游戏，请稍后重试")
                lock.use { return task() }
            }
        } finally { mutex.unlock() }
    }
}
