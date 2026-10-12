package com.example.limbuszhcn

/** Small phase records, without paths, resource text or account information. */
internal inline fun <T> measureStartup(phase: String, action: () -> T): T {
    val started = System.nanoTime()
    var succeeded = false
    try {
        return action().also { succeeded = true }
    } finally {
        runCatching { PersistentDiagnosticLog.info("LimbusStartup",
            "phase=$phase elapsedMs=${(System.nanoTime() - started) / 1_000_000} success=$succeeded") }
    }
}
