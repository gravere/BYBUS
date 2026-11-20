package com.example.limbuszhcn.translation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

internal data class AiPrice(val input: Double, val output: Double, val cached: Double? = null,
    val currency: String = "USD", val manual: Boolean = false, val fetchedAt: Long = System.currentTimeMillis(),
    val reference: String? = null) {
    fun validated() = apply {
        require(listOfNotNull(input, output, cached).all { it.isFinite() && it in 0.0..1_000_000.0 }) { "单价须为 0–1000000 的数字" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "币种请输入三个大写字母，例如 USD、CNY" }
        require(reference == null || reference.length <= 512 && reference.none(Char::isISOControl))
    }
    fun cost(inputTokens: Long, outputTokens: Long, cachedTokens: Long): Double =
        ((inputTokens - cachedTokens.coerceIn(0, inputTokens)) * input + outputTokens * output +
            cachedTokens.coerceIn(0, inputTokens) * (cached ?: input)) / 1_000_000.0
    fun json() = JSONObject().put("input", input).put("output", output).put("cached", cached)
        .put("currency", currency).put("manual", manual).put("fetchedAt", fetchedAt).put("reference", reference)
    companion object {
        fun read(j: JSONObject) = AiPrice(j.getDouble("input"), j.getDouble("output"),
            if (j.has("cached") && !j.isNull("cached")) j.getDouble("cached") else null,
            j.optString("currency", "USD"), j.optBoolean("manual"), j.optLong("fetchedAt"),
            j.optString("reference").takeIf { !j.isNull("reference") && it.isNotBlank() }).validated()
    }
}

internal data class AiPricingState(val endpoint: String = "", val model: String = "", val price: AiPrice? = null,
    val loading: Boolean = false, val message: String? = null, val options: List<AiPriceOption> = emptyList())
internal data class AiPriceOption(val providerId: String, val provider: String, val model: String, val name: String, val price: AiPrice) {
    val id get() = "$providerId/$model"
    private val searchText = "$providerId $provider $model $name"
    fun selectedPrice() = price.copy(manual = true, reference = "$provider · $model")
    fun matches(query: String): Boolean = matchesTerms(query.trim().split(Regex("\\s+")).filter(String::isNotEmpty))
    internal fun matchesTerms(terms: List<String>) = terms.all { searchText.contains(it, ignoreCase = true) }
}
internal fun searchPriceOptions(options: List<AiPriceOption>, query: String): List<AiPriceOption> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return if (terms.isEmpty()) options else options.filter { it.matchesTerms(terms) }
}
internal data class AiUsage(val input: Long = 0, val output: Long = 0, val cached: Long = 0, val known: Boolean = false)

/** Public metadata has no credentials. Exact provider endpoint and model matching prevents relay mispricing. */
internal class AiPricing(private val cache: Path, private val download: () -> String = ::downloadCatalog) {
    private val root = cache.resolve("ai-prices")
    private var attemptedAt = 0L
    private var parsedAt = -1L
    private var parsed: JSONObject? = null
    private var parsedOptions: List<AiPriceOption>? = null
    private fun key(endpoint: String, model: String) = AiTranslationJob.digest("${normalizedAiEndpoint(endpoint)}|$model")
    @Synchronized fun save(endpoint: String, model: String, price: AiPrice?) {
        require(validAiModelId(model))
        val file = root.resolve("manual-${key(endpoint, model)}.json")
        if (price == null) Files.deleteIfExists(file) else TranslationSources.writeAtomic(file, price.validated().copy(manual = true).json().toString())
    }
    private fun manualPrice(endpoint: String, model: String): AiPrice? {
        val manual = root.resolve("manual-${key(endpoint, model)}.json")
        if (Files.isRegularFile(manual) && Files.size(manual) <= 4096) return AiPrice.read(JSONObject(String(Files.readAllBytes(manual), Charsets.UTF_8)))
        return null
    }
    @Synchronized fun find(endpoint: String, model: String, refresh: Boolean = false): AiPrice? =
        manualPrice(endpoint, model) ?: catalog(refresh).let { (data, time, _) -> data?.let { match(it, endpoint, model, time) } }

    @Synchronized fun lookup(endpoint: String, model: String, refresh: Boolean = false): AiPricingState {
        normalizedAiEndpoint(endpoint); require(validAiModelId(model))
        val manual = manualPrice(endpoint, model)
        val (data, time, message) = catalog(refresh)
        return AiPricingState(endpoint, model, manual ?: data?.let { match(it, endpoint, model, time) },
            message = message, options = data?.let { parsedOptions ?: options(it, time).also { values -> parsedOptions = values } }.orEmpty())
    }
    private fun catalog(refresh: Boolean): Triple<JSONObject?, Long, String?> {
        val catalog = root.resolve("models.json")
        val now = System.currentTimeMillis()
        var failed = false
        if ((refresh || now - attemptedAt > 60_000) && (refresh || !Files.isRegularFile(catalog) || now - Files.getLastModifiedTime(catalog).toMillis() > 24 * 60 * 60 * 1000L)) {
            attemptedAt = now
            runCatching { val raw = download(); require(raw.toByteArray(Charsets.UTF_8).size <= 24 * 1024 * 1024)
                val data = JSONObject(raw); require(data.length() > 0); TranslationSources.writeAtomic(catalog, raw); parsedAt = -1L
            }.onFailure { failed = true }
        }
        if (!Files.isRegularFile(catalog) || Files.size(catalog) > 24 * 1024 * 1024)
            return Triple(null, 0, "价格目录暂时不可用，可重试或手动填写")
        val time = Files.getLastModifiedTime(catalog).toMillis()
        if (parsed == null || time != parsedAt) { parsed = JSONObject(String(Files.readAllBytes(catalog), Charsets.UTF_8)); parsedAt = time; parsedOptions = null }
        return Triple(parsed, time, if (failed) "目录更新失败，正在使用已缓存价格" else null)
    }
    companion object {
        const val URL = "https://models.dev/api.json"
        fun options(data: JSONObject, time: Long): List<AiPriceOption> = buildList {
            data.keys().asSequence().sorted().forEach { providerId ->
                val provider = data.optJSONObject(providerId) ?: return@forEach
                val models = provider.optJSONObject("models") ?: return@forEach
                models.keys().asSequence().sorted().forEach models@{ id ->
                    if (size >= 25_000 || providerId.length > 120 || id.length > 256) return@models
                    val model = models.optJSONObject(id) ?: return@models
                    val cost = model.optJSONObject("cost") ?: return@models
                    val price = readCost(cost, time) ?: return@models
                    val label = provider.optString("name", providerId).take(120)
                    if ((label + id).any(Char::isISOControl)) return@models
                    add(AiPriceOption(providerId, label, id, model.optString("name", id).take(200), price))
                }
            }
        }
        fun match(data: JSONObject, endpoint: String, model: String, time: Long): AiPrice? {
            val normalized = normalizedAiEndpoint(endpoint)
            val native = mapOf("https://api.openai.com/v1" to "openai", "https://api.anthropic.com/v1" to "anthropic")
            val providerIds = data.keys().asSequence().filter { id ->
                val api = data.optJSONObject(id)?.optString("api").orEmpty()
                (api.isNotBlank() && runCatching { normalizedAiEndpoint(api) == normalized }.getOrDefault(false)) || native[normalized] == id
            }.toList()
            if (providerIds.size != 1) return null
            val cost = data.getJSONObject(providerIds.single()).optJSONObject("models")?.optJSONObject(model)?.optJSONObject("cost") ?: return null
            return readCost(cost, time)
        }
        private fun readCost(cost: JSONObject, time: Long): AiPrice? {
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
