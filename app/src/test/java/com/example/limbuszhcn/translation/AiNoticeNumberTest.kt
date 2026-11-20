package com.example.limbuszhcn.translation

import org.junit.Assert.*
import org.junit.Test

class AiNoticeNumberTest {
    @Test fun announcementDateMonthSurvivesGenerationAndCacheRoundTrip() {
        val source = "Period: Nov. 12th 2026 12:00 ~ Nov. 26th 2026 10:00 (KST)"
        val protector = ProtectedTranslation(source)
        val answer = "期间：⟦P1⟧年11月⟦P0⟧日 ⟦P2⟧:⟦P3⟧～⟦P5⟧年11月⟦P4⟧日 ⟦P6⟧:⟦P7⟧（KST）"
        val translated = protector.restore(answer)
        assertEquals("期间：2026年11月12日 12:00～2026年11月26日 10:00（KST）", translated)
        assertEquals(translated, protector.restore(protector.maskTranslated(translated)))
    }

    @Test fun monthMatchingDoesNotDependOnWhichIdenticalNumberGetsTheProtectedToken() {
        val p = ProtectedTranslation("Nov. 11th 2026")
        val answer = "⟦P1⟧年11月⟦P0⟧日"
        val translated = p.restore(answer)
        assertEquals("2026年11月11日", translated)
        assertEquals(translated, p.restore(p.maskTranslated(translated)))
    }

    @Test fun explicitEventAndChapterRomanNumbersAreEquivalent() {
        for ((source, answer) in listOf("WALPURGISNACHT-X Identities" to "第10届瓦尔普吉斯之夜人格",
                "Chapter IV" to "第4章", "Canto X" to "第10章")) {
            val p = ProtectedTranslation(source)
            assertEquals(answer, p.restore(answer))
            assertEquals(answer, p.restore(p.maskTranslated(answer)))
        }
    }

    @Test fun allMonthNamesAndAbbreviationsRequireAnActualDateAndMatchingMonthUnit() {
        val names = listOf("Jan.", "February", "Mar", "April", "May", "Jun.", "July", "Aug", "Sept.", "October", "Nov.", "December")
        names.forEachIndexed { index, name ->
            val p = ProtectedTranslation("$name 19th 2026")
            val expected = "2026年${index + 1}月19日"
            assertEquals(expected, p.restore("⟦P1⟧年${index + 1}月⟦P0⟧日"))
            assertEquals(expected, p.restore(p.maskTranslated(expected)))
        }
        val styled = ProtectedTranslation("<b>Nov.</b> <b>11th</b> 2026")
        val expected = "<b>11月</b> <b>11日</b> 2026"
        assertEquals(expected, styled.restore(styled.maskTranslated(expected)))
        val reverse = ProtectedTranslation("12 November 2026")
        assertEquals("2026年11月12日", reverse.restore("⟦P1⟧年11月⟦P0⟧日"))
    }

    @Test fun monthAndRomanRecognitionDoNotAllowInventedRewardsTimesOrOrdinaryLetters() {
        for ((source, answer) in listOf(
            "Nov. 12th 2026" to "⟦P1⟧年12月⟦P0⟧日",
            "Nov. 12th 2026" to "⟦P1⟧年11月⟦P0⟧日，额外获得11个奖励",
            "Nov. 12th 2026" to "⟦P1⟧年⟦P0⟧日，奖励11个",
            "Nov. 12th 2026 (KST)" to "⟦P1⟧年11月⟦P0⟧日 UTC+9",
            "You may begin" to "5月开始", "March forward" to "3月前进",
            "I work for X Corp" to "我在10号公司工作", "Chapter IIII" to "第4章",
            "<style=Chapter-X>Start</style>" to "⟦P0⟧第10章开始⟦P1⟧")) {
            assertEquals(source, AiValidationReason.AddedNumber, assertThrows(AiTextValidationException::class.java) {
                ProtectedTranslation(source).restore(answer)
            }.reason)
        }
    }

    @Test fun failuresIdentifyABoundedNumericValueWithoutIncludingResponseText() {
        val failure = assertThrows(AiTextValidationException::class.java) { ProtectedTranslation("Start").restore("private response 19") }
        assertTrue(failure.message!!.contains("19")); assertFalse(failure.message!!.contains("private response"))
        val long = assertThrows(AiTextValidationException::class.java) { ProtectedTranslation("Start").restore("12345678901234567890") }
        assertFalse(long.message!!.contains("1234567890"))
    }

    @Test fun interruptedConnectionIsActionableAndNeverAutomaticallyRetried() {
        var calls = 0; val usages = mutableListOf<AiUsage>()
        val client = AiTranslationClient(AiConfig(), "non-secret-fixture", AiHttpTransport { _, _, _ ->
            calls++; throw java.net.SocketException("Software caused connection abort private endpoint")
        })
        val error = assertThrows(AiTranslationException::class.java) {
            client.translate(listOf(AiText("one", "Confirm")), onUsage = usages::add)
        }
        assertEquals(1, calls); assertEquals(1, usages.size); assertFalse(usages.single().known)
        assertTrue(error.message!!.contains("连接已中断")); assertTrue(error.message!!.contains("已保存"))
        assertFalse(error.message!!.contains("private")); assertNull(error.cause)
    }
}
