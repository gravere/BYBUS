package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.TranslationJsonPairer
import com.example.limbuszhcn.container.TranslationTextPolicy
import org.json.JSONArray
import org.json.JSONObject

/** One definition of display leaves for both the coverage report and AI supplement jobs. */
internal data class TranslationField(val id: String, val text: String, val context: String,
    val covered: Boolean, val translated: String?, val clear: () -> Unit, val apply: (String) -> Unit,
    val excludedReason: String? = null, val pointer: String = "", val recordId: String? = null) {
    val canTranslate get() = excludedReason == null && text.length <= 6000 && !text.contains(Regex("⟦P[0-9]+⟧"))
    val needsReview get() = covered && excludedReason == null && TranslationFields.hasResidualLanguage(translated.orEmpty())
}

internal object TranslationFields {
    fun parse(text: String): Any {
        val clean = text.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        return if (clean.startsWith('[')) JSONArray(clean) else JSONObject(clean)
    }

    fun collect(original: Any, target: Any, resource: String,
        baseline: TranslationJsonPairer.CoveredMerge?): List<TranslationField> {
        val result = mutableListOf<TranslationField>()
        fun leaf(path: String, before: String, translated: String, covered: Boolean,
            clear: () -> Unit, apply: (String) -> Unit, excluded: String?, speaker: String?, recordId: String?) {
            if (before.isBlank() || !TranslationTextPolicy.isUsableDisplayText(before) || !before.any(Char::isLetter)) return
            result += TranslationField(AiTranslationJob.digest("$resource|$path|$before"), before,
                "$resource $path" + speaker?.let { " | speaker=$it" }.orEmpty(), covered, translated.takeIf { covered },
                clear, apply, excluded, path, recordId)
        }
        fun visit(source: Any, destination: Any, pointer: String, depth: Int, displayArray: Boolean = false,
            excluded: String? = null, speaker: String? = null, inheritedId: String? = null) {
            require(depth <= 64) { "文本结构过深，无法检查" }
            val recordId = if (source is JSONObject) recordId(source) ?: inheritedId else inheritedId
            when {
                source is JSONObject && destination is JSONObject -> source.keys().asSequence().toList().sorted().forEach { field ->
                    val before = source.get(field); val after = destination.opt(field)
                    val who = listOf("teller", "speaker", "model").mapNotNull { (source.opt(it) as? String)?.takeIf { value -> value.isNotBlank() && value.length <= 80 } }.firstOrNull() ?: speaker
                    val path = "$pointer/${field.replace("~", "~0").replace("/", "~1")}"
                    val reason = excluded ?: if (source.keys().asSequence().any { key ->
                        TranslationTextPolicy.isDisplayField(key) && (source.opt(key) as? String)?.let(::unusedMarker) == true
                    }) "明确标为未使用的条目" else if (field == "desc" && com.example.limbuszhcn.container.sourceTableName(resource).startsWith("BattleSpeechBubbleDlg"))
                        "字幕内部备注" else if (resource.substringBeforeLast('/').endsWith("/StoryData") && source.optInt("id", 0) == -1 &&
                        source.optString("model").startsWith("//")) "剧情制作备注"
                    else if (before is String && (before.contains("name 만 번역해주세요") || before == "subDesc가 뭘까?"))
                        "明确的制作说明" else null
                    if (before is String && after is String && TranslationTextPolicy.isDisplayField(field)) {
                        leaf(path, before, after, baseline?.contains(destination, field) == true,
                            { destination.remove(field); Unit }, { destination.put(field, it) }, reason, who, recordId)
                    } else if ((before is JSONObject || before is JSONArray) && after != null)
                        visit(before, after, path, depth + 1, field in TranslationTextPolicy.STRING_ARRAY_FIELDS, reason, who, recordId)
                }
                source is JSONArray && destination is JSONArray -> {
                    require(source.length() == destination.length()) { "游戏文本结构发生变化，请重新检查" }
                    for (i in 0 until source.length()) {
                        val before = source.get(i); val after = destination.get(i)
                        if (displayArray && before is String && after is String)
                            leaf("$pointer/$i", before, after, baseline?.contains(destination, i.toString()) == true,
                                { destination.put(i, ""); Unit }, { destination.put(i, it) }, excluded, speaker, recordId)
                        else if (before is JSONObject || before is JSONArray) visit(before, after, "$pointer/$i", depth + 1,
                            excluded = excluded, speaker = speaker, inheritedId = recordId)
                    }
                }
            }
        }
        visit(original, target, "", 0)
        return result
    }

    private fun recordId(row: JSONObject): String? = recordKeys.firstNotNullOfOrNull { key ->
        row.opt(key)?.takeIf { it is String || it is Number }?.toString()?.takeIf { it.isNotBlank() }
    }
    private val recordKeys = listOf("id", "key", "originalId")

    private fun unusedMarker(text: String) = listOf("[未使用]", "[미사용]", "[Unused]", "[UNUSED]",
        "사용 안하는 텍스트", "사용하지않는스킬(번역x)").any { text.trimStart().startsWith(it) }
    private val MARKUP = Regex("<[^>]*>|\\[[A-Za-z][A-Za-z0-9_]*\\]|\\{[^{}]*\\}")
    fun hasResidualLanguage(text: String): Boolean {
        // These spellings are deliberately retained in released Chinese association names.
        val visible = MARKUP.replace(text, "").replace("し协会", "").replace("하나协会", "")
        return visible.any { it in '\u3041'..'\u3096' || it in '\u30A1'..'\u30FA' || it in '\uAC00'..'\uD7AF' }
    }
}
