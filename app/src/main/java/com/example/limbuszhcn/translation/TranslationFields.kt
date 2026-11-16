package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.TranslationJsonPairer
import com.example.limbuszhcn.container.TranslationTextPolicy
import org.json.JSONArray
import org.json.JSONObject

/** One definition of display leaves for both the coverage report and AI supplement jobs. */
internal data class TranslationField(val id: String, val text: String, val context: String,
    val covered: Boolean, val translated: String?, val clear: () -> Unit, val apply: (String) -> Unit) {
    val canTranslate get() = text.length <= 6000 && !text.contains(Regex("⟦P[0-9]+⟧"))
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
            clear: () -> Unit, apply: (String) -> Unit) {
            if (before.isBlank() || !TranslationTextPolicy.isUsableDisplayText(before) || !before.any(Char::isLetter)) return
            result += TranslationField(AiTranslationJob.digest("$resource|$path|$before"), before,
                "$resource $path", covered, translated.takeIf { covered }, clear, apply)
        }
        fun visit(source: Any, destination: Any, pointer: String, depth: Int, displayArray: Boolean = false) {
            require(depth <= 64) { "文本结构过深，无法检查" }
            when {
                source is JSONObject && destination is JSONObject -> source.keys().asSequence().toList().sorted().forEach { field ->
                    val before = source.get(field); val after = destination.opt(field)
                    val path = "$pointer/${field.replace("~", "~0").replace("/", "~1")}"
                    if (before is String && after is String && TranslationTextPolicy.isDisplayField(field)) {
                        leaf(path, before, after, baseline?.contains(destination, field) == true,
                            { destination.remove(field); Unit }, { destination.put(field, it) })
                    } else if ((before is JSONObject || before is JSONArray) && after != null)
                        visit(before, after, path, depth + 1, field in TranslationTextPolicy.STRING_ARRAY_FIELDS)
                }
                source is JSONArray && destination is JSONArray -> {
                    require(source.length() == destination.length()) { "游戏文本结构发生变化，请重新检查" }
                    for (i in 0 until source.length()) {
                        val before = source.get(i); val after = destination.get(i)
                        if (displayArray && before is String && after is String)
                            leaf("$pointer/$i", before, after, baseline?.contains(destination, i.toString()) == true,
                                { destination.put(i, ""); Unit }, { destination.put(i, it) })
                        else if (before is JSONObject || before is JSONArray) visit(before, after, "$pointer/$i", depth + 1)
                    }
                }
            }
        }
        visit(original, target, "", 0)
        return result
    }
}
