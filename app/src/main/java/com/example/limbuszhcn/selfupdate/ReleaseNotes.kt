package com.example.limbuszhcn.selfupdate

/** Old manifests contain one unmarked point per line; new ones keep Markdown bullets. */
private val listMarker = Regex("^(?:[-*•](?:\\s+|$)|\\d+[.)]\\s+|\\d+、\\s*)")

internal fun releaseNoteItems(notes: String): List<String> = notes.lineSequence()
    .map { it.trim().replaceFirst(listMarker, "").trim() }
    .filter(String::isNotEmpty)
    .toList()

internal enum class ReleaseNoteCategory(val label: String) {
    Added("新增"), Fixed("修复"), Improved("优化"), Updated("更新")
}

internal data class ReleaseNote(val category: ReleaseNoteCategory, val text: String)

/** Prefer explicit release-author labels; old unlabelled notes remain readable. */
internal fun categorizedReleaseNotes(notes: String): List<ReleaseNote> = releaseNoteItems(notes).map { text ->
    for (category in ReleaseNoteCategory.entries) {
        val label = category.label
        for (prefix in listOf("[$label]", "【$label】", "**$label**", "$label：", "$label:")) {
            if (text.startsWith(prefix)) {
                val body = text.removePrefix(prefix).trim().trimStart('：', ':').trim()
                if (body.isNotEmpty()) return@map ReleaseNote(category, body)
            }
        }
        if (text.startsWith(label) && text.length > label.length)
            return@map ReleaseNote(category, text.removePrefix(label).trim())
    }
    ReleaseNote(ReleaseNoteCategory.Updated, text)
}
