package com.example.limbuszhcn

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchRingTest {
    @Test fun movingArcKeepsItsLengthWhileCrossingTheLoopBoundary() {
        val ring = Path().apply { addOval(Rect(0f, 0f, 320f, 80f)) }
        val measure = PathMeasure().apply { setPath(ring, true) }
        for (phase in listOf(0f, .5f, .749f, .9f, .99f, .9999f, 1f, 1.0001f)) {
            val output = Path()
            wrappedProgressArc(measure, phase, .25f, output)
            val arc = PathMeasure().apply { setPath(output, false) }
            assertEquals("phase=$phase", measure.length * .25f, arc.length, 1f)
        }
    }
}
