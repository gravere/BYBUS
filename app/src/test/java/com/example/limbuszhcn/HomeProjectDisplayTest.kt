package com.example.limbuszhcn

import com.example.limbuszhcn.translation.SourceState
import com.example.limbuszhcn.translation.TranslationSource
import com.example.limbuszhcn.translation.TranslationSources
import org.junit.Assert.*
import org.junit.Test

class HomeProjectDisplayTest {
    private val local = TranslationSource("local-one", "本地包")
    private val ai = TranslationSource("ai-one", "AI 译文", kind = "ai")
    private val sources = SourceState(listOf(TranslationSources.OFFICIAL, local, ai), local.id)

    @Test fun followingTracksTheGameSourceAndPinningDoesNotChangeIt() {
        assertEquals(local, HomeProjectDisplay.resolve(sources, null).source)
        assertEquals(ai, HomeProjectDisplay.resolve(sources.copy(selectedId = ai.id), null).source)
        val fixed = HomeProjectDisplay.resolve(sources, "zero")
        assertTrue(fixed.pinned)
        assertTrue(fixed.source.isOfficial)
        assertEquals(local.id, sources.selectedId)
        assertEquals(fixed, HomeProjectDisplay.resolve(sources.copy(selectedId = ai.id), "zero"))
    }

    @Test fun removedPinFallsBackAndExistingPinsUseCurrentNamesAndIcons() {
        val missing = HomeProjectDisplay.resolve(sources, "local-deleted")
        assertFalse(missing.pinned)
        assertEquals(local, missing.source)
        val renamed = local.copy(name = "新名称", icon = "changed.png#new")
        val changed = sources.copy(sources = listOf(TranslationSources.OFFICIAL, renamed))
        assertEquals(renamed, HomeProjectDisplay.resolve(changed, local.id).source)
    }
}
