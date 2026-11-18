package com.example.limbuszhcn.translation

internal enum class AiUpdateChoice { Current, Official, Reuse, Supplement }

internal data class AiUpstreamOffer(val sourceId: String, val revision: String, val version: Long,
    val baseVersion: Long?, val metadata: String, val config: AiConfig, val launchAfter: Boolean)
internal data class AiUpstreamDecision(val offer: AiUpstreamOffer, val choice: AiUpdateChoice)

internal object AiUpstreamUpdate {
    fun identity(source: TranslationSource, version: Long, config: AiConfig) = AiTranslationJob.digest(
        "upstream-v1|${source.id}|${source.revision}|$version|${config.endpoint}|${config.model}|${AiTranslationJob.digest(config.prompt)}").take(32)

    fun shouldOffer(source: TranslationSource, latest: Long, lastOffered: Long, force: Boolean = false) =
        source.kind == "ai" && latest > (source.officialVersion ?: 0) && (force || latest > lastOffered)

    fun requireCurrent(offer: AiUpstreamOffer, source: TranslationSource) {
        require(source.kind == "ai" && source.id == offer.sourceId && source.revision == offer.revision) {
            "当前汉化已改变，请重新检查更新"
        }
    }

    fun supplementConfig(config: AiConfig) = config.copy(missingOnly = true, includeMissing = true, includeReview = false).validated()
}
