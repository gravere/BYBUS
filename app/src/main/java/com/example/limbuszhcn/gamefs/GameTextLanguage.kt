package com.example.limbuszhcn.gamefs

enum class GameTextLanguage(val code: String, val displayName: String) {
    Japanese("jp", "日语"), English("en", "英语"), Korean("kr", "韩语");

    fun resourceKey(path: String): String {
        val relative = path.replace('\\', '/').removePrefix("Localize/")
            .removePrefix("jp/").removePrefix("en/").removePrefix("kr/")
        val directory = relative.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val table = relative.substringAfterLast('/').removePrefix("JP_").removePrefix("EN_").removePrefix("KR_")
        return "$code/$directory${code.uppercase(java.util.Locale.ROOT)}_$table"
    }

    companion object { fun fromCode(value: String?) = entries.firstOrNull { it.code == value } }
}

data class GameTextLanguageState(val language: GameTextLanguage, val revision: String)
