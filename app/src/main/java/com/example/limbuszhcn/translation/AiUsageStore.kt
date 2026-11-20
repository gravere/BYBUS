package com.example.limbuszhcn.translation

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

/** Last completed task's aggregate usage, without prompt text, credentials or account data. */
internal class AiUsageStore(cache: Path) {
    private val file = cache.resolve("last-ai-usage.json")
    fun save(p: AiProgress) {
        if (p.requests == 0) return
        TranslationSources.writeAtomic(file, JSONObject().put("input", p.inputTokens).put("output", p.outputTokens)
            .put("cached", p.cachedTokens).put("elapsed", p.elapsedMillis).put("requests", p.requests)
            .put("unknown", p.unknownUsage).put("price", p.price?.json()).put("billing", p.billing?.validate(p)?.json()).toString())
    }
    fun read(): AiProgress = runCatching {
        require(Files.size(file) <= 32 * 1024)
        val j = JSONObject(String(Files.readAllBytes(file), Charsets.UTF_8))
        AiProgress(inputTokens=j.getLong("input"),outputTokens=j.getLong("output"),cachedTokens=j.getLong("cached"),
            elapsedMillis=j.getLong("elapsed"),requests=j.getInt("requests"),unknownUsage=j.getBoolean("unknown"),price=j.optJSONObject("price")?.let(AiPrice::read),
            billing=j.optJSONObject("billing")?.let(AiBillingCheckpoint::read)).also {
                require(it.inputTokens >= 0 && it.outputTokens >= 0 && it.cachedTokens in 0..it.inputTokens && it.elapsedMillis >= 0 && it.requests >= 0)
                it.billing?.validate(it)
            }
    }.getOrDefault(AiProgress())
}
