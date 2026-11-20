package com.example.limbuszhcn.translation

/** Coverage and paid jobs classify the same display fields, including public-text fallback. */
internal object AiTextSelection {
    fun missing(field: TranslationField, shared: Map<String, String>) = !field.covered && field.sharedTranslation(shared) == null
    fun review(field: TranslationField, shared: Map<String, String>) = field.needsReview ||
        (!field.covered && field.sharedTranslation(shared)?.let(TranslationFields::hasResidualLanguage) == true)
    fun includes(field: TranslationField, config: AiConfig, shared: Map<String, String>) = field.canTranslate &&
        (!config.missingOnly || (config.includeMissing && missing(field, shared)) || (config.includeReview && review(field, shared)))
}
