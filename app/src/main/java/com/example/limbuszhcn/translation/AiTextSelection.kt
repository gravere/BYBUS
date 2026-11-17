package com.example.limbuszhcn.translation

/** Coverage and paid jobs classify the same display fields, including public-text fallback. */
internal object AiTextSelection {
    fun missing(field: TranslationField, shared: Map<String, String>) = !field.covered && field.text !in shared
    fun review(field: TranslationField, shared: Map<String, String>) = field.needsReview ||
        (!field.covered && shared[field.text]?.let(TranslationFields::hasResidualLanguage) == true)
    fun includes(field: TranslationField, config: AiConfig, shared: Map<String, String>) = field.canTranslate &&
        (!config.missingOnly || (config.includeMissing && missing(field, shared)) || (config.includeReview && review(field, shared)))
}
