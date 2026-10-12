package com.example.limbuszhcn.container

import org.junit.Assert.*
import org.junit.Test

class GameLaunchHandoffTest {
    private val game = "com.ProjectMoon.LimbusCompany"

    @Test fun requestAcceptanceAndUnrelatedCallbacksCannotCompleteStartup() {
        val handoff = GameLaunchHandoff(game)
        handoff.onAppOpened("com.google.android.gms", 0)
        handoff.onAppOpened(game, 1)
        assertFalse(handoff.isOpened(game, 0))
        assertThrows(ContainerRuntimeUnavailableException::class.java) { handoff.await(0) }
        handoff.onAppOpened(game, 0)
        handoff.await(0)
        assertTrue(handoff.isOpened(game, 0))
    }

    @Test fun timedOutLaunchCannotCompleteTheNextLaunch() {
        val previous = GameLaunchHandoff(game)
        assertThrows(ContainerRuntimeUnavailableException::class.java) { previous.await(0) }
        val current = GameLaunchHandoff(game)
        previous.onAppOpened(game, 0)
        assertFalse(current.isOpened(game, 0))
        assertThrows(ContainerRuntimeUnavailableException::class.java) { current.await(0) }
    }
}
