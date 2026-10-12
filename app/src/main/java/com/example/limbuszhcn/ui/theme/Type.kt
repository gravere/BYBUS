package com.example.limbuszhcn.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default, fontWeight = weight, fontSize = size.sp,
    lineHeight = line.sp, letterSpacing = 0.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

val Typography = Typography(
    displaySmall = text(30, 40, FontWeight.Medium),
    headlineLarge = text(28, 38, FontWeight.Medium),
    headlineMedium = text(26, 36, FontWeight.Medium),
    headlineSmall = text(22, 32, FontWeight.Medium),
    titleLarge = text(20, 28, FontWeight.SemiBold),
    titleMedium = text(16, 24, FontWeight.Medium),
    titleSmall = text(14, 20, FontWeight.Medium),
    bodyLarge = text(16, 26), bodyMedium = text(14, 22), bodySmall = text(12, 18),
    labelLarge = text(16, 24, FontWeight.Medium),
    labelMedium = text(12, 18, FontWeight.Medium),
    labelSmall = text(11, 16, FontWeight.Medium)
)
