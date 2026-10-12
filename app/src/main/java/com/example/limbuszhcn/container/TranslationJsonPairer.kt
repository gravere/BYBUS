package com.example.limbuszhcn.container

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Pure pairing policy: no filesystem, Android lifecycle or activation side effects. */
internal object TranslationJsonPairer {
    data class Result(val records: List<TranslationIndexRecord>, val sourceFields: Int, val matchedFields: Int) {
        val sufficientlyPaired: Boolean get() = sourceFields == 0 || matchedFields * 100L >= sourceFields * 90L
    }

    fun pair(path: String, originalJson: String, translationJson: String): Result {
        fun parse(text: String): Any {
            val tokener = JSONTokener(text.removePrefix("\uFEFF"))
            val root = tokener.nextValue()
            require(root is JSONObject || root is JSONArray) { "Resource root must be an object or array" }
            require(tokener.nextClean() == '\u0000') { "Trailing data in resource" }
            return root
        }
        val original = parse(originalJson)
        val translation = parse(translationJson)
        val records = mutableListOf<TranslationIndexRecord>()
        val sourceFields = countFields(original)
        if (sourceFields == 0) {
            require(isExplicitlyEmpty(original) && isExplicitlyEmpty(translation)) { "Unrecognized empty resource" }
        }
        collect(original, translation, path, "$", records, 0)
        return Result(records, sourceFields, records.size)
    }

    private fun isExplicitlyEmpty(value: Any): Boolean = when (value) {
        is JSONArray -> value.length() == 0
        is JSONObject -> value.optJSONArray("dataList")?.length() == 0
        else -> false
    }

    private fun countFields(value: Any?, depth: Int = 0, displayArray: String? = null): Int {
        require(depth <= 64) { "Resource nesting exceeds limit" }
        return when (value) {
            is JSONObject -> value.keys().asSequence().sumOf { field ->
                val child = value.opt(field)
                if (child is String && child.isNotBlank() && TranslationTextPolicy.isDisplayField(field)) 1
                else countFields(child, depth + 1, field.takeIf { it in TranslationTextPolicy.STRING_ARRAY_FIELDS })
            }
            is JSONArray -> (0 until value.length()).sumOf { countFields(value.opt(it), depth + 1, displayArray) }
            is String -> if (displayArray != null && value.isNotBlank()) 1 else 0
            else -> 0
        }
    }

    private fun collect(original: Any?, translated: Any?, path: String, context: String,
                        records: MutableList<TranslationIndexRecord>, depth: Int,
                        displayArray: String? = null, arrayOwnerId: String? = null) {
        require(depth <= 64) { "Resource nesting exceeds limit" }
        when {
            original is JSONObject && translated is JSONObject -> {
                val identity = identity(original)
                if (identity != null && identity != identity(translated)) return
                val objectContext = identity?.let { "$context[$it]" } ?: context
                val stableId = listOf("id", "key").firstNotNullOfOrNull { name ->
                    original.opt(name).takeIf { it is String || it is Number }?.toString()
                }
                original.keys().forEach { field ->
                    val source = original.opt(field)
                    val target = translated.opt(field)
                    if (source is String && target is String && source.isNotBlank() && target.isNotBlank() &&
                        TranslationTextPolicy.isDisplayField(field) &&
                        TranslationTextPolicy.isUsableDisplayText(source) && TranslationTextPolicy.isUsableDisplayText(target)) {
                        records += TranslationIndexRecord(path, objectContext, field, source, target, stableId)
                    } else if (source is JSONObject || source is JSONArray) {
                        collect(source, target, path, "$objectContext.$field", records, depth + 1,
                            field.takeIf { it in TranslationTextPolicy.STRING_ARRAY_FIELDS }, stableId)
                    }
                }
            }
            original is JSONArray && translated is JSONArray -> {
                if (displayArray != null) {
                    if (original.length() != translated.length() ||
                        (0 until original.length()).any { original.opt(it) !is String } ||
                        (0 until translated.length()).any { translated.opt(it) !is String }) return
                    for (index in 0 until original.length()) {
                        val source = original.getString(index)
                        val target = translated.getString(index)
                        if (source.isNotBlank() && target.isNotBlank() &&
                            TranslationTextPolicy.isUsableDisplayText(source) &&
                            TranslationTextPolicy.isUsableDisplayText(target)) {
                            records += TranslationIndexRecord(path, "$context[$index]", displayArray,
                                source, target, arrayOwnerId)
                        }
                    }
                    return
                }
                fun identities(array: JSONArray) = (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { item -> identity(item)?.let { it to item } }
                }.groupBy({ it.first }, { it.second })
                val sources = identities(original)
                val targets = identities(translated)
                for (index in 0 until original.length()) {
                    val source = original.opt(index)
                    val key = (source as? JSONObject)?.let(::identity)
                    val target = if (key != null) {
                        val sourceGroup = sources[key].orEmpty()
                        val targetGroup = targets[key].orEmpty()
                        if (sourceGroup.size == 1 && targetGroup.size == 1) targetGroup.single()
                        else {
                            // Some official story rows repeat an id. A matching model/voice
                            // can identify one row without guessing by position or changing ids.
                            val metadata = stableMetadata(source as JSONObject)
                            if (metadata.isEmpty() || sourceGroup.count { stableMetadata(it) == metadata } != 1) null
                            else targetGroup.filter { stableMetadata(it) == metadata }.singleOrNull()
                        }
                    } else if (original.length() == translated.length()) {
                        translated.opt(index).takeUnless { it is JSONObject && identity(it) != null }
                    } else null
                    collect(source, target, path, "$context[$index]", records, depth + 1)
                }
            }
        }
    }

    private fun identity(value: JSONObject): String? = ID_FIELDS.firstNotNullOfOrNull { field ->
        value.opt(field).takeIf { it is String || it is Number }?.let { "$field=$it" }
    }
    private fun stableMetadata(value: JSONObject): Map<String, String> =
        listOf("model", "voicefile").mapNotNull { field ->
            (value.opt(field) as? String)?.takeIf(String::isNotBlank)?.let { field to it }
        }.toMap()
    private val ID_FIELDS = listOf("id", "key", "level", "index", "idx", "originalId")
}
