package com.example.limbuszhcn

import android.content.Context
import android.os.Bundle
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.ProviderCall
import com.lody.virtual.server.BinderProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Fixed game-PID and same-UID authorization categories, collected outside Unity. */
internal class GameFaultDiagnostics(
    context: Context,
    private val readSnapshot: (Long) -> Bundle? = ::requestGameFaultSnapshot
) {
    private var since = System.currentTimeMillis() - 1_000
    private var collectionStatus = ""
    private val seen = linkedSetOf<String>()
    private var modules = ""
    private var nextModulesAt = 0L

    init { pruneNativeTerminationTraces(context) }

    fun sample() {
        val now = System.currentTimeMillis()
        // The service process has no guest IO hooks. Never fork a logcat child in Unity.
        val snapshot = runCatching { readSnapshot(since) }.getOrNull()
        val status = snapshot?.getString("status") ?: "unavailable"
        if (status != collectionStatus) {
            collectionStatus = status
            PersistentDiagnosticLog.info("GameFault", "capture=$status intervalSeconds=5 scope=game_pid_and_same_uid_auth collector=container_service")
        }
        val events = snapshot?.getStringArrayList("markers").orEmpty()
        // A failed read must not discard its time window. Bound recovery to a
        // minute, so an unavailable log service cannot grow the next read forever.
        since = maxOf(since, now - if (collectionStatus == "available") 1_000 else 60_000)
        for (event in events) if (seen.add(event)) {
            while (seen.size > 256) seen.remove(seen.first())
            PersistentDiagnosticLog.info("GameFault", event)
        }
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (elapsed < nextModulesAt) return
        nextModulesAt = elapsed + 60_000
        val current = nativeModules()
        if (current.isNotEmpty() && current != modules) {
            modules = current
            PersistentDiagnosticLog.info("GameFault", "nativeModules=$current")
        }
    }

    private fun nativeModules(): String = runCatching {
        val allowed = setOf("libil2cpp.so", "libunity.so", "libcovault-appsec.so", "libv++_64.so", "libc.so")
        File("/proc/self/maps").bufferedReader().useLines { lines ->
            lines.take(32768).mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 6)
                if (parts.size != 6 || 'x' !in parts[1]) return@mapNotNull null
                val name = parts[5].substringAfterLast('/')
                if (name !in allowed || !parts[0].matches(Regex("[0-9a-f]+-[0-9a-f]+")) ||
                    !parts[2].matches(Regex("[0-9a-f]+"))) return@mapNotNull null
                "$name@${parts[0]}+${parts[2]}"
            }.take(24).joinToString(",")
        }
    }.getOrDefault("")
}

internal fun requestGameFaultSnapshot(sinceMillis: Long): Bundle? =
    ProviderCall.call(VirtualCore.getConfig().binderProviderAuthority,
        VirtualCore.get().context, BinderProvider.GAME_FAULT_MARKERS, null,
        Bundle().apply { putLong("since", sinceMillis) }, 0)

/** Called only in the unhooked service process, after Binder caller verification. */
internal fun collectGameFaultMarkers(pid: Int, sinceMillis: Long): Bundle {
    var status = "unavailable"
    // microG callbacks run in another process of our UID. A game-PID filter
    // discarded the authorize result while retaining only the bind request.
    val events = readGameLog(sinceMillis, listOf("Unity:I", "AppSealing:E", "LimbusIntegrity:W",
        "AuthorizationService:D", "IdentitySignInService:D", "AuthSignInActivity:D",
        "AssistedSignInActivity:D", "AssistedSignInFragment:D", "LimbusVA:I"),
        onStatus = { status = it }) { lines ->
        lines.take(2048).mapNotNull { line ->
            safeGoogleAuthMarker(line) ?: safeGameFaultMarker(line)?.takeIf { it.contains(" pid=$pid ") }
        }.toList().takeLast(128)
    }
    return Bundle().apply {
        putString("status", status)
        putStringArrayList("markers", ArrayList(events))
    }
}

private val GOOGLE_AUTH_EVENT = Regex("""(\d{1,11}\.\d{3,9})\s+(\d{1,10})\s+\d+\s+[VDIWEF]\s+(AuthorizationService|IdentitySignInService|AuthSignInActivity|AssistedSignInActivity|AssistedSignInFragment|LimbusVA)\s*:\s*(.{0,8192})""")

internal fun safeGoogleAuthMarker(line: String): String? {
    val match = GOOGLE_AUTH_EVENT.matchEntire(line.trim()) ?: return null
    val message = match.groupValues[4]
    val component = match.groupValues[3]
    // Select stages before exporting; never retain a request, result Bundle,
    // status message, account name, credential or authentication URL.
    val stage = when (component) {
        "LimbusVA" -> when {
            message.startsWith("Stored IntentSender result bridge") -> "result_route_saved"
            message.startsWith("Dispatch filled Google Identity UI through VAMS") -> "identity_ui_started"
            message.startsWith("Relay Google Identity UI result resultCode=-1 ") -> "identity_ui_returned"
            message.startsWith("Relay Google Identity UI result resultCode=0 ") -> "identity_ui_cancelled"
            else -> return null
        }
        "AssistedSignInActivity" -> when {
            message.startsWith("onCreate: clientPackageName:") -> "identity_ui_created"
            message.startsWith("errorResult:") -> "identity_ui_failed"
            message.startsWith("loginResult: googleSignInAccount:") -> "identity_result_prepared"
            else -> return null
        }
        "AssistedSignInFragment" -> when {
            message.startsWith("checkAccountAuthStatus:") && message.contains("auth error") -> "account_check_failed"
            message == "startLogin: error" -> "sign_in_failed"
            message == "cancelLogin" -> "sign_in_cancelled"
            else -> return null
        }
        "AuthSignInActivity" -> if (message.startsWith("Result:")) "account_ui_returned" else return null
        else -> when {
        message.startsWith("handleServiceRequest start") -> "service_request"
        message.startsWith("authorize called") -> "authorize_requested"
        message.startsWith("authorize resolved: pendingIntent") -> "consent_required"
        message.startsWith("authorize resolved: silent") -> "authorize_completed"
        message.startsWith("authorize: invalid account") -> "account_unavailable"
        message.startsWith("authorize failed, falling back to PendingIntent") -> "authorize_retry_consent"
        message.startsWith("verifyWithGoogle failed") -> "verification_failed"
        message.startsWith("method 'beginSignIn' called") -> "begin_sign_in"
        message.startsWith("method 'getSignInIntent' called") -> "get_sign_in_intent"
        else -> return null
        }
    }
    return "time=${match.groupValues[1]} pid=${match.groupValues[2]} event=google_auth stage=$stage component=${match.groupValues[3]}"
}

internal fun pruneNativeTerminationTraces(context: Context) {
    val root = File(context.filesDir, "translation-cache/translation-index")
    root.listFiles().orEmpty().filter {
        it.name.matches(Regex("termination-[0-9]{1,10}\\.log")) &&
            Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)
    }.sortedByDescending(File::lastModified).drop(6).forEach { it.delete() }
}

private val UNITY_FAULT = Regex("""(\d{1,11}\.\d{3,9})\s+(\d{1,10})\s+\d+\s+[WEF]\s+Unity\s*:\s*(.{0,8192})""")
private val EXCEPTIONS = listOf("NullReferenceException", "IndexOutOfRangeException", "ArgumentOutOfRangeException",
    "OutOfMemoryException", "InvalidOperationException", "MissingReferenceException", "TypeLoadException",
    "FormatException", "StackOverflowException", "DllNotFoundException", "ExecutionEngineException",
    "ArgumentException", "ArgumentNullException", "InvalidCastException", "MissingMethodException",
    "NotSupportedException", "NotImplementedException", "ObjectDisposedException", "KeyNotFoundException")
// Method identities only. A narrow class-prefix list discarded game UI callbacks
// between the exception and EventSystems, leaving just an apparent engine fault.
private val GAME_FRAME = Regex("""(?:at\s+)?([A-Za-z_][A-Za-z0-9_.$+`<>]{0,159}\.[A-Za-z_<][A-Za-z0-9_.$+`<>]{0,100})\s*\(.*""")
private val REPORT_SITE = Regex("""(\d{1,11}\.\d{3,9})\s+(\d{1,10})\s+\d+\s+W\s+LimbusIntegrity\s*:\s*report code=([1-9][0-9]{4}) callerOffset=([0-9a-f]{1,8}) forwarded=1""")
private val REPORT_CONTEXT = Regex("""(\d{1,11}\.\d{3,9})\s+(\d{1,10})\s+\d+\s+W\s+LimbusIntegrity\s*:\s*report_context site=94e90 flags=([0-9a-f]{8}) probeCode=(\d{1,10}) forwarded=1""")

internal fun safeGameReportMarker(line: String): String? =
    REPORT_SITE.matchEntire(line.trim())?.let {
        "time=${it.groupValues[1]} pid=${it.groupValues[2]} event=game_report_site code=${it.groupValues[3]} callerOffset=${it.groupValues[4]} forwarded=1"
    } ?: REPORT_CONTEXT.matchEntire(line.trim())?.let {
        "time=${it.groupValues[1]} pid=${it.groupValues[2]} event=game_report_context site=94e90 flags=${it.groupValues[3]} probeCode=${it.groupValues[4]} forwarded=1"
    }

internal fun safeGameFaultMarker(line: String): String? {
    safeGameTimelineMarker(line)?.let { return it }
    val match = UNITY_FAULT.matchEntire(line.trim()) ?: return null
    val message = match.groupValues[3].trim()
    val category = EXCEPTIONS.firstOrNull { message.startsWith(it + ":") || message == it }
        ?.let { "managed_exception type=$it" }
        ?: when {
            message.contains("Failed to load AssetBundle", true) -> "asset_bundle_load_failed"
            message.contains("Could not allocate memory", true) || message.contains("out of memory", true) -> "allocation_failed"
            message.contains("VK_ERROR_DEVICE_LOST") -> "graphics_device_lost"
            message.contains("VK_ERROR_OUT_OF_DEVICE_MEMORY") -> "graphics_allocation_failed"
            else -> GAME_FRAME.matchEntire(message)?.groupValues?.get(1)?.let { "managed_frame method=$it" }
        } ?: return null
    return "time=${match.groupValues[1]} pid=${match.groupValues[2]} event=$category"
}
