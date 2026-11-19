package com.example.limbuszhcn.container

import com.example.limbuszhcn.gamefs.GameTextLanguage
import com.example.limbuszhcn.gamefs.GameTextLanguageState
import com.example.limbuszhcn.translation.TranslationSources
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Cross-process observation of the game's effective language, with no PlayerPrefs access. */
internal class GameLanguageStore(cache: Path) {
    private val file = cache.resolve("game-text-language")
    fun read(): GameTextLanguageState? = runCatching {
        if (!Files.isRegularFile(file) || Files.size(file) !in 1..80) return null
        val parts = String(Files.readAllBytes(file), Charsets.US_ASCII).split('|')
        if (parts.size != 2) return null
        val language = GameTextLanguage.fromCode(parts[0]) ?: return null
        val revision = UUID.fromString(parts[1]).toString()
        GameTextLanguageState(language, revision)
    }.getOrNull()

    @Synchronized fun observe(code: String) {
        val language = GameTextLanguage.fromCode(code) ?: return
        if (read()?.language == language) return
        TranslationSources.writeAtomic(file, "$code|${UUID.randomUUID()}")
    }
}
