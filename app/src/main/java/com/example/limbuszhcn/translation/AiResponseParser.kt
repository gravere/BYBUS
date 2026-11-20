package com.example.limbuszhcn.translation

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Safe to display or log: never retain the provider body or a parser exception containing it. */
internal class AiResponseFormatException : AiTranslationException(
    "翻译服务返回的格式无法识别，未保存本批译文，请重试。")

private val JSON_FENCE = Regex("\\A```(?:json)?[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```\\z", RegexOption.IGNORE_CASE)

/** Accept one complete object. A model's optional Markdown wrapper is not part of the translation. */
internal fun parseAiResponseObject(raw: String, allowFence: Boolean = false, maxCharacters: Int = 256 * 1024): JSONObject {
    val clean = raw.trim().removePrefix("\uFEFF").trim()
    val json = if (allowFence && clean.startsWith("```"))
        JSON_FENCE.matchEntire(clean)?.groupValues?.get(1) ?: throw AiResponseFormatException()
    else clean
    try {
        AiJsonGuard(json, maxCharacters).check()
        val reader = JSONTokener(json)
        val value = reader.nextValue()
        if (value !is JSONObject || reader.nextClean() != '\u0000') throw AiResponseFormatException()
        return value
    } catch (_: JSONException) {
        throw AiResponseFormatException()
    }
}

/** Android org.json is lenient and silently overwrites duplicate keys. Bound and validate first. */
private class AiJsonGuard(private val text: String, private val maxCharacters: Int) {
    private var at = 0
    private fun invalid(): Nothing = throw AiResponseFormatException()
    private fun space() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun take(c: Char): Boolean { space(); return if (text.getOrNull(at) == c) { at++; true } else false }
    private fun expect(c: Char) { if (!take(c)) invalid() }
    fun check() {
        if (text.length > maxCharacters) invalid()
        value(0); space()
        if (at != text.length) invalid()
    }
    private fun value(depth: Int) {
        if (depth > 32) invalid()
        space()
        when (text.getOrNull(at)) {
            '{' -> {
                at++; val keys = hashSetOf<String>()
                if (take('}')) return
                do {
                    space(); val start = at
                    string()
                    val key = JSONTokener(text.substring(start, at)).nextValue() as? String ?: invalid()
                    if (!keys.add(key)) invalid()
                    expect(':'); value(depth + 1)
                } while (take(','))
                expect('}')
            }
            '[' -> {
                at++
                if (take(']')) return
                do { value(depth + 1) } while (take(','))
                expect(']')
            }
            '"' -> string()
            else -> {
                val start = at
                while (at < text.length && text[at] !in " \t\r\n,]}") at++
                val token = text.substring(start, at)
                if (token !in setOf("true", "false", "null") && !NUMBER.matches(token)) invalid()
            }
        }
    }
    private fun string() {
        if (text.getOrNull(at++) != '"') invalid()
        while (at < text.length) when (val c = text[at++]) {
            '"' -> return
            '\\' -> when (text.getOrNull(at++)) {
                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                'u' -> repeat(4) { if (text.getOrNull(at++)?.let { it in "0123456789abcdefABCDEF" } != true) invalid() }
                else -> invalid()
            }
            else -> if (c.code < 32) invalid()
        }
        invalid()
    }
    companion object { private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?") }
}
