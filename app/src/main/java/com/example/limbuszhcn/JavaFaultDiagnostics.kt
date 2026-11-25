package com.example.limbuszhcn

/** Do not export arbitrary browser/SDK messages: they can contain account and authorization data. */
private val JAVA_FAULT_LINE = Regex("""(\d{1,11}\.\d{3,9})\s+(\d{1,10})\s+\d{1,10}\s+[WEF]\s+(?:chromium|cr_JniAndroid|System\.err|AndroidRuntime)\s*:\s*(.{0,8192})""")
private val CHROMIUM_PREFIX = Regex("""^\[(?:ERROR|FATAL):[A-Za-z0-9_./-]{1,120}\(\d{1,6}\)\]\s*""")
private const val JAVA_PACKAGE = "(?:java|javax|kotlin|android|androidx|com\\.android|com\\.lody\\.virtual|com\\.example\\.limbuszhcn|com\\.google\\.android|org\\.chromium|org\\.microg|WV)\\."
private val JAVA_EXCEPTION = Regex("^(?:Caused by: |Suppressed: )?($JAVA_PACKAGE[A-Za-z0-9_.$]{1,220}(?:Exception|Error))(?::.*)?$")
private val PACKAGE_UID_REJECTION = Regex("""\bPackage [A-Za-z0-9_.]{1,240} does not belong to (?:uid )?[0-9]{1,10}\b""", RegexOption.IGNORE_CASE)
private val JAVA_FRAME = Regex("^at ($JAVA_PACKAGE[A-Za-z0-9_.$]{1,240})\\((?:Native Method|Unknown Source(?::[0-9]{1,7})?|SourceFile(?::[0-9]{1,7})?|[A-Za-z0-9_.$-]{1,100}\\.(?:java|kt)(?::[0-9]{1,7})?|chromium-[A-Za-z0-9_.-]{1,120}\\.apk-[A-Za-z0-9_.-]{1,80}:[0-9]{1,7})\\)$")

internal fun safeJavaFaultMarker(line: String): String? {
    val match = JAVA_FAULT_LINE.matchEntire(line.trim()) ?: return null
    val pid = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val message = CHROMIUM_PREFIX.replaceFirst(match.groupValues[3].trim(), "")
    val event = JAVA_EXCEPTION.matchEntire(message)?.let {
        val detail = when {
            "does not belong to uid" in message || "does not match calling uid" in message ||
                PACKAGE_UID_REJECTION.containsMatchIn(message) -> "package_uid_mismatch"
            "RECEIVER_EXPORTED" in message || "RECEIVER_NOT_EXPORTED" in message -> "receiver_export_flags"
            "BIND_EXTERNAL_SERVICE" in message -> "external_service_binding"
            "INTERNET permission" in message || "android.permission.INTERNET" in message -> "internet_permission"
            "Permission Denial" in message || "Permission denied" in message -> "permission_denied"
            "WebView data directory" in message || "same data directory" in message -> "webview_data_directory"
            else -> "unspecified"
        }
        "java_exception type=${it.groupValues[1]} category=$detail"
    } ?: JAVA_FRAME.matchEntire(message)?.let { "java_frame method=${it.groupValues[1]}" }
        ?: if (message == "Please include Java exception stack in crash report") "chromium_jni_fatal" else return null
    return "time=${match.groupValues[1]} pid=$pid event=$event"
}

/** Keep a bounded tail without accumulating all 6000 logcat lines before applying the byte limit. */
internal fun boundedDiagnosticTail(lines: Sequence<String>, maxBytes: Int, maxLines: Int): List<String> {
    require(maxBytes > 0 && maxLines > 0)
    val tail = java.util.ArrayDeque<Pair<String, Int>>()
    var bytes = 0
    var truncated = false
    for (line in lines.take(maxLines)) {
        val safe = if (line.length > 16_384) "[oversized diagnostic line omitted]" else line
        val size = safe.toByteArray(Charsets.UTF_8).size + 1
        if (size > maxBytes) { truncated = true; continue }
        while (bytes + size > maxBytes && tail.isNotEmpty()) {
            bytes -= tail.removeFirst().second
            truncated = true
        }
        tail.addLast(safe to size)
        bytes += size
    }
    return buildList {
        if (truncated) add("[truncated to recent data]")
        tail.forEach { add(it.first) }
    }
}
