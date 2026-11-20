package com.example.limbuszhcn.translation

/** Limited lexical equivalence, not a claim of semantic translation quality. Never changes protected values. */
internal class AiNumberWords(source: String) {
    private val available: Map<Int, Int> = buildList {
        ENGLISH.findAll(source).forEach { match ->
            // Signed or larger unsupported expressions must not donate a smaller positive number.
            val before = source.substring(0, match.range.first).takeLast(16)
            val after = source.substring(match.range.last + 1).take(24)
            if (!SIGNED_WORD.containsMatchIn(before) && !LARGE_UNIT.containsMatchIn(after))
                add(englishValue(match.value))
        }
        CJK.findAll(source).filterNot { signedBefore(source, it) }.forEach { chineseValue(it.value)?.let(::add) }
        KOREAN.findAll(source).filterNot { signedBefore(source, it) }.forEach { korean[it.value]?.let(::add) }
        MULTIPLICITY.findAll(source).filterNot { signedBefore(source, it) }.forEach {
            add(when (it.value.lowercase(java.util.Locale.ROOT)) { "once", "single" -> 1; "twice", "double" -> 2; else -> 3 })
        }
    }.groupingBy { it }.eachCount()
    // Older tasks can contain unmasked full-width numbers. Preserve exact original spellings, including percent signs.
    private val literals = NUMBER.findAll(source).map { it.value }.groupingBy { it }.eachCount()

    fun validate(text: String, protectedRanges: List<IntRange>) {
        val remaining = available.toMutableMap()
        val literalRemaining = literals.toMutableMap()
        NUMBER.findAll(text).forEach { match ->
            if (protectedRanges.any { match.range.first <= it.last && match.range.last >= it.first }) return@forEach
            val literalCount = literalRemaining[match.value] ?: 0
            if (literalCount > 0) { literalRemaining[match.value] = literalCount - 1; return@forEach }
            val value = decimalValue(match.value)
            val count = value?.let { remaining[it] } ?: 0
            if (value == null || count == 0) throw AiTextValidationException(AiValidationReason.AddedNumber)
            remaining[value] = count - 1
        }
    }

    companion object {
        private val units = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        private val tens = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        private val ordinals = listOf("zeroth", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
            "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth")
        private val ordinalTens = listOf("twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth", "seventieth", "eightieth", "ninetieth")
        private val values = units.withIndex().associate { it.value to it.index } +
            tens.withIndex().associate { it.value to (it.index + 2) * 10 } +
            ordinals.withIndex().associate { it.value to it.index } + ordinalTens.withIndex().associate { it.value to (it.index + 2) * 10 }
        private val one = "(?:${units.drop(1).take(9).joinToString("|")})"
        private val under100 = "(?:(?:${tens.joinToString("|")})(?:[ -]+$one)?|${units.joinToString("|")})"
        private val under1000 = "(?:$one[ -]+hundred(?:[ -]+(?:and[ -]+)?$under100)?|$under100)"
        private val cardinal = "(?:$under1000[ -]+thousand(?:[ -]+(?:and[ -]+)?$under1000)?|$under1000)"
        private val ordinal = "(?:(?:${tens.joinToString("|")})[ -]+(?:${ordinals.drop(1).take(9).joinToString("|")})|${(ordinals + ordinalTens).joinToString("|")})"
        private val ENGLISH = Regex("(?i)(?<![a-z])(?:$ordinal|$cardinal)(?![a-z])")
        private val SIGNED_WORD = Regex("(?i)(?:minus|negative|plus|マイナス|마이너스|[+−-])[ -]*$")
        private val LARGE_UNIT = Regex("(?i)^[ -]+(?:million|billion|trillion|hundred|thousand)(?![a-z])")
        private val CJK = Regex("[零〇一二两三四五六七八九十百千万]+")
        private val MULTIPLICITY = Regex("(?i)(?<![a-z])(?:once|twice|thrice|single|double|triple)(?![a-z])")
        private val korean = mapOf("하나" to 1, "한" to 1, "둘" to 2, "두" to 2, "셋" to 3, "세" to 3,
            "넷" to 4, "네" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9, "열" to 10)
        private val KOREAN = Regex("(?<![가-힣])(?:${korean.keys.sortedByDescending { it.length }.joinToString("|")})(?=$|[\\s.,!?，。]|(?:개|명|번|회|턴|번째|개의))")
        private val NUMBER = Regex("[-+−]?[\\p{Nd}]+(?:[.,．][\\p{Nd}]+)*(?:[%％‰])?")

        private fun signedBefore(source: String, match: MatchResult) =
            SIGNED_WORD.containsMatchIn(source.substring(0, match.range.first).takeLast(16))

        private fun decimalValue(text: String): Int? {
            if (text.length !in 1..6 || text.length > 1 && Character.digit(text[0], 10) == 0) return null
            var value = 0
            for (c in text) { val d = Character.digit(c, 10); if (d !in 0..9) return null; value = value * 10 + d }
            return value
        }

        private fun englishValue(text: String): Int {
            var total = 0; var group = 0
            text.lowercase(java.util.Locale.ROOT).split(Regex("[ -]+")).forEach { word ->
                when (word) {
                    "and" -> Unit
                    "hundred" -> group *= 100
                    "thousand" -> { total += group * 1000; group = 0 }
                    else -> group += values.getValue(word)
                }
            }
            return total + group
        }

        private fun chineseValue(text: String): Int? {
            if (text.length > 12) return null
            val digits = "零一二三四五六七八九"
            val clean = text.replace('〇', '零').replace('两', '二')
            if (clean.all { it in digits }) return clean.map { digits.indexOf(it) }.joinToString("").toIntOrNull()?.takeIf { it <= 999999 }
            var total = 0; var group = 0; var digit = 0; var lastUnit = 10000
            for (c in clean) {
                if (c in digits) { if (digit != 0) return null; digit = digits.indexOf(c); continue }
                val unit = when (c) { '十' -> 10; '百' -> 100; '千' -> 1000; '万' -> 10000; else -> return null }
                if (unit == 10000) {
                    if (total != 0) return null
                    total = (group + digit).coerceAtLeast(1) * unit; group = 0; lastUnit = 10000
                } else {
                    if (unit >= lastUnit) return null
                    group += digit.coerceAtLeast(1) * unit; lastUnit = unit
                }
                digit = 0
            }
            return (total + group + digit).takeIf { it <= 999999 }
        }

    }
}
