package com.example.limbuszhcn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal object LauncherColors {
    val Background: Color @Composable get() = MaterialTheme.colorScheme.background
    val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
    val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val Line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
    val Accent: Color @Composable get() = MaterialTheme.colorScheme.secondary
    val AccentSoft: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer
    val OnAccentSoft: Color @Composable get() = MaterialTheme.colorScheme.onSecondaryContainer
    val OnPrimary: Color @Composable get() = MaterialTheme.colorScheme.onPrimary
    val Error: Color @Composable get() = MaterialTheme.colorScheme.error
    val Ready: Color @Composable get() = if (Background.luminance() < .5f) Color(0xFF9DCCAE) else Color(0xFF32754A)
}
