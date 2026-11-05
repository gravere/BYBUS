package com.example.limbuszhcn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** Allocate the full text height before scrolling; nested size animations clip long disclosures. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExpandableDetails(expanded: Boolean, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    AnimatedVisibility(expanded, enter = fadeIn(tween(160)), exit = fadeOut(tween(100))) {
        Column(modifier.bringIntoViewRequester(requester), content = content)
        LaunchedEffect(expanded) {
            if (expanded) {
                // Wait for placement, not a guessed animation delay. Rapid collapse cancels this request.
                withFrameNanos { }
                requester.bringIntoView()
            }
        }
    }
}
