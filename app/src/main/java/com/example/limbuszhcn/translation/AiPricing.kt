package com.example.limbuszhcn.translation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

internal data class AiPrice(val input: Double, val output: Double, val cached: Double? = null,
    val currency: String = "USD", val manual: Boolean = false, val fetchedAt: Long = System.currentTimeMillis()) {
    fun validated() = apply {
        require(listOfNotNull(input, output, cached).all { it.isFinite() && it in 0.0..1_000_000.0 }) { "单价须为 0–1000000 的数字" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "币种请输入三个大写字母，例如 USD、CNY" }
    }
    fun cost(inputTokens: Long, outputTokens: Long, cachedTokens: Long): Double =
        ((inputTokens - cachedTokens.coerceIn(0, inputTokens)) * input + outputTokens * output +
            cachedTokens.coerceIn(0, inputTokens) * (cached ?: input)) / 1_000_000.0
    fun json() = JSONObject().put("input", input).put("output", output).put("cached", cached)
        .put("currency", currency).put("manual", manual).put("fetchedAt", fetchedAt)
    companion object {
        fun read(j: JSONObject) = AiPrice(j.getDouble("input"), j.getDouble("output"),
            if (j.has("cached") && !j.isNull("cached")) j.getDouble("cached") else null,
            j.optString("currency", "USD"), j.optBoolean("manual"), j.optLong("fetchedAt")).validated()
    }
}

internal data class AiPricingState(val endpoint: String = "", val model: String = "", val price: AiPrice? = null,
    val loading: Boolean = false, val message: String? = null)
internal data class AiUsage(val input: Long = 0, val output: Long = 0, val cached: Long = 0, val known: Boolean = false)

/** Public metadata has no credentials. Exact provider endpoint and model matching prevents relay mispricing. */
internal class AiPricing(private val cache: Path, private val download: () -> String = ::downloadCatalog) {
    private val root = cache.resolve("ai-prices")
    private var attemptedAt = 0L
    private var parsedAt = -1L
    private var parsed: JSONObject? = null
    private fun key(endpoint: String, model: String) = AiTranslationJob.digest("${normalizedAiEndpoint(endpoint)}|$model")
    fun save(endpoint: String, model: String, price: AiPrice?) {
        require(validAiModelId(model))
        val file = root.resolve("manual-${key(endpoint, model)}.json")
        if (price == null) Files.deleteIfExists(file) else TranslationSources.writeAtomic(file, price.validated().copy(manual = true).json().toString())
    }
    @Synchronized fun find(endpoint: String, model: String, refresh: Boolean = false): AiPrice? {
        val manual = root.resolve("manual-${key(endpoint, model)}.json")
        if (Files.isRegularFile(manual) && Files.size(manual) <= 4096) return AiPrice.read(JSONObject(String(Files.readAllBytes(manual), Charsets.UTF_8)))
        val catalog = root.resolve("models.json")
        val now = System.currentTimeMillis()
        if ((refresh || now - attemptedAt > 60_000) && (refresh || !Files.isRegularFile(catalog) || now - Files.getLastModifiedTime(catalog).toMillis() > 24 * 60 * 60 * 1000L)) {
            attemptedAt = now
            runCatching { val raw = download(); require(raw.toByteArray(Charsets.UTF_8).size <= 24 * 1024 * 1024)
                val data = JSONObject(raw); require(data.length() > 0); TranslationSources.writeAtomic(catalog, raw)
            }
        }
        if (!Files.isRegularFile(catalog) || Files.size(catalog) > 24 * 1024 * 1024) return null
        val time = Files.getLastModifiedTime(catalog).toMillis()
        if (parsed == null || time != parsedAt) { parsed = JSONObject(String(Files.readAllBytes(catalog), Charsets.UTF_8)); parsedAt = time }
        return match(requireNotNull(parsed), endpoint, model, time)
    }
    companion object {
        const val URL = "https://models.dev/api.json"
        fun match(data: JSONObject, endpoint: String, model: String, time: Long): AiPrice? {
            val normalized = normalizedAiEndpoint(endpoint)
            val native = mapOf("https://api.openai.com/v1" to "openai", "https://api.anthropic.com/v1" to "anthropic")
            val providerIds = data.keys().asSequence().filter { id ->
                val api = data.optJSONObject(id)?.optString("api").orEmpty()
                (api.isNotBlank() && runCatching { normalizedAiEndpoint(api) == normalized }.getOrDefault(false)) || native[normalized] == id
            }.toList()
            if (providerIds.size != 1) return null
            val cost = data.getJSONObject(providerIds.single()).optJSONObject("models")?.optJSONObject(model)?.optJSONObject("cost") ?: return null
            if (!cost.has("input") || !cost.has("output")) return null
            return runCatching { AiPrice(cost.getDouble("input"), cost.getDouble("output"),
                if (cost.has("cache_read")) cost.getDouble("cache_read") else null, fetchedAt = time).validated() }.getOrNull()
        }
        private fun downloadCatalog(): String {
            val c = URI(URL).toURL().openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 5000; c.readTimeout = 8000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "BYBUS-Pricing")
                require(c.responseCode == 200)
                val output = java.io.ByteArrayOutputStream()
                c.inputStream.use { TranslationSources.copyBounded(it, output, 24 * 1024 * 1024) }
                return output.toString("UTF-8")
            } finally { c.disconnect() }
        }
    }
}
