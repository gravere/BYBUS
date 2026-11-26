package com.example.limbuszhcn.migration

import com.example.limbuszhcn.gamefs.ResourceArchiveWriter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Entry point of a short-lived, explicitly authorized su process. No Application/container is initialized. */
object RootResourceMain {
    @JvmStatic fun main(args: Array<String>) {
        try {
            check(android.os.Process.myUid() == 0) { "未获得 Root 授权" }
            require(args.size == 3) { "资源导入参数无效" }
            val user = args[0].toInt()
            val version = args[1].toLong().also { require(it > 0) }
            val roots = RootResourcePaths.roots(user, args[2])
            val stop = ProcessBuilder("/system/bin/am", "force-stop", "--user", user.toString(), RootResourcePaths.GAME)
                .redirectOutput(java.io.File("/dev/null")).redirectError(java.io.File("/dev/null")).start()
            if (!stop.waitFor(15, TimeUnit.SECONDS)) { stop.destroyForcibly(); error("原版游戏未能退出，请关闭后重试") }
            check(stop.exitValue() == 0) { "原版游戏未能退出，请关闭后重试" }
            ResourceArchiveWriter(roots, version).write(System.out)
            exitProcess(0)
        } catch (error: Throwable) {
            System.err.println("BYBUS_ROOT_ERROR: " + (error.message ?: "无法读取原版资源").take(500))
            exitProcess(1)
        }
    }
}
