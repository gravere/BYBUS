package com.example.limbuszhcn.container

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Per-Intent acknowledgment; returning from startActivity alone does not finish startup. */
internal class GameLaunchHandoff(private val packageName: String, private val userId: Int = 0) {
    private val opened = CountDownLatch(1)

    fun onAppOpened(actualPackage: String?, actualUser: Int) {
        if (actualPackage == packageName && actualUser == userId) opened.countDown()
    }

    fun isOpened(actualPackage: String?, actualUser: Int): Boolean =
        actualPackage == packageName && actualUser == userId && opened.count == 0L

    fun await(timeoutMillis: Long = 30_000) {
        if (!opened.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw ContainerRuntimeUnavailableException("游戏未及时打开，请查看运行状态后重试；已有数据保留")
        }
    }
}
