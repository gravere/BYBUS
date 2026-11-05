package com.example.limbuszhcn

import org.junit.Assert.*
import org.junit.Test

class LauncherLayoutTest {
    @Test fun portraitTabletsAndSquareWindowsStayInOneColumn() {
        for ((width, height) in listOf(600f to 960f, 800f to 1280f, 1080f to 1600f, 1200f to 1200f)) {
            val layout = LauncherLayout.calculate(width, height, 1f)
            assertFalse("$width x $height", layout.twoColumns)
            assertTrue(layout.centeredDetails)
        }
    }

    @Test fun landscapeStillRequiresEnoughRoomAfterTextScaling() {
        assertTrue(LauncherLayout.calculate(1280f, 800f, 1f).twoColumns)
        assertTrue(LauncherLayout.calculate(1280f, 800f, 1.5f).twoColumns)
        assertFalse(LauncherLayout.calculate(1280f, 800f, 1.8f).twoColumns)
        assertFalse(LauncherLayout.calculate(800f, 600f, 1.3f).twoColumns)
        assertFalse(LauncherLayout.calculate(500f, 800f, 1f).twoColumns) // Split-screen window.
    }

    @Test fun middleActionCannotConsumeSpaceReservedForReadableCards() {
        for (width in listOf(800f, 900f, 960f, 1024f, 1280f, 1600f)) {
            for (scale in listOf(1f, 1.3f, 1.5f, 2f)) {
                val layout = LauncherLayout.calculate(width, 600f, scale)
                if (layout.twoColumns) {
                    val card = (minOf(width, layout.contentMaxWidth) - layout.margin * 2 -
                        layout.columnGap * 2 - layout.actionWidth) / 2
                    assertTrue("$width at $scale", card >= 256 * scale)
                    assertTrue(layout.actionWidth >= minOf(260 * scale, 360f) + 52)
                }
            }
        }
    }
}
