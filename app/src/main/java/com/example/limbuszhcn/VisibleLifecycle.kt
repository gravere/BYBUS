package com.example.limbuszhcn

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** A paused activity can still be visible behind a dialog or the game's handoff window. */
@Composable
internal fun rememberLifecycleAtLeast(lifecycle: Lifecycle, minimum: Lifecycle.State): State<Boolean> {
    val active = remember(lifecycle, minimum) { mutableStateOf(lifecycle.currentState.isAtLeast(minimum)) }
    DisposableEffect(lifecycle, minimum) {
        val observer = LifecycleEventObserver { _, _ ->
            active.value = lifecycle.currentState.isAtLeast(minimum)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return active
}
