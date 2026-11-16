package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.TranslationJsonPairer
import com.example.limbuszhcn.gamefs.GameStorage
import java.nio.file.Files
import java.util.concurrent.CancellationException

internal data class CoverageSample(val text: String, val review: Boolean)
internal data class CoverageFile(val resource: String, val category: String, val missing: Int, val review: Int,
    val examples: List<CoverageSample>)
internal data class CoverageReport(val sourceName: String, val checkedAt: Long, val files: Int, val total: Int,
    val covered: Int, val missing: Int, val review: Int, val aiEligible: Int, val unreadable: Int,
    val details: List<CoverageFile>)
internal data class CoverageState(val loading: Boolean = false, val report: CoverageReport? = null,
    val error: String? = null)

/** Local inspection only. Does not activate, rewrite or download game/translation resources. */
internal object TranslationCoverage {
    fun scan(storage: GameStorage, base: PreparedTranslation, sourceName: String): CoverageReport {
        val resources = storage.japaneseResources().sorted()
        require(resources.isNotEmpty()) { "还没有本地游戏文本。请先从 BYBUS 进入游戏完成资源下载，再回来检查。" }
        val entries = base.manifest.entries.associateBy { AiTranslationJob.resourceKey(it.targetPath) }
        var total = 0; var covered = 0; var missing = 0; var review = 0; var eligible = 0
        var unreadable = 0; var bytesRead = 0L
        val details = mutableListOf<CoverageFile>()
        for (key in resources) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            val result = runCatching {
                val bytes = requireNotNull(storage.read(storage.rootPath().trimEnd('/') + "/$key"))
                require(bytes.size <= 8 * 1024 * 1024)
                bytesRead += bytes.size
                val raw = bytes.toString(Charsets.UTF_8)
                val original = TranslationFields.parse(raw)
                val baseline = entries[key]?.let { entry ->
                    val file = base.directory.resolve(entry.cachePath)
                    require(Files.size(file) <= 8 * 1024 * 1024)
                    TranslationJsonPairer.mergeWithCoverage("Localize/$key", raw, String(Files.readAllBytes(file), Charsets.UTF_8))
                }
                TranslationFields.collect(original, baseline?.document ?: TranslationFields.parse(raw), key, baseline)
            }
            require(bytesRead <= 128L * 1024 * 1024) { "本地文本过多，本次检查已停止" }
            if (result.isFailure) {
                if (Thread.currentThread().isInterrupted) throw CancellationException()
                unreadable++; continue
            }
            val fields = result.getOrThrow()
            val uncovered = fields.filterNot { it.covered }
            // Kana/Hangul is a review hint, not permission to overwrite human translations.
            val uncertain = fields.filter { it.covered && it.translated.orEmpty().any(::foreignLetter) }
            total += fields.size; covered += fields.count { it.covered }; missing += uncovered.size
            review += uncertain.size; eligible += uncovered.count { it.canTranslate }
            if (uncovered.isNotEmpty() || uncertain.isNotEmpty()) details += CoverageFile(key, category(key),
                uncovered.size, uncertain.size, uncovered.take(2).map { CoverageSample(it.text.take(200), false) } +
                    uncertain.take(1).map { CoverageSample(it.translated.orEmpty().take(200), true) })
        }
        return CoverageReport(sourceName, System.currentTimeMillis(), resources.size - unreadable, total,
            covered, missing, review, eligible, unreadable, details)
    }
    private fun foreignLetter(c: Char) = c in '\u3041'..'\u3096' || c in '\u30A1'..'\u30FA' || c in '\uAC00'..'\uD7AF'
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
