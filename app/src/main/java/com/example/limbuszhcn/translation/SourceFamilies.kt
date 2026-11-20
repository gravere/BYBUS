package com.example.limbuszhcn.translation

internal data class SourceFamily(val id: String, val name: String, val base: TranslationSource?, val versions: List<TranslationSource>)

internal fun sourceFamilies(sources: List<TranslationSource>): List<SourceFamily> {
    val grouped = sources.filter { it.kind == "ai" }.groupBy { it.baseId ?: if (it.independent) "global" else "zero" }
    val roots = sources.filter { it.kind != "ai" }
    return roots.map { SourceFamily(it.id, it.name, it, grouped[it.id].orEmpty().sortedByDescending { v -> v.updatedAtMillis }) } +
        grouped.filterKeys { id -> roots.none { it.id == id } }.map { (id, values) ->
            SourceFamily(id, if (id == "global") "全局 AI 翻译" else values.first().baseName ?: "原始汉化包", null,
                values.sortedByDescending { it.updatedAtMillis })
        }
}
