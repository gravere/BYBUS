package com.example.limbuszhcn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF254B40), onPrimary = Color(0xFFFAF9F5),
    secondary = Color(0xFF315A4C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EEE7), onSecondaryContainer = Color(0xFF466153),
    background = Color(0xFFF7F7F2), onBackground = Color(0xFF252C28),
    surface = Color(0xFFFEFEFA), onSurface = Color(0xFF252C28),
    surfaceVariant = Color(0xFFECEDE6), onSurfaceVariant = Color(0xFF616B63),
    outline = Color(0xFF8B958B), outlineVariant = Color(0xFFE0E4DA),
    error = Color(0xFFB33F37), errorContainer = Color(0xFFFBECE9),
    scrim = Color(0xFF171A22)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC3D5C4), onPrimary = Color(0xFF203B30),
    secondary = Color(0xFFB7CDBD), onSecondary = Color(0xFF213B30),
    secondaryContainer = Color(0xFF25352D), onSecondaryContainer = Color(0xFFB8CDBD),
    background = Color(0xFF151A17), onBackground = Color(0xFFE5E9DF),
    surface = Color(0xFF202722), onSurface = Color(0xFFE5E9DF),
    surfaceVariant = Color(0xFF2B352E), onSurfaceVariant = Color(0xFFAAB5A9),
    outline = Color(0xFF788377), outlineVariant = Color(0xFF354136),
    error = Color(0xFFFFB4AA), errorContainer = Color(0xFF422822),
    scrim = Color.Black
)

@Composable
fun LimbusZhCNTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = Typography, content = content)
}
