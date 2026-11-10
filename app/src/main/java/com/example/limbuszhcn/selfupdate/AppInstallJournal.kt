package com.example.limbuszhcn.selfupdate

import com.example.limbuszhcn.storage.AtomicFiles
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Properties

/** A single bounded receipt, not a queue or an instruction to resume an installation. */
internal class AppInstallJournal(private val path: Path) {
    enum class Phase { PREPARED, DISPATCHED, RETURNED, DISPATCH_FAILED }
    data class Record(val fromCode: Long, val targetCode: Long, val startedAt: Long,
        val phase: Phase, val phaseAt: Long, val observedCode: Long = 0,
        val observedAt: Long = 0, val firstHomeFrameAt: Long = 0) {
        val outcome: String get() = when {
            observedCode == 0L -> "unknown"
            observedCode < targetCode -> "not-updated"
            observedCode == targetCode -> "installed"
            else -> "superseded"
        }
    }

    fun prepare(fromCode: Long, targetCode: Long, now: Long) = synchronized(lock) {
        require(fromCode > 0 && targetCode > fromCode && now > 0)
        write(Record(fromCode, targetCode, now, Phase.PREPARED, now))
    }

    fun phase(phase: Phase, now: Long) = synchronized(lock) {
        require(now > 0)
        read()?.let { record ->
            // Late callbacks from an old installer must not rewrite a completed receipt.
            if (record.observedCode < record.targetCode) write(record.copy(phase = phase, phaseAt = now))
        }
    }

    fun observe(installedCode: Long, now: Long, homeFrame: Boolean = false) = synchronized(lock) {
        require(installedCode > 0 && now > 0)
        read()?.let { record ->
            val firstFrame = if (homeFrame && installedCode >= record.targetCode && record.firstHomeFrameAt == 0L)
                now else record.firstHomeFrameAt
            if (record.observedCode != installedCode || firstFrame != record.firstHomeFrameAt) {
                write(record.copy(observedCode = installedCode, observedAt = now, firstHomeFrameAt = firstFrame))
            }
        }
    }

    fun read(): Record? = synchronized(lock) {
        // Corrupt or future receipts cannot break startup and are never replayed.
        runCatching {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) !in 1..4096) return null
            val p = AtomicFiles.readProperties(path)
            require(p.getProperty("schema") == "1")
            fun number(key: String) = requireNotNull(p.getProperty(key)?.toLongOrNull()).also { require(it >= 0) }
            Record(number("fromCode"), number("targetCode"), number("startedAt"),
                Phase.valueOf(p.getProperty("phase")), number("phaseAt"), number("observedCode"),
                number("observedAt"), number("firstHomeFrameAt")).also {
                require(it.fromCode > 0 && it.targetCode > it.fromCode && it.startedAt > 0 && it.phaseAt > 0)
                require((it.observedCode == 0L) == (it.observedAt == 0L))
                require(it.firstHomeFrameAt == 0L || it.observedCode >= it.targetCode)
            }
        }.getOrNull()
    }

    fun summary(): String = read()?.let { r ->
        "source=in-app\nfromCode=${r.fromCode}\ntargetCode=${r.targetCode}\nstartedAtMillis=${r.startedAt}\n" +
            "phase=${r.phase}\nphaseAtMillis=${r.phaseAt}\nobservedCode=${r.observedCode}\n" +
            "observedAtMillis=${r.observedAt}\noutcome=${r.outcome}\nfirstHomeFrameAtMillis=${r.firstHomeFrameAt}\n" +
            "openSource=unknown; home frame does not identify installer versus desktop launch\n"
    } ?: "unavailable: no valid in-app install receipt; external installations have no receipt\n"

    private fun write(record: Record) {
        AtomicFiles.properties(path, Properties().apply {
            setProperty("schema", "1")
            setProperty("fromCode", record.fromCode.toString())
            setProperty("targetCode", record.targetCode.toString())
            setProperty("startedAt", record.startedAt.toString())
            setProperty("phase", record.phase.name)
            setProperty("phaseAt", record.phaseAt.toString())
            setProperty("observedCode", record.observedCode.toString())
            setProperty("observedAt", record.observedAt.toString())
            setProperty("firstHomeFrameAt", record.firstHomeFrameAt.toString())
        })
    }

    companion object { private val lock = Any() }
}
