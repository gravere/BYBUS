package com.example.limbuszhcn.translation

import org.json.JSONObject

/** A task's answer cache stays in its original namespace even after changing provider/model. */
internal data class AiCacheConnection(val endpoint: String, val model: String) {
    fun json() = JSONObject().put("endpoint", endpoint).put("model", model)
    companion object {
        fun read(j: JSONObject) = AiCacheConnection(normalizedAiEndpoint(j.getString("endpoint")),
            j.getString("model").also { require(validAiModelId(it)) })
    }
}

internal fun AiTaskSnapshot.withConnection(settings: AiConfig, price: AiPrice?): AiTaskSnapshot {
    val next = config.copy(endpoint = settings.endpoint, model = settings.model, stream = settings.stream).validated()
    val changed = next.endpoint != config.endpoint || next.model != config.model
    return copy(config = next, cacheConnection = cacheConnection ?: AiCacheConnection(config.endpoint, config.model),
        progress = if (changed) progress.withNextPrice(price) else progress)
}

internal fun aiResponseCacheKey(config: AiConfig, connection: AiCacheConnection?, resource: String, raw: String): String =
    AiTranslationJob.digest("${connection?.endpoint ?: config.endpoint}|${connection?.model ?: config.model}|$resource|${AiTranslationJob.digest(raw)}|${config.missingOnly}|${AiTranslationJob.digest(config.prompt)}")
