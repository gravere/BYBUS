package com.example.limbuszhcn.translation

import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.gamefs.GameTextLanguage

internal enum class AiEntryKind(val label: String) { Ai("AI 已译"), Pending("未翻译"), Base("原包译文") }
internal data class AiEntry(val number: Int, val id: String, val kind: AiEntryKind, val category: String,
    val resource: String, val hash: String, val original: String, val translation: String?,
    val pointer: String, val recordId: String?)
internal data class AiEntryFilter(val kind: AiEntryKind? = null, val category: String? = null, val search: String = "")
internal data class AiEntryReport(val key: String, val sourceId: String?, val revision: String, val name: String,
    val language: GameTextLanguage, val game: String, val counts: Map<AiEntryKind, Int>, val categories: List<String>,
    val checkedAt: Long = System.currentTimeMillis(), val unreadable: Int = 0) {
    val total get() = counts.values.sum()
    val aiCount get() = counts[AiEntryKind.Ai] ?: 0
}
internal data class AiEntryPage(val rows: List<AiEntry>, val total: Int, val page: Int) {
    val pages get() = ((total + AI_ENTRY_PAGE_SIZE - 1) / AI_ENTRY_PAGE_SIZE).coerceAtLeast(1)
}
internal const val AI_ENTRY_PAGE_SIZE = 8
internal data class AiEntryRequest(val report: AiEntryReport, val filter: AiEntryFilter = AiEntryFilter(),
    val ids: Set<String>? = null, val count: Int) {
    init {
        require(count > 0 && (ids == null || ids.isNotEmpty() && ids.size <= 1000 && ids.size == count &&
            ids.all { it.matches(Regex("[a-f0-9]{64}")) }))
    }
}
internal data class AiLibraryState(val report: AiEntryReport? = null, val loading: Boolean = false,
    val sourceId: String? = null, val error: String? = null, val stale: Boolean = false)
internal typealias AiEntryReader = (AiEntryReport, AiEntryFilter, Int, android.os.CancellationSignal) -> AiEntryPage

/** The archive is the display truth; a response cache alone never makes an entry AI-owned. */
internal object AiEntryScan {
    fun scan(storage: GameStorage, base: PreparedTranslation?, language: GameTextLanguage,
        proof: Map<String, String>, cancelled: () -> Boolean = { false }, progress: (String, Int, Int) -> Unit = { _, _, _ -> }, add: (AiEntry) -> Unit): Pair<Map<AiEntryKind, Int>, Int> {
        val inspection = TranslationInspection(storage, base, cancelled, language)
        require(inspection.resources.isNotEmpty()) { "尚无${language.displayName}原文，请先完成游戏资源下载" }
        val shared = inspection.sharedTranslations { done, total -> progress("匹配共用译文", done, total) }
        val counts = AiEntryKind.entries.associateWith { 0 }.toMutableMap()
        var number = 0; var bytes = 0L; var unreadable = 0
        progress("读取翻译条目", 0, inspection.resources.size)
        for ((index, resource) in inspection.resources.withIndex()) {
            val r = inspection.readable(resource)
            if (r == null) { unreadable++; progress("读取翻译条目", index + 1, inspection.resources.size); continue }
            bytes += r.bytes; require(bytes <= 128L * 1024 * 1024) { "游戏文本超出本地处理上限" }
            val hash = AiTranslationJob.digest(r.raw)
            for (field in r.fields.filter { it.canTranslate }) {
                val owned = proof[field.id]?.let { runCatching { ProtectedTranslation(field.text).restore(it) == field.translated }.getOrDefault(false) } == true && field.covered
                val translated = field.translated ?: field.sharedTranslation(shared)
                val kind = if (owned) AiEntryKind.Ai else if (translated != null) AiEntryKind.Base else AiEntryKind.Pending
                counts[kind] = counts.getValue(kind) + 1
                require(++number <= 500_000) { "游戏文本条目超出本地处理上限" }
                add(AiEntry(number, field.id, kind, TranslationCoverage.category(resource), resource, hash,
                    field.text, translated, field.pointer, field.recordId))
            }
            progress("读取翻译条目", index + 1, inspection.resources.size)
        }
        return counts to unreadable
    }
}
