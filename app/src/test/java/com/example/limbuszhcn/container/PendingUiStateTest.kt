package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.PendingUiState
import org.junit.Assert.*
import org.junit.Test

class PendingUiStateTest {
    @Test fun recreationWaitsForEachExistingChildAndAcceptsItsResultOnce() {
        listOf(PendingUiState.GOOGLE_IDENTITY, PendingUiState.BILLING).forEach { code ->
            val original = PendingUiState().apply { begin(code) }
            val restored = PendingUiState().apply { restore(original.savedRequest()) }
            assertTrue(restored.waiting())
            assertFalse(restored.finish(1234))
            assertTrue(restored.waiting())
            assertTrue(restored.finish(code))
            assertFalse(restored.waiting())
            assertFalse(restored.finish(code))
            val next = PendingUiState().apply { restore(restored.savedRequest()) }
            assertFalse(next.waiting())
        }
    }

    @Test fun corruptStateCannotLeaveTheBridgeWaiting() {
        val restored = PendingUiState().apply { restore(Int.MAX_VALUE) }
        assertFalse(restored.waiting())
        restored.begin(PendingUiState.GOOGLE_IDENTITY)
        assertTrue(restored.finish(PendingUiState.GOOGLE_IDENTITY))
    }
}
