package com.example.limbuszhcn.translation

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiBillingCheckpointTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun switchingRatesAccumulatesOnlyEachSegmentsUsageAndKeepsCurrenciesSeparateAfterRestart() {
        val first = AiProgress(inputTokens = 1_000_000, outputTokens = 200_000, cachedTokens = 100_000,
            requests = 2, price = AiPrice(2.0, 5.0, 0.5))
        assertEquals(2.85, first.knownCosts().getValue("USD"), 0.00001)
        val second = first.withNextPrice(AiPrice(10.0, 20.0)).copy(inputTokens = 2_000_000, outputTokens = 300_000, requests = 3)
        assertEquals(14.85, second.knownCosts().getValue("USD"), 0.00001)
        val third = second.withNextPrice(AiPrice(1.0, 2.0, currency = "CNY"))
            .copy(inputTokens = 3_000_000, outputTokens = 400_000, requests = 4)
        assertEquals(14.85, third.knownCosts().getValue("USD"), 0.00001)
        assertEquals(1.2, third.knownCosts().getValue("CNY"), 0.00001)
        assertFalse(third.hasUnknownPrice())
        val path = temp.root.toPath()
        AiUsageStore(path).save(third)
        assertEquals(third, AiUsageStore(path).read())
    }
    @Test fun laterPriceCannotRepriceUnknownEarlierRequestsOrEraseMissingUsage() {
        val unknown = AiProgress(inputTokens = 1000, outputTokens = 100, requests = 1, unknownUsage = true)
        val priced = unknown.withNextPrice(AiPrice(2.0, 8.0)).copy(inputTokens = 2000, outputTokens = 200, requests = 2)
        assertEquals(0.0028, priced.knownCosts().getValue("USD"), 0.0000001)
        assertTrue(priced.hasUnknownPrice()); assertTrue(priced.unknownUsage)
        val unknownAgain = priced.withNextPrice(null).copy(requests = 3)
        assertEquals(priced.knownCosts(), unknownAgain.knownCosts())
        assertTrue(unknownAgain.hasUnknownPrice())
    }
    @Test fun switchingBackBeforeAnyRequestDoesNotDuplicatePreviousCharges() {
        val original = AiProgress(inputTokens = 1000000, requests = 1, price = AiPrice(2.0, 4.0))
        val changed = original.withNextPrice(AiPrice(99.0, 100.0)).withNextPrice(original.price)
        assertEquals(original.knownCosts(), changed.knownCosts())
        assertEquals(2.0, changed.knownCosts().getValue("USD"), 0.00001)
        assertFalse(changed.hasUnknownPrice())
    }
}
