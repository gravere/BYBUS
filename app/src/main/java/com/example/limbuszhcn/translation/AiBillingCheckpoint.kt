package com.example.limbuszhcn.translation

import org.json.JSONObject

/** Completed pricing segments, with cumulative token boundaries. Never mixes currencies. */
internal data class AiBillingCheckpoint(val input: Long, val output: Long, val cached: Long, val requests: Int,
    val costs: Map<String, Double>, val unknownPrice: Boolean) {
    fun json() = JSONObject().put("input", input).put("output", output).put("cached", cached)
        .put("requests", requests).put("costs", JSONObject(costs)).put("unknownPrice", unknownPrice)
    fun validate(progress: AiProgress) = apply {
        require(input in 0..progress.inputTokens && output in 0..progress.outputTokens && cached in 0..progress.cachedTokens)
        require(cached <= input && progress.cachedTokens - cached <= progress.inputTokens - input && requests in 0..progress.requests)
        require(costs.size <= 256 && costs.all { (currency, cost) -> currency.matches(Regex("[A-Z]{3}")) && cost.isFinite() && cost >= 0 })
    }
    companion object {
        fun read(j: JSONObject): AiBillingCheckpoint {
            val costs = j.getJSONObject("costs")
            require(costs.length() <= 256)
            return AiBillingCheckpoint(j.getLong("input"), j.getLong("output"), j.getLong("cached"), j.getInt("requests"),
                costs.keys().asSequence().associateWith { costs.getDouble(it) }, j.getBoolean("unknownPrice"))
        }
    }
}

internal fun AiProgress.knownCosts(): Map<String, Double> {
    val old = billing?.validate(this)
    val totals = old?.costs.orEmpty().toMutableMap()
    val input = inputTokens - (old?.input ?: 0); val output = outputTokens - (old?.output ?: 0)
    if (price != null && (requests > (old?.requests ?: 0) || input + output > 0)) {
        totals[price.currency] = (totals[price.currency] ?: 0.0) + price.cost(input, output, cachedTokens - (old?.cached ?: 0))
    }
    return totals.toSortedMap()
}

internal fun AiProgress.hasUnknownPrice(): Boolean = billing?.unknownPrice == true ||
    price == null && (requests > (billing?.requests ?: 0) || inputTokens > (billing?.input ?: 0) || outputTokens > (billing?.output ?: 0))

internal fun AiProgress.withNextPrice(next: AiPrice?): AiProgress = copy(price = next,
    billing = AiBillingCheckpoint(inputTokens, outputTokens, cachedTokens, requests, knownCosts(), hasUnknownPrice()))
