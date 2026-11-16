package com.example.limbuszhcn

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal object DetailPanelDragPolicy {
    /** Move the top edge past the viewport, including the gap below a centered panel. */
    fun travel(viewportHeight: Float, panelHeight: Float, centered: Boolean): Float =
        if (centered) (viewportHeight.coerceAtLeast(panelHeight) + panelHeight) / 2f else panelHeight

    fun scrimFraction(offset: Float, travel: Float): Float =
        if (travel <= 0f) 1f else (1f - offset / travel).coerceIn(0f, 1f)

    fun offset(current: Float, delta: Float, height: Float): Float =
        (current + delta).coerceIn(0f, height.coerceAtLeast(0f))

    /** Distances and velocity are in dp, independent of display density. */
    fun dismiss(distance: Float, height: Float, velocity: Float): Boolean {
        if (velocity < -200f) return false
        return distance >= (height * .2f).coerceIn(64f, 112f) ||
            (distance >= 24f && velocity >= 1000f)
    }
}

@Stable
internal class DetailPanelDrag(private val height: Float, private val density: Float,
    private val scope: CoroutineScope, private val travel: Float = height, private val onDismiss: () -> Unit) {
    var offset by mutableFloatStateOf(0f)
        private set
    private var animation: Job? = null
    val scrimFraction: Float get() = DetailPanelDragPolicy.scrimFraction(offset, travel)

    fun stop() { animation?.cancel(); animation = null }
    fun reset() { stop(); offset = 0f }

    fun drag(delta: Float): Float {
        stop()
        val before = offset
        offset = DetailPanelDragPolicy.offset(before, delta, travel)
        return offset - before
    }

    fun settle(velocity: Float) {
        stop()
        if (offset == 0f) return
        val dismiss = DetailPanelDragPolicy.dismiss(offset / density, height / density, velocity / density)
        animation = scope.launch {
            animate(offset, if (dismiss) travel else 0f, animationSpec = tween(220)) { value, _ ->
                offset = value.coerceIn(0f, travel)
            }
            if (dismiss) onDismiss()
        }
    }
}
