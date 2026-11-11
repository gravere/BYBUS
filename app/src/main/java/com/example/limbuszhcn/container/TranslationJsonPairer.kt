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

    /** Merge only the display leaves accepted by the exact same identity policy as the index. */
    fun merge(path: String, originalJson: String, translationJson: String): String? {
        val original = parse(originalJson)
        val translation = parse(translationJson)
        val records = mutableListOf<TranslationIndexRecord>()
        val edits = mutableListOf<() -> Unit>()
        collect(original, translation, path, "$", records, 0, edits = edits)
        edits.forEach { it() }
        return if (records.any { it.source != it.translation }) original.toString() else null
    }

    fun releasedTexts(json: String): List<String> {
        // This catalogue proves font ownership, not a source-to-target mapping.
        // A duplicate identity cannot invalidate a complete released Chinese label.
        val texts = mutableListOf<String>()
        fun collectReleased(value: Any?, depth: Int, displayArray: String? = null) {
            require(depth <= 64) { "Resource nesting exceeds limit" }
            when (value) {
                is JSONObject -> value.keys().forEach { field ->
                    val child = value.opt(field)
                    if (child is String) {
                        if (TranslationTextPolicy.isDisplayField(field) && child.isNotBlank() &&
                            TranslationTextPolicy.isUsableDisplayText(child)) texts += child
                    } else collectReleased(child, depth + 1,
                        field.takeIf { it in TranslationTextPolicy.STRING_ARRAY_FIELDS })
                }
                is JSONArray -> (0 until value.length()).forEach {
                    collectReleased(value.opt(it), depth + 1, displayArray)
                }
                is String -> if (displayArray != null && value.isNotBlank() &&
                    TranslationTextPolicy.isUsableDisplayText(value)) texts += value
            }
        }
        collectReleased(parse(json), 0)
        return texts
    }

    private fun parse(text: String): Any {
        val tokener = JSONTokener(text.removePrefix("\uFEFF"))
        val root = tokener.nextValue()
        require(root is JSONObject || root is JSONArray) { "Resource root must be an object or array" }
        require(tokener.nextClean() == '\u0000') { "Trailing data in resource" }
        return root
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
                        displayArray: String? = null, arrayOwnerId: String? = null,
                        skillContext: String? = null, skillRelation: String? = null,
                        objectScope: String? = null, edits: MutableList<() -> Unit>? = null) {
        require(depth <= 64) { "Resource nesting exceeds limit" }
        when {
            original is JSONObject && translated is JSONObject -> {
                val identity = identity(original)
                if (identity != null && identity != identity(translated)) return
                val objectContext = identity?.let { "$context[$it]" } ?: context
                val directId = listOf("id", "key").firstNotNullOfOrNull { name ->
                    original.opt(name).takeIf { it is String || it is Number }?.toString()
                }
                val skillRoot = skillContext ?: directId?.takeIf { id ->
                    id.isSkillNumber() && original.optJSONArray("levelList") != null &&
                        path.substringAfterLast('/').removePrefix("JP_").let {
                            it == "Skills.json" || it.startsWith("Skills_")
                        }
                }?.let { "@skill:$it" }
                val tableScope = objectScope ?: directId?.let { id ->
                    when {
                        id.isSkillNumber() && belongsTo(path, "EGOgift") -> "@ego-gift:$id"
                        id.isSkillNumber() && belongsTo(path, "ActionEvents") -> "@action-event:$id"
                        id.isSkillNumber() && belongsTo(path, "AbnormalityGuides") -> "@abnormality-guide:$id"
                        id.length in 1..96 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_." } &&
                            belongsTo(path, "rpg-loc-dialogue") -> "@rpg-dialogue:$id"
                        else -> null
                    }
                }
                val storyScope = path.takeIf { it.substringBeforeLast('/', "").endsWith("/StoryData") }
                        ?.substringAfterLast('/')?.removePrefix("JP_")?.removeSuffix(".json")
                        ?.takeIf { it.length in 1..96 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_-" } }
                        ?.let { scene -> "@story:$scene" + (directId?.takeIf { it.isSkillNumber() }?.let { "/dialog:$it" } ?: "") }
                val stableId = skillRoot ?: tableScope ?: storyScope ?: directId?.let { tableIdentity(path, it) }
                original.keys().forEach { field ->
                    val source = original.opt(field)
                    val target = translated.opt(field)
                    if (field == "simpleDesc" && tableScope?.startsWith("@ego-gift:") == true && !tableScope.contains('/')) {
                        if (source is JSONArray && target is JSONArray) {
                            collectGiftAbilities(source, target, path, "$objectContext.$field",
                                records, depth + 1, tableScope, edits)
                        }
                        return@forEach
                    }
                    val eventOptions = field == "options" && tableScope?.startsWith("@action-event:") == true
                    val guideStories = field == "storyList" && tableScope?.startsWith("@abnormality-guide:") == true
                    val rpgDialogue = field == "texts" && tableScope?.startsWith("@rpg-dialogue:") == true
                    if ((eventOptions || guideStories || rpgDialogue) && tableScope != null && !tableScope.contains('/')) {
                        if (source is JSONArray && target is JSONArray) {
                            collectTableChildren(source, target, path, "$objectContext.$field", records,
                                depth + 1, tableScope, if (guideStories) "level" else if (rpgDialogue) "index" else null, edits)
                        }
                        return@forEach
                    }
                    if (source is String && target is String && source.isNotBlank() && target.isNotBlank() &&
                        TranslationTextPolicy.isDisplayField(field) &&
                        TranslationTextPolicy.isUsableDisplayText(source) && TranslationTextPolicy.isUsableDisplayText(target)) {
                        records += TranslationIndexRecord(path, objectContext, field, source, target, stableId)
                        edits?.add { original.put(field, target) }
                    } else if (source is JSONObject || source is JSONArray) {
                        collect(source, target, path, "$objectContext.$field", records, depth + 1,
                            field.takeIf { it in TranslationTextPolicy.STRING_ARRAY_FIELDS }, stableId,
                            skillRoot.takeIf { field.lowercase() in SKILL_CHILD_FIELDS }, field.lowercase(), edits = edits)
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
                            edits?.add { original.put(index, target) }
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
                    val nestedSkill = skillContext?.let { parent ->
                        when (skillRelation) {
                            "levellist" -> (source as? JSONObject)?.opt("level")?.toString()
                                ?.takeIf { it.isSkillNumber() }?.let { "$parent/level:$it" }
                            "coinlist" -> "$parent/coin:$index"
                            "coindescs" -> "$parent/text:$index"
                            else -> null
                        }
                    }?.takeIf { it.length <= 256 }
                    collect(source, target, path, "$context[$index]", records, depth + 1,
                        skillContext = nestedSkill, edits = edits)
                }
            }
        }
    }

    private fun collectGiftAbilities(original: JSONArray, translated: JSONArray, path: String,
                                     context: String, records: MutableList<TranslationIndexRecord>,
                                     depth: Int, parent: String, edits: MutableList<() -> Unit>?) {
        fun groups(array: JSONArray) = (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { row ->
                row.opt("abilityID")?.toString()?.takeIf { it.isSkillNumber() }?.let { it to row }
            }
        }.groupBy({ it.first }, { it.second })
        val sources = groups(original)
        val targets = groups(translated)
        for (index in 0 until original.length()) {
            val source = original.optJSONObject(index) ?: continue
            val id = source.opt("abilityID")?.toString()?.takeIf { it.isSkillNumber() } ?: continue
            val sourceGroup = sources.getValue(id)
            val targetGroup = targets[id].orEmpty()
            if (targetGroup.isEmpty() || sourceGroup.any { !equivalentJson(it, source) }) continue
            val target = targetGroup.first()
            if (targetGroup.any { !equivalentJson(it, target) }) continue
            // Ability IDs are metadata, not a license to pair changed structures.
            if (!equivalentJson(nonDisplayMetadata(source), nonDisplayMetadata(target))) continue
            collect(source, target, path, "$context[$index]", records, depth + 1,
                objectScope = "$parent/ability:$id", edits = edits)
        }
    }

    private fun collectTableChildren(original: JSONArray, translated: JSONArray, path: String,
                                     context: String, records: MutableList<TranslationIndexRecord>,
                                     depth: Int, parent: String, identityField: String?, edits: MutableList<() -> Unit>?) {
        if (identityField == null && original.length() != translated.length()) return
        fun identities(array: JSONArray) = (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { row ->
                identityField?.let(row::opt)?.toString()?.takeIf { it.isSkillNumber() }?.let { it to row }
            }
        }.groupBy({ it.first }, { it.second })
        val sources = if (identityField != null) identities(original) else emptyMap()
        val targets = if (identityField != null) identities(translated) else emptyMap()
        for (index in 0 until original.length()) {
            val source = original.optJSONObject(index) ?: continue
            val identity = identityField?.let(source::opt)?.toString()
            val target = if (identityField != null) {
                if (identity == null || sources[identity]?.size != 1) continue
                targets[identity]?.singleOrNull() ?: continue
            } else translated.optJSONObject(index) ?: continue
            if (!equivalentJson(nonDisplayMetadata(source), nonDisplayMetadata(target))) continue
            val key = when (identityField) {
                "level" -> "$parent/level:$identity"
                "index" -> "$parent/text:$identity"
                else -> "$parent/option:$index"
            }
            collect(source, target, path, "$context[$index]", records, depth + 1, objectScope = key, edits = edits)
        }
    }

    private fun nonDisplayMetadata(row: JSONObject) = JSONObject().apply {
        row.keys().forEach { key -> if (!TranslationTextPolicy.isDisplayField(key)) put(key, row.get(key)) }
    }

    private fun equivalentJson(left: Any?, right: Any?, depth: Int = 0): Boolean {
        if (depth > 64) return false
        return when {
            left is JSONObject && right is JSONObject -> {
                val keys = left.keys().asSequence().toSet()
                keys == right.keys().asSequence().toSet() &&
                    keys.all { equivalentJson(left.opt(it), right.opt(it), depth + 1) }
            }
            left is JSONArray && right is JSONArray -> left.length() == right.length() &&
                (0 until left.length()).all { equivalentJson(left.opt(it), right.opt(it), depth + 1) }
            else -> left == right
        }
    }

    private fun String.isSkillNumber(): Boolean = length in 1..18 && all { it in '0'..'9' }
    private val SKILL_CHILD_FIELDS = setOf("levellist", "coinlist", "coindescs")

    private fun tableIdentity(path: String, id: String): String {
        return when {
            belongsTo(path, "Bufs") -> "@buf:$id"
            belongsTo(path, "BattleKeywords") -> "@battle-keyword:$id"
            else -> id
        }
    }

    private fun belongsTo(path: String, family: String): Boolean {
        val file = path.substringAfterLast('/').removePrefix("JP_")
        return file == "$family.json" || file.startsWith("${family}_") || file.startsWith("$family-")
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
