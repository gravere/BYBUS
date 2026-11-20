package com.example.limbuszhcn.translation

import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.gamefs.GameTextLanguage
import java.util.concurrent.CancellationException

internal data class CoverageSample(val text: String, val review: Boolean, val translation: String? = null,
    val reason: String = "")
internal data class CoverageFile(val resource: String, val category: String, val missing: Int, val review: Int,
    val examples: List<CoverageSample>)
internal data class CoverageReport(val sourceName: String, val checkedAt: Long, val files: Int, val total: Int,
    val covered: Int, val missing: Int, val review: Int, val aiEligible: Int, val unreadable: Int,
    val details: List<CoverageFile>, val excluded: Int = 0, val shared: Int = 0, val reviewEligible: Int = 0,
    val independentEligible: Int = 0, val entryIndex: CoverageIndexRef? = null,
    val language: GameTextLanguage = GameTextLanguage.Japanese)
internal data class CoverageState(val loading: Boolean = false, val report: CoverageReport? = null,
    val error: String? = null, val fingerprint: String? = null, val stale: Boolean = false, val independentReady: Boolean = true,
    val language: GameTextLanguage? = null) {
    val current get() = report != null && fingerprint != null && error == null && !loading && !stale
    fun eligible(config: AiConfig): Int = report?.let {
        if (!config.missingOnly) it.independentEligible else (if (config.includeMissing) it.aiEligible else 0) + (if (config.includeReview) it.reviewEligible else 0)
    } ?: 0
    fun canGenerate(config: AiConfig) = current && (config.missingOnly || independentReady) && eligible(config) > 0
}

/** Local inspection only. Does not activate, rewrite or download game/translation resources. */
internal object TranslationCoverage {
    fun independentRemaining(storage: GameStorage, committed: Set<String>): Int {
        val originals = TranslationInspection(storage, null)
        var bytes = 0L
        var remaining = 0
        for (key in originals.resources) {
            val resource = originals.readable(key) ?: continue
            bytes += resource.bytes
            require(bytes <= 128L * 1024 * 1024) { "本地文本过多，本次检查已停止" }
            remaining += resource.fields.count { it.canTranslate && it.id !in committed }
        }
        return remaining
    }

    fun scan(storage: GameStorage, base: PreparedTranslation, sourceName: String, committedAi: Set<String> = emptySet(),
        onEntry: ((CoverageEntry) -> Unit)? = null): CoverageReport {
        val inspection = TranslationInspection(storage, base)
        val resources = inspection.resources
        require(resources.isNotEmpty()) { "还没有本地游戏文本。请先从 BYBUS 进入游戏完成资源下载，再回来检查。" }
        val shared = inspection.sharedTranslations()
        var total = 0; var covered = 0; var missing = 0; var review = 0; var eligible = 0
        var excluded = 0; var sharedCount = 0; var reviewEligible = 0
        var unreadable = 0; var independent = 0; var bytesRead = 0L
        val details = mutableListOf<CoverageFile>()
        for (key in resources) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            val resource = inspection.readable(key)
            if (resource == null) { unreadable++; continue }
            bytesRead += resource.bytes
            require(bytesRead <= 128L * 1024 * 1024) { "本地文本过多，本次检查已停止" }
            val all = resource.fields
            excluded += all.count { it.excludedReason != null }
            val category = category(key)
            val reason = if (key in inspection.entries) "同文件未找到可用配对" else "汉化包没有同名文件"
            val examples = mutableListOf<CoverageSample>()
            var fileMissing = 0; var fileReview = 0
            for (field in all) {
                if (field.excludedReason != null) continue
                total++
                if (field.canTranslate && field.id !in committedAi) independent++
                if (!field.covered && field.sharedTranslation(shared) != null) sharedCount++
                val kind = if (AiTextSelection.missing(field, shared)) {
                    missing++; fileMissing++
                    if (field.canTranslate) eligible++
                    CoverageKind.Missing
                } else {
                    covered++
                    if (!AiTextSelection.review(field, shared)) continue
                    review++; fileReview++
                    if (field.canTranslate) reviewEligible++
                    CoverageKind.Review
                }
                val translated = field.translated ?: field.sharedTranslation(shared)
                if (onEntry != null) {
                    onEntry(CoverageEntry(missing + review, kind, category, key, field.text, translated, field.recordId, field.pointer))
                } else if (if (kind == CoverageKind.Review) fileReview <= 2 else fileMissing <= 2) {
                    examples += CoverageSample(preview(field.text), kind == CoverageKind.Review,
                        translated?.let(::preview), if (kind == CoverageKind.Missing) reason else "译文中仍含日文或韩文")
                }
            }
            if (fileMissing + fileReview > 0) details += CoverageFile(key, category, fileMissing, fileReview, examples)
        }
        return CoverageReport(sourceName, System.currentTimeMillis(), resources.size - unreadable, total,
            covered, missing, review, eligible, unreadable, details, excluded, sharedCount, reviewEligible, independent,
            language = inspection.language)
    }
    private fun preview(text: String) = if (text.length > 320) text.take(320) + "…" else text
    internal fun category(path: String): String {
        val name = path.lowercase()
        return when {
            "gift" in name -> "饰品与特性"
            "rpg" in name || "dungeon" in name || "abnormalityevent" in name -> "迷宫与事件"
            "story" in name || "scenario" in name || "dialog" in name -> "剧情与对话"
            "voice" in name || "announcer" in name -> "语音与播报"
            "skill" in name || "passive" in name || "buff" in name || "keyword" in name -> "战斗与技能"
            "notice" in name || "mail" in name -> "公告与邮件"
            "event" in name -> "活动与事件"
            "ego" in name || "personality" in name -> "人格与 E.G.O"
            "ui" in name || "tip" in name || "tutorial" in name -> "界面与提示"
            else -> "其他文本"
        }
    }
}
