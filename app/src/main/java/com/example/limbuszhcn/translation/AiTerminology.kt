package com.example.limbuszhcn.translation

import com.example.limbuszhcn.gamefs.GameStorage

internal data class AiTerms(val values: Map<String, String>, val required: Set<String>)

internal class AiTerminology(private val values: Map<String, String>, private val names: Set<String>) {
    private val longestFirst = values.entries.sortedByDescending { it.key.length }
    fun forText(text: String, context: String): AiTerms {
        val visible = ProtectedTranslation(text).masked
        val found = linkedMapOf<String, String>()
        val occupied = mutableListOf<IntRange>()
        for ((source, target) in longestFirst) {
            var start = visible.indexOf(source)
            while (start >= 0) {
                val range = start until start + source.length
                val word = source.all { it.code < 128 }
                fun wordChar(index: Int) = visible.getOrNull(index)?.let { it.code < 128 && (it.isLetterOrDigit() || it == '_') } == true
                if ((!word || (!wordChar(start - 1) && !wordChar(start + source.length))) &&
                    occupied.none { range.first <= it.last && range.last >= it.first }) {
                    found[source] = target; occupied += range
                }
                start = visible.indexOf(source, start + source.length)
            }
            if (found.size >= 64) break
        }
        val mechanical = listOf("JP_Skills", "JP_Passives", "JP_BattleKeywords", "JP_Bufs", "JP_EgoGifts", "JP_Egos").any { context.contains(it) }
        return AiTerms(found, if (mechanical) found.keys else found.keys.intersect(names))
    }

    companion object {
        // Checked against Zero 2026111401 Characters/ScenarioModelCodes and paired game JP tables.
        private val DEFAULT_NAMES = mapOf("イサン" to "李箱", "ファウスト" to "浮士德", "ドンキホーテ" to "堂吉诃德",
            "良秀" to "良秀", "ムルソー" to "默尔索", "ホンル" to "鸿璐", "ヒースクリフ" to "希斯克利夫",
            "イシュメール" to "以实玛利", "ロージャ" to "罗佳", "シンクレア" to "辛克莱", "ウーティス" to "奥提斯",
            "グレゴール" to "格里高尔", "ダンテ" to "但丁", "ヴェルギリウス" to "维吉里乌斯", "カロン" to "卡戎")
        val DEFAULT = AiTerminology(DEFAULT_NAMES, DEFAULT_NAMES.keys)
        fun build(storage: GameStorage, base: PreparedTranslation?, cancelled: () -> Boolean = { false }): AiTerminology {
            if (base == null) return DEFAULT
            val inspection = TranslationInspection(storage, base, cancelled)
            val terms = linkedMapOf<String, String>(); val names = mutableSetOf<String>(); val conflicts = mutableSetOf<String>()
            var bytes = 0L
            for (key in inspection.resources) {
                val filename = key.substringAfterLast('/')
                val character = filename.startsWith("JP_Characters") || filename.startsWith("JP_ScenarioModelCodes")
                if (!character && listOf("JP_BattleKeywords", "JP_Bufs", "JP_Egos", "JP_EgoGifts", "JP_Personalities", "JP_Personality", "JP_Items").none(filename::startsWith)) continue
                val resource = inspection.read(key); bytes += resource.raw.length * 2L
                require(bytes <= 64L * 1024 * 1024) { "术语文本超出检查范围" }
                resource.fields.filter { it.covered && it.canTranslate && !it.needsReview &&
                    (it.context.substringBefore(" | ").endsWith("/name") || it.context.substringBefore(" | ").endsWith("/nameWithTitle")) }.forEach { field ->
                    val from = field.text; val to = field.translated.orEmpty()
                    if (from.length !in 2..64 || to.length !in 1..64 || listOf(from, to).any { it.any { c -> c in "<>[]{}\n\r" } }) return@forEach
                    if (from in terms && terms[from] != to) conflicts += from else terms[from] = to
                    if (character) names += from
                }
                require(terms.size <= 12000) { "术语数量超出检查范围" }
            }
            conflicts.forEach(terms::remove)
            return AiTerminology(DEFAULT_NAMES + terms, DEFAULT_NAMES.keys + (names - conflicts))
        }
        fun validate(text: String, terms: AiTerms) {
            require(terms.required.all { source -> terms.values[source]?.let(text::contains) == true }) {
                "AI 译名与参考术语不一致，未写入本批译文"
            }
        }
    }
}
