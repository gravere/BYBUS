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
        assertFalse(LauncherLayout.calculate(800f, 600f, 1.3f).twoColumns)
        assertFalse(LauncherLayout.calculate(500f, 800f, 1f).twoColumns) // Split-screen window.
    }
}
