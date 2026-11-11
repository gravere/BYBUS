package com.example.limbuszhcn

import com.example.limbuszhcn.gamefs.PatchInstallSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationLaunchGateTest {
    @Test fun noFullIndexCannotSilentlyLaunchEvenWithAStartupIndex() {
        assertEquals(TranslationLaunchMode.NeedsResources, translationLaunchMode(null, false))
        assertEquals(TranslationLaunchMode.NeedsResources, translationLaunchMode(
            PatchInstallSummary("200", 0, indexActivated = false, startupIndexAvailable = true, sourceRecordCount = 35), false))
    }
    @Test fun preparationRequiresExplicitChoiceAndNeverClaimsLocalized() {
        assertEquals(TranslationLaunchMode.ResourcePreparation, translationLaunchMode(null, true))
    }
    @Test fun verifiedFullIndexTakesPrecedenceAfterResourcesArrive() {
        val full = PatchInstallSummary("200", 100, sourceRecordCount = 20000)
        assertEquals(TranslationLaunchMode.Localized, translationLaunchMode(full, false))
        assertEquals(TranslationLaunchMode.Localized, translationLaunchMode(full, true))
    }
}
