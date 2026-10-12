package com.example.limbuszhcn.container

import com.example.limbuszhcn.storage.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** A request token prevents a prior process's response from being mistaken for the new launch. */
internal class RuntimeReceipt(private val indexRoot: Path) {
    fun beginLaunch() {
        Files.createDirectories(indexRoot)
        val temp = indexRoot.resolve("launch.request.tmp")
        temp.writeText(UUID.randomUUID().toString() + "\n")
        AtomicFiles.move(temp, indexRoot.resolve("launch.request"), true)
        Files.deleteIfExists(indexRoot.resolve("runtime-state.properties"))
        for (name in listOf("font-state.log", "integrity-state.log", "billing-state.log")) {
            Files.deleteIfExists(indexRoot.resolve(name))
        }
    }

    fun description(): String = runCatching {
        val request = indexRoot.resolve("launch.request").readText().trim()
        val receipt = AtomicFiles.readProperties(indexRoot.resolve("runtime-state.properties"))
        if (receipt.getProperty("request") != request) return "尚未收到本次游戏进程的确认"
        val detail = when (receipt.getProperty("state")) {
            "hooks_installed" -> "上次启动：索引和文本接管已加载，游戏画面仍需确认"
            "index_loaded" -> "上次启动：索引已加载，等待文本接管初始化"
            "no_index" -> "上次启动：资源准备模式，尚无可用索引"
            else -> "上次启动：文本接管未完成，可导出诊断包"
        }
        val profile = receipt.getProperty("nativeProfile", "")
        when {
            profile.isBlank() -> "$detail；当前游戏构建的底层兼容性未验证"
            profile == com.lody.virtual.helper.compat.LimbusNativeStructure.FAMILY -> "$detail；已按当前安装包自动适配"
            else -> detail
        }
    }.getOrDefault("尚未收到游戏运行时确认；当前游戏构建的底层兼容性未验证")
}
