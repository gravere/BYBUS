package com.example.limbuszhcn.container

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.IdentityHashMap

/** Data-only source binding for generated translations; never changes the game file or its IDs. */
internal object TranslationSnapshot {
    private const val MARKER = "_bybusTextSnapshot"
    private const val ARRAY = "_bybusArray"
    fun encode(original: String, document: Any): String {
        val root = if (document is JSONObject) document else JSONObject().put(ARRAY, document)
        require(!root.has(MARKER))
        root.put(MARKER, JSONObject().put("schema", 1).put("sha256", hash(original)))
        return try { root.toString() } finally { root.remove(MARKER) }
    }
    data class Decoded(val document: Any, val sourceHash: String?)
    fun decode(value: Any): Decoded {
        if (value !is JSONObject || !value.has(MARKER)) return Decoded(value, null)
        val meta = value.getJSONObject(MARKER)
        require(meta.getInt("schema") == 1 && meta.getString("sha256").matches(Regex("[a-f0-9]{64}"))) { "AI 汉化快照格式无效" }
        val digest = meta.getString("sha256")
        value.remove(MARKER)
        return Decoded(if (value.length() == 1 && value.opt(ARRAY) is JSONArray) value.getJSONArray(ARRAY) else value, digest)
    }
    fun bind(originalText: String, original: Any, translated: Decoded): IdentityHashMap<Any, Any>? {
        if (translated.sourceHash != hash(originalText)) return null
        return matchingStructure(original, translated.document)
    }
    /** Exact non-display structure only. Without a source/cache fingerprint this is not translation proof. */
    fun matchingStructure(original: Any, translated: Any): IdentityHashMap<Any, Any>? {
        val pairs = IdentityHashMap<Any, Any>()
        var count = 0
        fun visit(a: Any?, b: Any?, depth: Int, stringArray: Boolean = false): Boolean {
            if (depth > 64 || ++count > 1000000) return false
            return when {
                a is JSONObject && b is JSONObject -> {
                    if (b.keys().asSequence().any { !a.has(it) }) return false
                    pairs[a] = b
                    a.keys().asSequence().all { key ->
                        val child = a.opt(key)
                        if (child is String && TranslationTextPolicy.isDisplayField(key)) !b.has(key) || b.opt(key) is String
                        else b.has(key) && visit(child, b.opt(key), depth + 1, key in TranslationTextPolicy.STRING_ARRAY_FIELDS)
                    }
                }
                a is JSONArray && b is JSONArray -> {
                    if (a.length() != b.length()) return false
                    pairs[a] = b
                    (0 until a.length()).all { i ->
                        if (stringArray && a.opt(i) is String) b.opt(i) is String else visit(a.opt(i), b.opt(i), depth + 1)
                    }
                }
                a is Number && b is Number -> runCatching { a.toString().toBigDecimal().compareTo(b.toString().toBigDecimal()) == 0 }.getOrDefault(false)
                else -> a == b
            }
        }
        return pairs.takeIf { visit(original, translated, 0) }
    }
    private fun hash(text: String): String {
        val alphabet = "0123456789abcdef"
        return buildString(64) { MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).forEach {
            val value = it.toInt() and 255; append(alphabet[value ushr 4]); append(alphabet[value and 15])
        } }
    }
}
