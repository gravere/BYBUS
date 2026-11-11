package com.example.limbuszhcn.container

import com.example.limbuszhcn.storage.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.Properties
import com.lody.virtual.helper.compat.LimbusSafetyAlert
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** A request token prevents a prior process's response from being mistaken for the new launch. */
internal class RuntimeReceipt(private val indexRoot: Path) {
    fun beginLaunch() {
        Files.createDirectories(indexRoot)
        val indexMode = TranslationIndexStore(indexRoot).selectRuntimeIndex()
        val mode = if (LiveTranslationStore(indexRoot.parent).read() != null) "live" else indexMode
        indexRoot.resolve("launch-mode.txt").writeText(mode + "\n", Charsets.UTF_8)
        val temp = indexRoot.resolve("launch.request.tmp")
        temp.writeText(UUID.randomUUID().toString() + "\n")
        AtomicFiles.move(temp, indexRoot.resolve("launch.request"), true)
        Files.deleteIfExists(indexRoot.resolve("runtime-state.properties"))
        for (name in listOf("font-state.log", "integrity-state.log", "billing-state.log", "safety-alert.properties")) {
            Files.deleteIfExists(indexRoot.resolve(name))
        }
    }

    /** Called in the container server before forwarding the game's own safety dialog. */
    fun recordSafetyAlert(callerPid: Int, summary: LimbusSafetyAlert.Summary): Boolean {
        require(callerPid > 0)
        val request = currentRequest() ?: return false
        val runtime = readReceipt("runtime-state.properties")
        if (runtime.getProperty("request") == request &&
            runtime.getProperty("pid")?.toIntOrNull()?.let { it != callerPid } == true) return false
        val values = Properties().apply {
            setProperty("request", request)
            setProperty("pid", callerPid.toString())
            setProperty("timeMillis", System.currentTimeMillis().toString())
            setProperty("type", summary.type.toString())
            setProperty("codes", summary.codes)
            setProperty("category", summary.category)
            setProperty("messagePresent", summary.messagePresent.toString())
            setProperty("messageTruncated", summary.messageTruncated.toString())
        }
        AtomicFiles.properties(indexRoot.resolve("safety-alert.properties"), values)
        return true
    }

    fun diagnosticSummary(): String = buildString {
        val request = currentRequest()
        val receipt = readReceipt("runtime-state.properties")
        val current = request != null && receipt.getProperty("request") == request
        appendLine("translationLaunchMode=${launchMode()}")
        appendLine("runtimeReceiptCurrent=$current")
        if (current) {
            val state = receipt.getProperty("state").takeIf {
                it in setOf("hooks_installed", "index_loaded", "no_index", "hooks_failed")
            } ?: "unknown"
            val font = receipt.getProperty("fontState").takeIf { it in setOf("pending", "ready", "failed") } ?: "unknown"
            appendLine("runtimeState=$state")
            appendLine("runtimeFontState=$font")
            for (key in listOf("pid", "entries", "terms", "fontAttempts", "liveResources", "liveResourceMerges")) {
                appendLine("runtime_$key=${receipt.getProperty(key)?.toLongOrNull()?.takeIf { it >= 0 } ?: -1}")
            }
        }
        val alert = currentSafetyAlert()
        appendLine("safetyAlertObserved=${alert != null}")
        if (alert != null) {
            appendLine("safetyAlertCodes=${safeCodes(alert).ifEmpty { "unknown" }}")
            appendLine("safetyAlertType=${alert.getProperty("type")?.toIntOrNull()?.takeIf { it in 0..255 } ?: -1}")
            appendLine("safetyAlertCategory=${alert.getProperty("category").takeIf {
                it in setOf("root", "emulator", "debugger", "integrity", "blacklist", "work_profile")
            } ?: "unknown"}")
        }
    }

    private fun readReceipt(name: String): Properties = runCatching {
        val file = indexRoot.resolve(name)
        require(Files.size(file) <= 4096)
        AtomicFiles.readProperties(file)
    }.getOrDefault(Properties())

    private fun currentRequest(): String? = runCatching {
        val file = indexRoot.resolve("launch.request")
        require(Files.size(file) <= 64)
        file.readText().trim().takeIf { UUID.fromString(it).toString() == it }
    }.getOrNull()

    private fun launchMode(): String = runCatching {
        val file = indexRoot.resolve("launch-mode.txt")
        require(Files.size(file) <= 16)
        file.readText().trim().takeIf { it in setOf("none", "startup", "full", "live") } ?: "unknown"
    }.getOrDefault("unknown")

    private fun currentSafetyAlert(): Properties? {
        val request = currentRequest() ?: return null
        val alert = readReceipt("safety-alert.properties")
        if (alert.getProperty("request") != request) return null
        val runtime = readReceipt("runtime-state.properties")
        if (runtime.getProperty("request") == request && runtime.getProperty("pid") != null &&
            runtime.getProperty("pid") != alert.getProperty("pid")) return null
        return alert
    }

    private fun safeCodes(alert: Properties): String = alert.getProperty("codes", "")
        .split(',').filter { it.matches(Regex("[1-9][0-9]{4}")) }.distinct().take(8).joinToString(",")

    fun description(): String = runCatching {
        currentSafetyAlert()?.let { alert ->
            val code = safeCodes(alert).ifEmpty { "未提供编号" }
            return "上次启动：游戏安全提示（$code），可导出诊断包查看"
        }
        val request = indexRoot.resolve("launch.request").readText().trim()
        val receipt = AtomicFiles.readProperties(indexRoot.resolve("runtime-state.properties"))
        if (receipt.getProperty("request") != request) return "尚未收到本次游戏进程的确认"
        val detail = when (receipt.getProperty("state")) {
            "hooks_installed" -> when (receipt.getProperty("fontState")) {
                "failed" -> "上次启动：中文字体初始化失败，可导出诊断包"
                "pending" -> "上次启动：文本接管已加载，等待中文字体初始化"
                "ready" -> if (launchMode() == "startup")
                    "上次启动：启动页索引和字体已加载，完整汉化等待游戏资源下载"
                    else "上次启动：索引、文本接管和中文字体已加载"
                else -> "上次启动：索引和文本接管已加载，尚无字体确认"
            }
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
