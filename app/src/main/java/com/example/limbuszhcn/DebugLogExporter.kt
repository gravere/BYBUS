package com.example.limbuszhcn

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import com.example.limbuszhcn.container.ContainerStatus
import com.example.limbuszhcn.container.LocalContainerRuntime
import com.example.limbuszhcn.container.MicrogContainerConfig
import com.example.limbuszhcn.gamefs.PatchInstallSummary
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class DebugLogExporter(private val context: Context) {
    fun export(
        containerStatus: ContainerStatus,
        activeTranslation: PatchInstallSummary?
    ): File {
        val outputDirectory = File(context.filesDir, "diagnostics").apply {
            require(isDirectory || mkdirs()) { "无法创建诊断目录" }
            listFiles()?.filter { it.name.startsWith(FILE_PREFIX) }?.forEach(File::delete)
        }
        val stamp = LocalDateTime.now().format(FILE_STAMP)
        val output = File(outputDirectory, "$FILE_PREFIX$stamp.zip")
        val captureStarted = Instant.now()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            zip.writeText("README.txt", README)
            zip.writeText(
                "ISSUE_TEMPLATE.md",
                issueTemplate(containerStatus, activeTranslation)
            )
            zip.writeText(
                "runtime.txt",
                runtimeSummary(containerStatus, activeTranslation)
            )
            // 持久日志比 logcat 更能覆盖 vivo 等系统在后台直接回收进程后的故障现场。
            PersistentDiagnosticLog.files(context)
                .sortedByDescending(File::lastModified)
                .take(MAX_PERSISTENT_LOG_FILES)
                .sortedBy(File::getName)
                .forEach { logFile ->
                    val content = diagnosticTail(logFile, MAX_PERSISTENT_LOG_BYTES)
                        .redactedAndBounded(MAX_PERSISTENT_LOG_BYTES)
                    zip.writeText("persistent/${logFile.name}", content)
                }
            val logcat = collectLogcat()
            val startup = collectGameStartup()
            zip.writeText("logcat.txt", logcat)
            zip.writeText("game-startup.txt", startup)
            zip.writeText("java-faults.txt", collectJavaFaults())
            zip.writeText("process-starts.txt", SystemStartupDiagnostics.collect(context))
            zip.writeText("game-session.txt", runCatching {
                com.example.limbuszhcn.container.VirtualAppSession(context).snapshot().diagnostic()
            }.getOrElse { "live snapshot unavailable: ${it.javaClass.simpleName}\n" })
            zip.writeText("app-install.txt", com.example.limbuszhcn.selfupdate.AppUpdateInstaller(context).summary())
            zip.writeText("game-install.txt", GameInstallationDiagnostics.collect(context))
            // Recover only old host/server crash records omitted by earlier diagnostic exports.
            val legacyNames = Regex("(?:null|${Regex.escape(context.packageName.replace(':', '_') + "_x")})_\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\.log")
            val legacyDirectory = File(context.filesDir, "saftySandBoxExceptionLog")
            val legacyFiles = if (java.nio.file.Files.isDirectory(legacyDirectory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                legacyDirectory.listFiles().orEmpty() else emptyArray()
            legacyFiles.filter { java.nio.file.Files.isRegularFile(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && legacyNames.matches(it.name) }
                .sortedByDescending(File::lastModified).take(4).forEach { file ->
                    val text = runCatching {
                        file.inputStream().use { String(it.readBounded(32 * 1024), Charsets.UTF_8) }
                    }.getOrElse { "Legacy host record unavailable: ${it.javaClass.simpleName}" }
                    zip.writeText("legacy-host/${file.name}", text.redactedAndBounded(32 * 1024))
                }
            // Fixed, bounded runtime metadata only; never include the index or game text.
            for (name in listOf("font-state.log", "font-hooks.log", "integrity-state.log", "billing-state.log")) {
                val file = File(context.filesDir, "translation-cache/translation-index/$name")
                if (file.isFile) {
                    val text = file.inputStream().use { String(it.readBounded(32 * 1024), Charsets.UTF_8) }
                    zip.writeText("native/$name", text.redactedAndBounded(32 * 1024))
                }
            }
            val nativeRoot = File(context.filesDir, "translation-cache/translation-index")
            val nativeTraces = nativeRoot.listFiles().orEmpty().filter {
                it.name.matches(Regex("termination-[0-9]{1,10}\\.log")) &&
                    java.nio.file.Files.isRegularFile(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
            }.sortedByDescending(File::lastModified).take(6)
            nativeTraces.forEach { file ->
                zip.writeText("native/${file.name}", diagnosticTail(file, 32 * 1024).redactedAndBounded(32 * 1024))
            }
            zip.writeText("native/termination-format.txt", "event: 0=ready 1=kill 2=raise 3=tkill 4=tgkill 5=pthread_kill 6=exit 7=libc_syscall 8=fault 9=unhandled_fault\n" +
                "disposition: -1=observed_handler_entry 0=forwarded 1=existing_block 2=existing_exit_handler 3=unconsumed_fault\n" +
                "scope: existing libc hooks only; direct kernel calls and external SIGKILL senders are not intercepted\n" +
                "limit: first 8 records per event class per trace family (including fork children); fixed 384-byte slots; at most 6 trace files\n")
            zip.writeText("capture-files.txt", buildString {
                appendLine("nativeTerminationFiles=${nativeTraces.size}")
                nativeTraces.forEach { appendLine("native/${it.name} bytes=${it.length()} modified=${Instant.ofEpochMilli(it.lastModified())} truncated=${it.length() > 32 * 1024}") }
                PersistentDiagnosticLog.files(context).sortedByDescending(File::lastModified).take(MAX_PERSISTENT_LOG_FILES).forEach {
                    appendLine("persistent/${it.name} bytes=${it.length()} modified=${Instant.ofEpochMilli(it.lastModified())} truncated=${it.length() > MAX_PERSISTENT_LOG_BYTES}")
                }
            })
            val exits = collectExitReasons()
            zip.writeText("process-exits.txt", exits.summary)
            exits.traces.forEachIndexed { index, trace ->
                // Native tombstone 是 protobuf 二进制；按 UTF-8 写回会破坏地址和字段长度。
                zip.writeBytes("exit-trace-${index + 1}.pb", trace.bytes)
            }
            zip.writeText("capture-status.txt", buildString {
                appendLine("schema=4")
                appendLine("captureStarted=$captureStarted")
                appendLine("captureFinished=${Instant.now()}")
                appendLine("scope=own_uid_only")
                appendLine("logcat=${when {
                    logcat.startsWith("[truncated to recent data]") -> "collected_truncated"
                    logcat.startsWith("command=") && logcat.contains("\nexitCode=0\n") -> "collected_bounded"
                    logcat.startsWith("command=") && logcat.contains("\nexitCode=bounded_read\n") -> "collected_truncated"
                    logcat.startsWith("command=") && logcat.contains("\ncollection=incomplete\n") -> "collected_incomplete"
                    else -> "unavailable"
                }}")
                appendLine("gameEvents=${when {
                    startup.contains("unavailable") -> "unavailable"
                    startup.contains("\ncollection=incomplete\n") -> "collected_incomplete"
                    startup.contains("no_markers") -> "no_retained_events"
                    else -> "collected_bounded"
                }}")
                appendLine("systemExitHistory=${if (exits.summary.startsWith("count=")) "collected" else "unavailable"}")
                appendLine("systemExitTraces=${exits.traces.size}")
                appendLine("packageQueries=first_64_supported_calls_with_game_metadata_plus_first_8_late_errors")
                appendLine("classLookup=first_32_fallback_results_per_process")
                appendLine("memory=5_second_startup_samples_then_30_seconds_with_summary_categories")
                appendLine("gameFaultPersistence=5_second_game_pid_fixed_markers_via_container_service")
                appendLine("startupThreads=three_startup_samples_fixed_roles_and_stack_methods_only")
                appendLine("javaFaults=own_uid_exception_types_and_stack_methods_only")
                appendLine("nativeTerminationFiles=${nativeTraces.size}")
                appendLine("logPersistence=bounded_per_process_rotation")
                appendLine("signalSender=not_exposed_by_ApplicationExitInfo")
                appendLine("systemWideLogs=not_accessible_to_app")
                appendLine("missingEvidence=not_a_success_result")
            })
        }
        require(output.length() <= MAX_ARCHIVE_BYTES) {
            output.delete()
            "诊断包超过大小限制"
        }
        return output
    }

    private fun runtimeSummary(
        containerStatus: ContainerStatus,
        activeTranslation: PatchInstallSummary?
    ): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val runtime = Runtime.getRuntime()
        val files = context.filesDir
        val microgStatus = MicrogContainerConfig(context).diagnosticStatus()
        val compatibility = DeviceCompatibility(context).report()
        return buildString {
            appendLine("generatedAt=${Instant.now()}")
            appendLine("timezone=${ZoneId.systemDefault().id}")
            appendLine("appVersionName=${packageInfo.versionName}")
            appendLine("appVersionCode=$versionCode")
            appendLine("buildType=${BuildConfig.BUILD_TYPE}")
            appendLine("package=${context.packageName}")
            appendLine("uid=${Process.myUid()}")
            appendLine("androidUserId=${Process.myUid() / 100000}")
            appendLine("elapsedRealtimeMillis=${android.os.SystemClock.elapsedRealtime()}")
            appendLine("hostTargetSdk=${context.applicationInfo.targetSdkVersion}")
            appendLine("hostAppFlags=0x${Integer.toHexString(context.applicationInfo.flags)}")
            appendLine("lastUpdateTime=${Instant.ofEpochMilli(packageInfo.lastUpdateTime)}")
            appendLine("hostExceptionHandlerInstalled=${PersistentDiagnosticLog.ownsExceptionHandler()}")
            val launcher = runCatching { context.packageManager.getLaunchIntentForPackage(context.packageName) }.getOrNull()
            val entry = launcher?.component?.takeIf { it.packageName == context.packageName }
                ?.let { runCatching { context.packageManager.getActivityInfo(it, 0) }.getOrNull() }
            appendLine("launcherAvailable=${entry != null}")
            appendLine("launcherEnabled=${entry?.enabled == true}")
            appendLine("launcherExported=${entry?.exported == true}")
            appendLine("deviceManufacturer=${Build.MANUFACTURER}")
            appendLine("deviceBrand=${Build.BRAND}")
            appendLine("deviceModel=${Build.MODEL}")
            appendLine("deviceProduct=${Build.PRODUCT}")
            appendLine("deviceName=${Build.DEVICE}")
            appendLine("buildDisplay=${Build.DISPLAY}")
            appendLine("buildIncremental=${Build.VERSION.INCREMENTAL}")
            appendLine("androidSdk=${Build.VERSION.SDK_INT}")
            appendLine("androidRelease=${Build.VERSION.RELEASE}")
            val webView = runCatching { android.webkit.WebView.getCurrentWebViewPackage() }.getOrNull()
            appendLine("webViewPackage=${webView?.packageName ?: "unavailable"}")
            appendLine("webViewVersion=${webView?.versionName ?: "unavailable"}")
            appendLine("androidSecurityPatch=${Build.VERSION.SECURITY_PATCH}")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("abis64=${Build.SUPPORTED_64_BIT_ABIS.joinToString(",")}")
            appendLine("process64Bit=${compatibility.is64BitProcess}")
            appendLine("pageSizeBytes=${compatibility.pageSizeBytes}")
            appendLine("lowRamDevice=${compatibility.isLowRamDevice}")
            appendLine("ignoringBatteryOptimizations=${compatibility.isIgnoringBatteryOptimizations}")
            appendLine("compatibilitySupported=${compatibility.isSupported}")
            appendLine("compatibilityWarnings=${compatibility.warnings.joinToString("|")}")
            appendLine("runtimeMaxBytes=${runtime.maxMemory()}")
            appendLine("runtimeFreeBytes=${runtime.freeMemory()}")
            GameMemoryDiagnostics.systemSnapshot(context).split(' ').forEach { appendLine(it) }
            appendLine("appStorageFreeBytes=${files.freeSpace}")
            appendLine("translationSource=GitHub Releases")
            appendLine("containerAvailable=${containerStatus.available}")
            appendLine("gameImported=${containerStatus.gameImported}")
            appendLine("importedVersionCode=${containerStatus.importedVersionCode ?: "none"}")
            appendLine("importedSplits=${containerStatus.importedSplits}")
            appendLine("importSource=${containerStatus.importSource ?: "none"}")
            appendLine("containerMessage=${containerStatus.message}")
            appendLine("translationActive=${activeTranslation != null}")
            appendLine("translationVersion=${activeTranslation?.version ?: "none"}")
            appendLine("translationSourceFiles=${activeTranslation?.availableSourceFiles ?: 0}/${activeTranslation?.candidateSourceFiles ?: 0}")
            appendLine("translationSourceRecords=${activeTranslation?.sourceRecordCount ?: 0}")
            val indexRoot = files.toPath().resolve("translation-cache/translation-index")
            val startup = com.example.limbuszhcn.container.TranslationIndexStore(indexRoot).bootstrapManifest()
            appendLine("translationStartupAvailable=${startup != null}")
            appendLine("translationStartupRecords=${startup?.getProperty("sourceRecordCount")?.toIntOrNull() ?: 0}")
            append(com.example.limbuszhcn.container.RuntimeReceipt(indexRoot).diagnosticSummary(versionCode, packageInfo.lastUpdateTime) { pid, started ->
                val exit = SystemStartupDiagnostics.gameProcessExit(context, pid)
                val until = exit?.timestamp?.takeIf { it >= started } ?: Long.MAX_VALUE
                readGameExitCodes(context, pid, started, until)
            })
            appendLine("microgSource=${microgStatus.source}")
            appendLine("microgApks=${microgStatus.availableApkCount}/${microgStatus.configuredApkCount}")
            appendLine("microgMigrationCompleted=${microgStatus.migrationCompleted}")
            listOf("com.google.android.gms", "com.android.vending").forEach { packageName ->
                val version = runCatching {
                    val info = com.lody.virtual.client.core.VirtualCore.get()
                        .getInstalledAppInfo(packageName, 0)?.getPackageInfo(0)
                    if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode else {
                        @Suppress("DEPRECATION")
                        info?.versionCode?.toLong()
                    }
                }.getOrNull()
                appendLine("googleComponent=$packageName version=${version ?: "unavailable"}")
            }
        }.redactedAndBounded(MAX_SUMMARY_BYTES)
    }

    private fun collectLogcat(): String {
        val command = listOf(
            "logcat",
            "-d",
            "-b",
            "all",
            "-v",
            "threadtime",
            "--uid=${Process.myUid()}",
            "-t",
            MAX_LOG_LINES.toString(),
            // Upstream Google components may log billing arguments. Export only our metadata
            // and crash tags; regex redaction alone cannot safely strip arbitrary receipts.
            "LimbusZhCNApp:I", "LimbusLauncher:I", "LimbusContainer:I", "LimbusVA:I",
            "LimbusZhCN:I", "LimbusSIG:I", "GameMemory:I", "PackageQueries:I", "LimbusJNI:I", "GameExit:I", "GameFault:I", "GameStartup:I",
            "NativeEngine:I", "AndroidRuntime:E", "libc:F", "*:S"
        )
        val result = exportLogReader.read({ ProcessBuilder(command).redirectErrorStream(true).start() }) {
            boundedDiagnosticTail(it, MAX_LOGCAT_BYTES, MAX_LOG_LINES)
        }
        if (result.status !in setOf("available", "incomplete")) return "logcat collection failed: ${result.status}\n"
        return buildString {
            appendLine("command=${command.joinToString(" ")}")
            appendLine("collection=${result.status}")
            appendLine("exitCode=${result.exitCode ?: "not_observed"}")
            append(result.lines.joinToString("\n"))
        }.redactedAndBounded(MAX_LOGCAT_BYTES)
    }

    /** Export fixed engine/bind/exit events, never arbitrary SDK logs or payloads. */
    private fun collectGameStartup(): String {
        val command = listOf("logcat", "-d", "-b", "main", "-v", "epoch",
            "--uid=${Process.myUid()}", "-t", "6000", "Unity:I", "LimbusVA:I", "AppSealing:E", "*:S")
        val result = startupLogReader.read({ ProcessBuilder(command).redirectErrorStream(true).start() }) { lines ->
            lines.take(6000).mapNotNull(::safeGameTimelineMarker).toList().takeLast(256)
        }
        if (result.status !in setOf("available", "incomplete")) return "startup_markers_unavailable status=${result.status}\n"
        return "scope=own-uid; fixed events only; correlate time and pid; missing events do not prove success\n" +
            "collection=${result.status}\n" + result.lines.joinToString("\n").ifEmpty { "no_markers" } + "\n"
    }

    private fun collectJavaFaults(): String {
        // Chromium may describe and clear a Java exception before its native fatal handler runs.
        // Keep only typed evidence; login URLs, consent data and SDK payloads never leave the parser.
        val command = listOf("logcat", "-d", "-b", "main", "-b", "crash", "-v", "epoch",
            "--uid=${Process.myUid()}", "-t", "6000", "chromium:E", "cr_JniAndroid:E",
            "System.err:W", "AndroidRuntime:E", "*:S")
        val result = javaFaultLogReader.read({ ProcessBuilder(command).redirectErrorStream(true).start() }) { lines ->
            lines.take(6000).mapNotNull(::safeJavaFaultMarker).toList().takeLast(512)
        }
        return "scope=own-uid; types, fixed categories and methods only; correlate pid and time\n" +
            "collection=${result.status}\nexitCode=${result.exitCode ?: "not_observed"}\n" +
            result.lines.joinToString("\n").ifEmpty { "no_retained_markers" } + "\n"
    }

    private fun collectExitReasons(): ExitDiagnostics {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ExitDiagnostics(
                summary = "unavailable: historical process exit reasons require Android 11 or newer\n",
                traces = emptyList()
            )
        }
        val manager = context.getSystemService(ActivityManager::class.java)
        val reasons = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_REASONS)
        }.getOrElse { error ->
            return ExitDiagnostics(
                summary = "exit reason collection failed: ${error.javaClass.simpleName}: ${error.message}"
                    .redactedAndBounded(MAX_SUMMARY_BYTES),
                traces = emptyList()
            )
        }
        val traces = mutableListOf<ExitTrace>()
        val summary = buildString {
            appendLine("count=${reasons.size}")
            reasons.forEachIndexed { index, reason ->
                appendLine()
                appendLine("[$index]")
                appendLine("timestamp=${Instant.ofEpochMilli(reason.timestamp)}")
                appendLine("pid=${reason.pid}")
                appendLine("process=${reason.processName}")
                appendLine("reason=${exitReasonName(reason.reason)}(${reason.reason})")
                appendLine("status=${reason.status}")
                appendLine("checkpoint=${SystemStartupDiagnostics.exitCheckpoint(reason.processStateSummary)}")
                val code = SystemStartupDiagnostics.exitVersionCode(reason.processStateSummary)
                appendLine("appVersionCode=${code ?: "unavailable"}")
                appendLine("buildRelation=" + when (code) {
                    null -> "unknown"
                    BuildConfig.VERSION_CODE.toLong() -> "same_build"
                    else -> "different_build"
                })
                appendLine("importance=${reason.importance}")
                appendLine("pssKb=${reason.pss}")
                appendLine("rssKb=${reason.rss}")
                appendLine("description=${reason.description.orEmpty()}")
                if (traces.size < MAX_EXIT_TRACES) {
                    val trace = runCatching {
                        reason.traceInputStream?.use { trace ->
                            trace.readBinaryAtMost(MAX_EXIT_TRACE_BYTES)
                        }
                    }.getOrNull()
                    if (trace?.exceededLimit == true) {
                        // 截断 protobuf 会得到无法解析的假栈，因此超限时只在摘要中说明。
                        appendLine("traceSkipped=exceeds-${MAX_EXIT_TRACE_BYTES}-bytes")
                    } else if (trace != null && trace.bytes.isNotEmpty()) {
                        traces += ExitTrace(trace.bytes.redactedBinaryCopy())
                        appendLine("traceFile=exit-trace-${traces.size}.pb")
                    }
                }
            }
        }.redactedAndBounded(MAX_EXIT_SUMMARY_BYTES)
        return ExitDiagnostics(summary, traces)
    }

    private fun ZipOutputStream.writeText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /**
     * 将已经完成等长脱敏的二进制诊断内容写入 ZIP。
     *
     * @param name ZIP 内的条目名称
     * @param bytes 保持 protobuf 字段边界的二进制内容
     */
    private fun ZipOutputStream.writeBytes(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    /**
     * 生成用户可直接复制到 GitHub Issue 的结构化说明。
     *
     * @param containerStatus 当前容器状态。
     * @param activeTranslation 当前激活的汉化版本。
     * @return 不包含账号与本地路径的 Markdown 模板。
     */
    private fun issueTemplate(
        containerStatus: ContainerStatus,
        activeTranslation: PatchInstallSummary?
    ): String {
        val compatibility = DeviceCompatibility(context).report()
        return """
            ## 问题现象

            <!-- 请说明是无法安装、打开即闪退、同步失败、启动游戏失败、黑屏，还是汉化显示异常。 -->

            ## 复现步骤

            1.
            2.
            3.

            ## 设备信息

            - 机型：${compatibility.deviceLabel}
            - 系统：${compatibility.androidLabel}
            - ABI：${compatibility.abiLabel}
            - 内存页：${compatibility.pageSizeBytes} bytes
            - 容器状态：${containerStatus.message}
            - 游戏版本：${containerStatus.importedVersionCode ?: "未导入"}
            - 汉化版本：${activeTranslation?.version ?: "未激活"}
            - 兼容提醒：${compatibility.recommendation}

            ## 补充说明

            <!-- 请把本 ZIP 一并附加到 Issue。公开上传前仍建议检查是否包含不希望公开的信息。 -->
        """.trimIndent() + "\n"
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = limit
        var truncated = false
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            val retained = minOf(count, remaining)
            if (retained > 0) {
                output.write(buffer, 0, retained)
                remaining -= retained
            }
            if (retained < count) truncated = true
        }
        if (truncated) {
            output.write("\n[truncated]\n".toByteArray(Charsets.UTF_8))
        }
        return output.toByteArray()
    }

    /**
     * 在不截断二进制格式的前提下读取单项诊断内容。
     *
     * @param limit 允许写入诊断包的最大字节数
     * @return 完整数据及是否超过上限；超限时返回的数据不会被写入 ZIP
     */
    private fun InputStream.readBinaryAtMost(limit: Int): BoundedBinary {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) {
                return BoundedBinary(output.toByteArray(), exceededLimit = false)
            }
            if (output.size() + count > limit) {
                return BoundedBinary(ByteArray(0), exceededLimit = true)
            }
            output.write(buffer, 0, count)
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "REASON_$reason"
    }

    private data class ExitDiagnostics(val summary: String, val traces: List<ExitTrace>)

    private data class ExitTrace(val bytes: ByteArray)

    private data class BoundedBinary(val bytes: ByteArray, val exceededLimit: Boolean)

    companion object {
        // Shared across exporter instances so a stuck child cannot accumulate threads on retries.
        private val exportLogReader = BoundedDiagnosticRead()
        private val startupLogReader = BoundedDiagnosticRead()
        private val javaFaultLogReader = BoundedDiagnosticRead()
        private const val FILE_PREFIX = "limbus-diagnostics-"
        private const val MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L
        private const val MAX_LOGCAT_BYTES = 3 * 1024 * 1024
        private const val MAX_PERSISTENT_LOG_BYTES = 768 * 1024
        private const val MAX_PERSISTENT_LOG_FILES = 12
        private const val MAX_SUMMARY_BYTES = 64 * 1024
        private const val MAX_EXIT_SUMMARY_BYTES = 128 * 1024
        private const val MAX_EXIT_TRACE_BYTES = 512 * 1024
        private const val MAX_LOG_LINES = 6000
        private const val MAX_EXIT_REASONS = 16
        private const val MAX_EXIT_TRACES = 3
        private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val README = """
            Limbus Company 汉化器 Issue 诊断包

            内容：可复制的 Issue 模板、设备兼容信息、应用内滚动日志、本应用 UID 的近期日志、历史进程退出原因，以及等长脱敏的原生 tombstone protobuf。
            不包含：游戏资源、存档、PlayerPrefs、汉化渠道地址、Token 或账号配置。
            文本已经过自动脱敏，但公开上传前仍建议自行检查内容。
        """.trimIndent() + "\n"
    }
}

/**
 * 对 protobuf 等二进制内容中的可打印 ASCII 凭据执行等长覆盖。
 *
 * <p>只扫描连续可打印 ASCII 区间，避免正则跨过 protobuf 的字段键或长度字节；替换后的
 * 字节数与原始内容完全一致，因此地址、varint 和嵌套消息边界仍可被标准工具解析。</p>
 *
 * @return 保持原长度且已覆盖常见凭据、邮箱和 JWT 的新字节数组
 */
internal fun ByteArray.redactedBinaryCopy(): ByteArray {
    val result = copyOf()
    var start = 0
    while (start < result.size) {
        while (start < result.size && result[start].toInt() !in PRINTABLE_ASCII_RANGE) {
            start++
        }
        var end = start
        while (end < result.size && result[end].toInt() in PRINTABLE_ASCII_RANGE) {
            end++
        }
        if (end > start) {
            val segment = String(result, start, end - start, Charsets.US_ASCII)
            BINARY_SENSITIVE_PATTERNS.forEach { pattern ->
                pattern.findAll(segment).forEach { match ->
                    for (relativeIndex in match.range) {
                        result[start + relativeIndex] = REDACTED_BINARY_BYTE
                    }
                }
            }
        }
        start = if (end == start) start + 1 else end
    }
    return result
}

internal fun String.redactedAndBounded(maxBytes: Int): String {
    var value = this
    value = URL_CREDENTIAL.replace(value) { match -> "${match.groupValues[1]}<redacted>@" }
    value = CREDENTIAL_FIELD.replace(value) { match -> "${match.groupValues[1]}<redacted>" }
    value = FIREBASE_USER.replace(value) { match ->
        "${match.groupValues[1]}<redacted-account>${match.groupValues[2]}"
    }
    value = JWT_VALUE.replace(value, "<redacted-jwt>")
    value = API_KEY_VALUE.replace(value, "<redacted-api-key>")
    value = EMAIL_ADDRESS.replace(value, "<redacted-email>")
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return value
    val suffix = bytes.copyOfRange(bytes.size - maxBytes, bytes.size)
    return "[truncated to recent data]\n" + String(suffix, Charsets.UTF_8)
}

internal fun diagnosticTail(file: File, limit: Int): String = runCatching {
    java.io.RandomAccessFile(file, "r").use { input ->
        val size = input.length()
        val offset = maxOf(0L, size - limit)
        input.seek(offset)
        val bytes = ByteArray(minOf(size, limit.toLong()).toInt())
        input.readFully(bytes)
        val text = String(bytes, Charsets.UTF_8)
        if (offset == 0L) text else "[truncated to recent data]\n" + text.substringAfter('\n', "")
    }
}.getOrElse { "unavailable: ${it.javaClass.simpleName}" }

private val GAME_EXIT_LOG = Regex("""(\d{1,11})\.(\d{3,9})\s+(\d+)\s+\d+\s+[EF]\s+AppSealing\s*:\s*[! ]*Kill\s+Process[. ]*\[([1-9]\d{4})\]""")

private val GAME_STARTUP_LOG = Regex("""(\d{1,11}\.\d{3,9})\s+(\d{1,10})\s+\d+\s+[VDIWEF]\s+Unity\s*:\s*(.{0,8192})""")

internal fun safeGameStartupMarker(line: String): String? {
    val match = GAME_STARTUP_LOG.matchEntire(line.trim()) ?: return null
    val message = match.groupValues[3].trim()
    val category = when {
        message in listOf("PlayVideo", "PlayVideo()") -> "intro_video"
        message in listOf("PlayWarningAnim", "PlayWarningAnim()") -> "warning_animation"
        message in listOf("PlayDone", "PlayDone()") -> "intro_complete"
        message.contains("Not enough storage space to install required resources", true) -> "resource_install_failed"
        message.contains("RuntimeData is null", true) -> "addressables_runtime_data_missing"
        message.contains("Failed to load PlayerSettings", true) -> "player_settings_missing"
        message.contains("Unable to initialize the Unity Engine", true) -> "engine_initialization_failed"
        message.contains("Failed to extract resources", true) -> "resource_extraction_failed"
        message.contains("Unable to load", true) && message.contains("global-metadata.dat", true) -> "il2cpp_metadata_unreadable"
        message.contains("Curl error", true) -> "network_request_failed"
        message.contains("FMOD", true) && message.contains("error", true) -> "audio_resource_failed"
        else -> return null
    }
    // Only a numeric timestamp, PID and our category can leave this parser.
    return "time=${match.groupValues[1]} pid=${match.groupValues[2]} event=$category"
}

private const val EARLY_GAME_EXIT_MS = 30_000L
private const val GAME_BIND_EVENT = "VClient.bindApplication before makeApplication"
private val GAME_BIND_LOG = Regex("""(\d{1,11})\.(\d{3,9})\s+(\d+)\s+\d+\s+I\s+LimbusVA\s*:\s*VClient\.bindApplication before makeApplication package=(\S+) process=(\S+) info=.*""")

/** This timeline may span launches; it does not assign a helper's error to the main game. */
internal fun safeGameTimelineMarker(line: String): String? {
    safeGameStartupMarker(line)?.let { return it }
    val value = line.trim()
    GAME_EXIT_LOG.matchEntire(value)?.let { match ->
        val pid = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return "time=${match.groupValues[1]}.${match.groupValues[2]} pid=$pid event=game_reported_exit code=${match.groupValues[4]}"
    }
    val match = GAME_BIND_LOG.matchEntire(value) ?: return null
    val pid = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val game = LocalContainerRuntime.GAME_PACKAGE
    if (match.groupValues[4] != game) return null
    val role = when {
        match.groupValues[5] == game -> "main"
        match.groupValues[5].startsWith("$game:") -> "helper"
        else -> return null
    }
    return "time=${match.groupValues[1]}.${match.groupValues[2]} pid=$pid event=game_process_bound role=$role"
}

internal fun readGameExitCodes(context: Context, pid: Int, started: Long, until: Long): List<String> {
    val direct = readGameLog(started, listOf("--pid=$pid", "AppSealing:E")) { lines ->
        exitCodesWithEvents(lines.take(256).toList(), pid, started, until, RetainedExitEvidence::record)
    }
    if (direct.isNotEmpty() || until - started !in 0..EARLY_GAME_EXIT_MS) return direct
    return readGameLog(started, listOf("LimbusVA:I", "AppSealing:E")) { lines ->
        launchGameExitCodes(lines, pid, started, until, RetainedExitEvidence::record) {
            SystemStartupDiagnostics.gameSlotExitTime(context, it)
        }
    }
}

private val gameLogReader = BoundedDiagnosticRead()

internal fun readGameLog(started: Long, filters: List<String>, onStatus: (String) -> Unit = {},
    parse: (Sequence<String>) -> List<String>): List<String> {
    val since = "${started / 1000}.${(started % 1000).toString().padStart(3, '0')}"
    val args = listOf("logcat", "-d", "-b", "main", "-v", "epoch", "-T", since, "--uid=${Process.myUid()}") + filters + "*:S"
    val result = gameLogReader.read({ ProcessBuilder(args).redirectErrorStream(true).start() }, parse)
    onStatus(result.status)
    return result.lines
}

/** Android can recreate a game activity under a new PID before it returns to BYBUS. */
internal fun launchGameExitCodes(
    lines: Sequence<String>, pid: Int, started: Long, until: Long,
    onEvent: (String) -> Unit = {}, exitTime: (Int) -> Long?
): List<String> {
    val logs = lines.take(4096).filter { GAME_BIND_EVENT in it || "AppSealing" in it }.take(256).toList()
    val direct = exitCodesWithEvents(logs, pid, started, until, onEvent)
    if (direct.isNotEmpty() || until - started !in 0..EARLY_GAME_EXIT_MS) return direct

    val bindings = logs.mapNotNull { GAME_BIND_LOG.matchEntire(it.trim()) }
        .filter { logTimeMillis(it) in started..until }
        .groupBy { it.groupValues[3].toIntOrNull() }
    val codes = linkedSetOf<String>()
    val game = LocalContainerRuntime.GAME_PACKAGE
    for ((priorPid, records) in bindings) {
        if (priorPid == null || priorPid <= 0 || priorPid == pid) continue
        // A reused PID or a helper process cannot establish ownership of a game error.
        val binding = records.singleOrNull() ?: continue
        if (binding.groupValues[4] != game || binding.groupValues[5] != game) continue
        val boundAt = logTimeMillis(binding)
        if (logs.none { gameReportedExitCode(it, priorPid, boundAt, until) != null }) continue
        val endedAt = exitTime(priorPid)?.takeIf { it in boundAt..until } ?: continue
        codes.addAll(exitCodesWithEvents(logs, priorPid, boundAt, endedAt, onEvent))
        if (codes.size >= 8) break
    }
    return codes.take(8)
}

private fun exitCodesWithEvents(logs: List<String>, pid: Int, started: Long, until: Long, onEvent: (String) -> Unit): List<String> =
    logs.mapNotNull { line ->
        val code = gameReportedExitCode(line, pid, started, until) ?: return@mapNotNull null
        safeGameTimelineMarker(line)?.let { runCatching { onEvent(it) } }
        code
    }.distinct().take(8)

/** Retain the original event time before logcat rotates; repeated status reads do not spam disk. */
private object RetainedExitEvidence {
    private val seen = linkedSetOf<String>()
    fun record(event: String) {
        val fresh = synchronized(seen) {
            if (event in seen) false else {
                if (seen.size >= 32) seen.remove(seen.first())
                seen.add(event)
            }
        }
        if (fresh) PersistentDiagnosticLog.info("GameExit", event)
    }
}

private fun logTimeMillis(match: MatchResult): Long =
    match.groupValues[1].toLong() * 1000 + match.groupValues[2].take(3).toLong()

/** Only the game's numeric code is exported, bound to the time and PID of this launch. */
internal fun gameReportedExitCode(line: String, pid: Int, started: Long, until: Long = Long.MAX_VALUE): String? {
    val match = GAME_EXIT_LOG.matchEntire(line.trim()) ?: return null
    if (match.groupValues[3].toIntOrNull() != pid) return null
    val time = logTimeMillis(match)
    return match.groupValues[4].takeIf { time in started..until }
}

private val URL_CREDENTIAL = Regex("(https?://)[^\\s/@:]+(?::[^\\s/@]*)?@", RegexOption.IGNORE_CASE)
private val API_KEY_VALUE = Regex("(?<![A-Za-z0-9_-])sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{16,}")
private val CREDENTIAL_FIELD = Regex(
    "([\\\"']?(?:authorization|access[_-]?token|refresh[_-]?token|token|api[_-]?key|secret|password)[\\\"']?\\s*[:=]\\s*)(?:bearer\\s+)?(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&]+)",
    RegexOption.IGNORE_CASE
)
private val JWT_VALUE = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}(?![A-Za-z0-9_-])")
private val EMAIL_ADDRESS = Regex("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?![A-Za-z0-9.-])")
private val FIREBASE_USER = Regex("(about\\s+user\\s*\\(\\s*)[^)\\r\\n]+(\\s*\\))", RegexOption.IGNORE_CASE)
private val PRINTABLE_ASCII_RANGE = 0x20..0x7e
private const val REDACTED_BINARY_BYTE: Byte = 0x2a
private val BINARY_SENSITIVE_PATTERNS = listOf(
    URL_CREDENTIAL,
    CREDENTIAL_FIELD,
    FIREBASE_USER,
    JWT_VALUE,
    API_KEY_VALUE,
    EMAIL_ADDRESS
)
