package com.example.limbuszhcn.translation

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Safe to display or log: never retain the provider body or a parser exception containing it. */
internal class AiResponseFormatException : IllegalArgumentException(
    "翻译服务返回的格式无法识别，未保存本批译文，请重试。")

private val JSON_FENCE = Regex("\\A```(?:json)?[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```\\z", RegexOption.IGNORE_CASE)

/** Accept one complete object. A model's optional Markdown wrapper is not part of the translation. */
internal fun parseAiResponseObject(raw: String, allowFence: Boolean = false): JSONObject {
    val clean = raw.trim().removePrefix("\uFEFF").trim()
    val json = if (allowFence && clean.startsWith("```"))
        JSON_FENCE.matchEntire(clean)?.groupValues?.get(1) ?: throw AiResponseFormatException()
    else clean
    try {
        val reader = JSONTokener(json)
        val value = reader.nextValue()
        if (value !is JSONObject || reader.nextClean() != '\u0000') throw AiResponseFormatException()
        return value
    } catch (_: JSONException) {
        throw AiResponseFormatException()
    }
}
