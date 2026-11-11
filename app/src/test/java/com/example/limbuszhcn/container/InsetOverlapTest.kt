package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.InsetOverlap
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class InsetOverlapTest {
    @Test fun threeButtonsAndGestureBarsReserveTheirActualArea() {
        assertArrayEquals(intArrayOf(0, 24, 0, 48), InsetOverlap.calculate(400, 900, 0, 0, 400, 900, 0, 24, 0, 48))
        assertArrayEquals(intArrayOf(0, 24, 0, 16), InsetOverlap.calculate(400, 900, 0, 0, 400, 900, 0, 24, 0, 16))
    }
    @Test fun anAlreadyInsetContentFrameDoesNotReceiveDoublePadding() {
        assertArrayEquals(intArrayOf(0, 0, 0, 0), InsetOverlap.calculate(400, 900, 0, 24, 400, 828, 0, 24, 0, 48))
    }
    @Test fun landscapeSideNavigationAndCutoutAreHandledIndependently() {
        assertArrayEquals(intArrayOf(32, 0, 48, 0), InsetOverlap.calculate(900, 400, 0, 24, 900, 376, 32, 24, 48, 0))
    }
    @Test fun keyboardUsesOnlyOverlapRemainingAfterSystemResize() {
        assertArrayEquals(intArrayOf(0, 0, 0, 300), InsetOverlap.calculate(400, 900, 0, 24, 400, 876, 0, 24, 0, 300))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), InsetOverlap.calculate(400, 900, 0, 24, 400, 576, 0, 24, 0, 300))
    }
    @Test fun splitWindowAndNotYetLaidOutRootDoNotReservePhantomBars() {
        assertArrayEquals(intArrayOf(0, 0, 0, 0), InsetOverlap.calculate(400, 400, 0, 0, 400, 400, 0, 0, 0, 0))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), InsetOverlap.calculate(0, 0, 0, 0, 0, 0, 0, 24, 0, 48))
    }
}
