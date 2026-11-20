package com.example.limbuszhcn

import android.os.CancellationSignal
import androidx.compose.runtime.*
import com.example.limbuszhcn.translation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Counts and paid-task arguments are derived from the same immutable SQLite selection. */
@Composable
internal fun coverageSelection(coverage: CoverageState, filter: CoverageFilter, numbers: Set<Int>?, read: CoverageCountReader): CoverageRequest? {
    val report = coverage.report
    val identity = report?.entryIndex
    val fingerprint = coverage.fingerprint
    val reader by rememberUpdatedState(read)
    var result by remember(identity, fingerprint, filter, numbers, coverage.current) { mutableStateOf<CoverageRequest?>(null) }
    val signal = remember(identity, fingerprint, filter, numbers, coverage.current) { CancellationSignal() }
    DisposableEffect(signal) { onDispose { signal.cancel() } }
    LaunchedEffect(signal) {
        if (!coverage.current || identity == null || fingerprint == null) return@LaunchedEffect
        try {
            val count = withContext(Dispatchers.IO) { reader(identity, filter, numbers, signal) }
            result = CoverageRequest(report, fingerprint, filter, numbers, count)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { result = null }
    }
    return result
}
