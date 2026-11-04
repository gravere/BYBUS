package com.example.limbuszhcn.selfupdate

/** Old manifests contain one unmarked point per line; new ones keep Markdown bullets. */
private val listMarker = Regex("^(?:[-*•](?:\\s+|$)|\\d+[.)]\\s+|\\d+、\\s*)")

internal fun releaseNoteItems(notes: String): List<String> = notes.lineSequence()
    .map { it.trim().replaceFirst(listMarker, "").trim() }
    .filter(String::isNotEmpty)
    .toList()
