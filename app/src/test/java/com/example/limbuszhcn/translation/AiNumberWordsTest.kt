package com.example.limbuszhcn.translation

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class AiNumberWordsTest {
    @Test fun equivalentWrittenQuantitiesDoNotRejectAnOtherwiseValidTranslation() {
        val cases = listOf(
            "Attack two enemies" to "攻击2个敌人",
            "No more than twelve times" to "不超过12次",
            "The twenty-first district" to "第21区",
            "one hundred and five times" to "105次",
            "two thousand and one people" to "2001人",
            "The first and second coins" to "第1枚和第2枚硬币",
            "三回使用できる" to "可使用3次",
            "두 명에게 적용" to "对2人施加",
            "Can be used twice" to "可使用2次",
            "zero stacks" to "0层")
        cases.forEach { (source, answer) ->
            val p = ProtectedTranslation(source)
            assertEquals(source, answer, p.restore(answer))
            assertEquals(answer, p.restore(p.maskTranslated(answer)))
        }
    }

    @Test fun originalNumericTokensAreNotAnAllowanceForExtraNumbers() {
        val p = ProtectedTranslation("Deal 2 damage to two enemies, then attack one enemy. {0}%")
        val answer = "对2个敌人造成⟦P0⟧点伤害，然后攻击1个敌人。⟦P1⟧%"
        assertEquals("对2个敌人造成2点伤害，然后攻击1个敌人。{0}%", p.restore(answer))
        assertEquals(p.restore(answer), p.restore(p.maskTranslated(p.restore(answer))))
        assertEquals(AiValidationReason.AddedNumber,
            assertThrows(AiTextValidationException::class.java) { p.restore(answer + "追加2次") }.reason)
        assertEquals(AiValidationReason.Tokens,
            assertThrows(AiTextValidationException::class.java) { p.restore(answer.replace("⟦P0⟧", "2")) }.reason)
    }

    @Test fun inventedDifferentDuplicateSignedDecimalAndPercentQuantitiesStillFail() {
        for (answer in listOf("3次", "2次再2次", "+2次", "-2次", "2.0次", "2%", "02次", "２％")) {
            assertEquals(answer, AiValidationReason.AddedNumber,
                assertThrows(AiTextValidationException::class.java) { ProtectedTranslation("Attack two enemies").restore(answer) }.reason)
        }
        for (source in listOf("Someone attacks", "one million enemies", "minus two", "negative two")) {
            val answer = if (source.contains("two")) "2" else "1"
            assertThrows(AiTextValidationException::class.java) { ProtectedTranslation(source).restore(answer) }
        }
        for (source in listOf("-二", "マイナス二", "마이너스 두 명", "-two", "negative double")) {
            assertThrows(AiTextValidationException::class.java) { ProtectedTranslation(source).restore("2次") }
        }
    }

    @Test fun wordsInsideProtectedFormattingDoNotDonateQuantities() {
        val p = ProtectedTranslation("<style=one>[Two]{three}Attack</style>")
        assertEquals(AiValidationReason.AddedNumber, assertThrows(AiTextValidationException::class.java) {
            p.restore(p.masked.replace("Attack", "攻击1次"))
        }.reason)
    }

    @Test fun exactOlderNonAsciiNumericSpellingStillSurvivesCacheRoundTrip() {
        for (source in listOf("２回 {0}", "２％の確率", "+２回", "１．５倍")) {
            val p = ProtectedTranslation(source)
            assertEquals(source, p.restore(p.masked))
            assertEquals(source, p.restore(p.maskTranslated(source)))
        }
    }

    @Test fun timeoutHasAnActionableMessageAndNeverSilentlyRetries() {
        var calls = 0; val usages = mutableListOf<AiUsage>()
        val client = AiTranslationClient(AiConfig(), "non-secret-fixture", AiHttpTransport { _, _, _ ->
            calls++; throw SocketTimeoutException("private provider endpoint")
        })
        val failure = assertThrows(AiTranslationException::class.java) {
            client.translate(listOf(AiText("one", "Confirm")), onUsage = usages::add)
        }
        assertEquals(1, calls); assertEquals(1, usages.size); assertFalse(usages.single().known)
        assertTrue(failure.message!!.contains("响应超时")); assertFalse(failure.message!!.contains("private provider"))
        assertNull(failure.cause)
    }
}
