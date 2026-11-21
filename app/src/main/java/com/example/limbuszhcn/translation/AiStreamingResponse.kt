package com.example.limbuszhcn.translation

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.CodingErrorAction

internal enum class AiResponseStage { Sending, Waiting, Receiving, Thinking }
internal data class AiResponseProgress(val stage: AiResponseStage, val bytes: Long = 0, val events: Int = 0,
    val heartbeats: Int = 0, val characters: Long = 0)

/** One HTTP response only; accepting ordinary JSON never causes a second billed request. */
internal fun readAiHttpResponse(input: InputStream, contentType: String?,
    progress: (AiResponseProgress) -> Unit = {}): String = try {
    decodeAiHttpResponse(input, contentType, progress)
} catch (_: java.nio.charset.CharacterCodingException) { throw AiResponseFormatException() }

private fun decodeAiHttpResponse(input: InputStream, contentType: String?,
    progress: (AiResponseProgress) -> Unit = {}): String {
    var received = 0L
    var current = AiResponseProgress(AiResponseStage.Waiting)
    fun report(stage: AiResponseStage = current.stage, events: Int = current.events,
        heartbeats: Int = current.heartbeats, characters: Long = current.characters) {
        current = AiResponseProgress(stage, received, events, heartbeats, characters); progress(current)
    }
    val bounded = object : FilterInputStream(input) {
        private fun add(n: Int) { if (n > 0) { received += n; report(); if (received > 8 * 1024 * 1024) throw AiResponseFormatException() } }
        override fun read(): Int = `in`.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also(::add)
    }
    val buffered = BufferedInputStream(bounded)
    buffered.mark(64)
    var first = buffered.read()
    var skipped = 0
    while (first in listOf(9, 10, 13, 32) && skipped++ < 60) first = buffered.read()
    buffered.reset()
    val stream = contentType?.substringBefore(';')?.trim()?.equals("text/event-stream", true) == true || first == ':'.code || first == 'd'.code || first == 'e'.code
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    // InputStreamReader must use the strict decoder, rather than silently replacing malformed bytes.
    val strict = java.io.InputStreamReader(buffered, decoder).buffered()
    if (!stream) {
        report(AiResponseStage.Receiving)
        val out = StringBuilder()
        val chars = CharArray(4096)
        while (true) {
            val count = strict.read(chars)
            if (count < 0) break
            if (out.length + count > 256 * 1024) throw AiResponseFormatException()
            out.append(chars, 0, count)
            report(characters = current.characters + (0 until count).count { !chars[it].isWhitespace() })
        }
        val raw = out.toString()
        aiServiceFailure(parseAiResponseObject(raw))?.let { throw it }
        return raw
    }
    val content = StringBuilder()
    var finish: String? = null
    var refusal: String? = null
    var usage: JSONObject? = null
    var events = 0
    var done = false
    val event = StringBuilder()
    var eventType = ""
    fun dispatch() {
        if (event.isEmpty()) { eventType = ""; return }
        val data = event.toString().trim(); event.setLength(0)
        val errorEvent = eventType == "error"; eventType = ""
        if (data == "[DONE]") { done = true; return }
        val chunk = parseAiResponseObject(data)
        aiServiceFailure(chunk, errorEvent = errorEvent)?.let { throw it }
        events++
        var stage = current.stage
        var receivedCharacters = current.characters
        val choices = chunk.optJSONArray("choices") ?: throw AiResponseFormatException()
        for (i in 0 until choices.length()) {
            val choice = choices.optJSONObject(i) ?: throw AiResponseFormatException()
            if (choice.optInt("index", -1) != 0) continue
            val delta = choice.optJSONObject("delta") ?: throw AiResponseFormatException()
            val value = delta.opt("content")
            if (value != null && value !== JSONObject.NULL) {
                if (value !is String || finish != null && value.isNotEmpty() || content.length + value.length > 256 * 1024) throw AiResponseFormatException()
                content.append(value)
                receivedCharacters += value.length
                if (value.isNotEmpty()) stage = AiResponseStage.Receiving
            }
            if (!delta.isNull("refusal") && delta.optString("refusal").isNotEmpty()) refusal = "refused"
            if (content.isEmpty() && (!delta.isNull("reasoning_content") || !delta.isNull("reasoning"))) stage = AiResponseStage.Thinking
            for (field in listOf("reasoning_content", "reasoning")) receivedCharacters += (delta.opt(field) as? String)?.length ?: 0
            if (!choice.isNull("finish_reason")) {
                val reason = choice.opt("finish_reason") as? String ?: throw AiResponseFormatException()
                if (finish != null && finish != reason) throw AiResponseFormatException()
                finish = reason
            }
        }
        chunk.optJSONObject("usage")?.let { usage = it }
        report(stage, events, characters = receivedCharacters)
    }
    val line = StringBuilder()
    var firstLine = true
    while (!done) {
        line.setLength(0)
        while (true) {
            val char = strict.read()
            if (char < 0) throw AiTranslationException("翻译回复未接收完整，本批未保存。请稍后继续，已完成的译文会保留。")
            if (char == '\n'.code) break
            if (char == '\r'.code) {
                strict.mark(1)
                if (strict.read() != '\n'.code) strict.reset()
                break
            }
            if (line.length >= 256 * 1024) throw AiResponseFormatException()
            line.append(char.toChar())
        }
        var text = line.toString().removeSuffix("\r")
        if (firstLine) { text = text.removePrefix("\uFEFF"); firstLine = false }
        if (text.isEmpty()) dispatch()
        else if (text.startsWith(':')) report(heartbeats = current.heartbeats + 1)
        else if (text.startsWith("event:")) eventType = text.substring(6).trim()
        else if (text.startsWith("data:")) {
            val data = text.substring(5).removePrefix(" ")
            if (event.length + data.length + 1 > 256 * 1024) throw AiResponseFormatException()
            if (event.isNotEmpty()) event.append('\n')
            event.append(data)
        }
        // Comments/keepalives and other SSE fields carry no translation text.
    }
    if (finish == null) throw AiTranslationException("模型没有完整返回本批译文；此前完成的译文已保留。")
    return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
        .put("message", JSONObject().put("content", content.toString()).put("refusal", refusal ?: JSONObject.NULL))))
        .put("usage", usage ?: JSONObject.NULL).toString()
}
