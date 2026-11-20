package com.example.limbuszhcn.translation

internal enum class CoverageKind(val label: String) { Missing("未覆盖"), Review("待核对") }

internal data class CoverageEntry(val number: Int, val kind: CoverageKind, val category: String, val resource: String,
    val text: String, val translation: String?, val recordId: String? = null, val pointer: String = "", val note: String = "") {
    val canTranslate get() = pointer.isNotEmpty() && text.length <= 6000 && !Regex("⟦P[0-9]+⟧").containsMatchIn(text)
}

/** A selection belongs to one immutable check, never to whichever source is selected later. */
internal data class CoverageRequest(val report: CoverageReport, val fingerprint: String, val filter: CoverageFilter,
    val numbers: Set<Int>? = null, val count: Int)
internal typealias CoverageCountReader = (CoverageIndexRef, CoverageFilter, Set<Int>?, android.os.CancellationSignal) -> Int

internal data class CoverageFilter(val kind: CoverageKind? = null, val category: String? = null, val text: String = "") {
    val term get() = text.trim()
    val exactNumber get() = term.takeIf { it.startsWith('#') || it.startsWith('＃') }?.drop(1)?.toIntOrNull()

    fun includes(entry: CoverageEntry): Boolean {
        if (kind != null && entry.kind != kind || category != null && entry.category != category) return false
        exactNumber?.let { return entry.number == it }
        return term.isEmpty() || entry.number.toString() == term ||
            listOfNotNull(entry.text, entry.translation, entry.recordId, entry.resource).any { it.contains(term, ignoreCase = true) }
    }
}

internal const val COVERAGE_PAGE_SIZE = 5
internal data class CoveragePage(val rows: List<CoverageEntry>, val total: Int, val page: Int) {
    val pages get() = (total + COVERAGE_PAGE_SIZE - 1) / COVERAGE_PAGE_SIZE
}

/** Older reports only contain a few examples. Keep their sample numbers independent of the view. */
internal fun CoverageReport.legacyEntries(): List<CoverageEntry> = buildList {
    for (file in details) for (sample in file.examples) {
        add(CoverageEntry(size + 1, if (sample.review) CoverageKind.Review else CoverageKind.Missing,
            file.category, file.resource, sample.text, sample.translation, note = sample.reason))
    }
}
internal fun List<CoverageEntry>.filteredPage(filter: CoverageFilter, requested: Int): CoveragePage {
    val matches = this.filter { filter.includes(it) }.sortedWith(compareBy({ it.kind }, { it.category }, { it.resource }, { it.number }))
    val page = requested.coerceIn(0, (matches.size - 1).coerceAtLeast(0) / COVERAGE_PAGE_SIZE)
    return CoveragePage(matches.drop(page * COVERAGE_PAGE_SIZE).take(COVERAGE_PAGE_SIZE), matches.size, page)
}
