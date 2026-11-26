package com.example.limbuszhcn.migration

import android.content.Context
import com.example.limbuszhcn.container.AndroidInstalledGameImporter
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded read-only pipe. Destination files are always created by the ordinary BYBUS UID. */
internal class RootResourceSession(context: Context) : Closeable {
    private val closed = AtomicBoolean()
    @Volatile private var lastRead = System.nanoTime()
    @Volatile private var timeout = false
    private val errors = StringBuilder()
    private val process: Process
    private val watcher = Executors.newSingleThreadScheduledExecutor { Thread(it, "bybus-root-timeout").apply { isDaemon = true } }
    private val stderr: Thread
    val input: java.io.InputStream

    init {
        val su = RootResourcePaths.findSu() ?: "su" // Hidden su can still be requested explicitly from the import entry.
        val info = context.packageManager.getApplicationInfo(RootResourcePaths.GAME, 0)
        val version = AndroidInstalledGameImporter.installedVersionCode(context.packageManager)
        val user = android.os.Process.myUid() / 100000
        check(info.uid / 100000 == user) { "只能导入当前 Android 用户的原版资源" }
        val command = RootResourcePaths.command(context.applicationInfo.sourceDir, user, version, info.dataDir)
        process = try { ProcessBuilder(su, "-c", command).start() }
        catch (error: IOException) { watcher.shutdownNow(); throw IOException("未找到可用 Root，请在已 Root 的设备上授权导入", error) }
        process.outputStream.close()
        stderr = Thread({
            runCatching { process.errorStream.bufferedReader().use { reader ->
                val buffer = CharArray(512)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    synchronized(errors) {
                        errors.append(buffer, 0, count)
                        if (errors.length > 4096) errors.delete(0, errors.length - 4096)
                    }
                }
            } }
        }, "bybus-root-errors").apply { isDaemon = true; start() }
        input = object : FilterInputStream(process.inputStream) {
            override fun read(): Int = super.read().also { lastRead = System.nanoTime() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { lastRead = System.nanoTime() }
        }
        watcher.scheduleAtFixedRate({
            if (!closed.get() && process.isAlive && System.nanoTime() - lastRead > TimeUnit.SECONDS.toNanos(120)) {
                timeout = true
                close()
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    fun verifyFinished() {
        check(process.waitFor(10, TimeUnit.SECONDS)) { "Root 读取进程未正常结束，请重试" }
        stderr.join(1000)
        check(!timeout && process.exitValue() == 0) { errorMessage() }
    }

    fun errorMessage(): String {
        if (timeout) return "Root 授权或资源读取超时，请确认授权后重试"
        val detail = synchronized(errors) { errors.toString() }.lineSequence()
            .lastOrNull { it.startsWith("BYBUS_ROOT_ERROR: ") }?.removePrefix("BYBUS_ROOT_ERROR: ")
        return detail ?: "未能读取原版资源，请确认 Root 已授权；原版和已有资源均已保留"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        watcher.shutdownNow()
        if (process.isAlive) process.destroyForcibly()
        runCatching { input.close() }
        runCatching { process.errorStream.close() }
    }
}
