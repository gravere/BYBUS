package com.example.limbuszhcn.translation

import org.json.JSONArray
import org.json.JSONObject

internal enum class AiChangeKind(val label: String) {
    Translation("AI 翻译"), Removal("删除 AI 译文"), BaseUpdate("采用原包译文"), Upstream("同步原包并补译")
}
internal data class AiChangeExample(val category: String, val original: String, val before: String?, val after: String?)
internal data class AiChangeSummary(val kind: AiChangeKind, val atMillis: Long = 0, val added: Int = 0, val replaced: Int = 0,
    val removed: Int = 0, val unchanged: Int = 0, val files: Int = 0, val categories: Map<String, Int> = emptyMap(),
    val examples: List<AiChangeExample> = emptyList()) {
    val total get() = added + replaced + removed + unchanged
    val description get() = "${kind.label} · $total 条"
    val counts get() = listOfNotNull(added.takeIf { it > 0 }?.let { "新增 $it" }, replaced.takeIf { it > 0 }?.let { "替换 $it" },
        removed.takeIf { it > 0 }?.let { "删除 $it" }, unchanged.takeIf { it > 0 }?.let { "内容相同 $it" }).joinToString(" · ")
    fun validated() = apply {
        require(atMillis >= 0 && listOf(added, replaced, removed, unchanged).all { it in 0..MAX_AI_TASK_ENTRIES } && total <= MAX_AI_TASK_ENTRIES)
        require(files in 0..total && categories.size <= 32 && categories.values.all { it > 0 } && categories.values.sum() == total)
        require(categories.keys.all { it.length <= 80 } && examples.size <= 3)
        require(examples.all { it.category.length <= 80 && it.original.length <= 200 && (it.before?.length ?: 0) <= 240 && (it.after?.length ?: 0) <= 240 })
    }
    fun json() = JSONObject().put("kind", kind.name).put("at", atMillis).put("added", added).put("replaced", replaced)
        .put("removed", removed).put("unchanged", unchanged).put("files", files).put("categories", JSONObject(categories))
        .put("examples", JSONArray().apply { examples.forEach { put(JSONObject().put("category", it.category)
            .put("original", it.original).put("before", it.before).put("after", it.after)) } })
    companion object {
        fun read(j: JSONObject): AiChangeSummary {
            val groups = j.getJSONObject("categories"); val rows = j.getJSONArray("examples")
            require(groups.length() <= 32 && rows.length() <= 3)
            return AiChangeSummary(AiChangeKind.valueOf(j.getString("kind")), j.optLong("at"), j.getInt("added"), j.getInt("replaced"),
                j.getInt("removed"), j.getInt("unchanged"), j.getInt("files"), groups.keys().asSequence().associateWith { groups.getInt(it) },
                (0 until rows.length()).map { rows.getJSONObject(it).let { r -> AiChangeExample(r.getString("category"), r.getString("original"),
                    r.optString("before").takeUnless { r.isNull("before") }, r.optString("after").takeUnless { r.isNull("after") }) } }).validated()
        }
    }
}

/** Collected alongside real field writes; no second full-pack scan or API request. */
internal class AiChangeCollector(private val kind: AiChangeKind) {
    private var added = 0; private var replaced = 0; private var removed = 0; private var unchanged = 0
    private val resources = hashSetOf<String>()
    private val categories = linkedMapOf<String, Int>()
    private val examples = mutableListOf<AiChangeExample>()
    fun record(resource: String, original: String, before: String?, after: String?) {
        if (before == null && after == null) return
        val category = TranslationCoverage.category(resource)
        resources += resource; categories[category] = (categories[category] ?: 0) + 1
        when { before == after -> unchanged++; before == null -> added++; after == null -> removed++; else -> replaced++ }
        fun preview(value: String, limit: Int) = if (value.length <= limit) value else value.take(limit - 1) + "…"
        if (before != after && examples.size < 3) examples += AiChangeExample(category, preview(original, 200),
            before?.let { preview(it, 240) }, after?.let { preview(it, 240) })
    }
    fun snapshot() = AiChangeSummary(kind, added = added, replaced = replaced, removed = removed, unchanged = unchanged,
        files = resources.size, categories = categories.toMap(), examples = examples.toList()).validated()
}
