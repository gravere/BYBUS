package com.example.limbuszhcn

import org.junit.Assert.*
import org.junit.Test

class DetailPanelDragPolicyTest {
    @Test fun upwardPullCannotMoveTheExpandedPanelOrOvershootItsBottom() {
        assertEquals(0f, DetailPanelDragPolicy.offset(0f, -120f, 600f), 0f)
        assertEquals(0f, DetailPanelDragPolicy.offset(40f, -120f, 600f), 0f)
        assertEquals(600f, DetailPanelDragPolicy.offset(500f, 500f, 600f), 0f)
    }

    @Test fun shortPullReturnsButDeliberatePullCloses() {
        assertFalse(DetailPanelDragPolicy.dismiss(40f, 600f, 100f))
        assertTrue(DetailPanelDragPolicy.dismiss(120f, 600f, 100f))
        assertFalse(DetailPanelDragPolicy.dismiss(40f, 260f, 0f))
        assertTrue(DetailPanelDragPolicy.dismiss(70f, 260f, 0f))
    }

    @Test fun fastDismissNeedsTravelAndAnUpwardReversalCancelsIt() {
        assertFalse(DetailPanelDragPolicy.dismiss(8f, 600f, 1800f))
        assertTrue(DetailPanelDragPolicy.dismiss(30f, 600f, 1200f))
        assertFalse(DetailPanelDragPolicy.dismiss(140f, 600f, -500f))
    }
}
