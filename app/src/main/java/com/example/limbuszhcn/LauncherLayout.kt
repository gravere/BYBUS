package com.example.limbuszhcn

/** All measurements are available window dp, after safe insets; never physical screen pixels. */
internal data class LauncherLayout(
    val twoColumns: Boolean,
    val centeredDetails: Boolean,
    val compactHeight: Boolean,
    val contentMaxWidth: Float,
    val margin: Float,
    val columnGap: Float,
    val panelWidth: Float,
    val panelHeight: Float,
    val detailPadding: Float
) {
    companion object {
        fun calculate(width: Float, height: Float, fontScale: Float): LauncherLayout {
            val scale = fontScale.coerceAtLeast(1f)
            val margin = if (width < 360) 16f else if (width < 1200) 24f else 32f
            val gap = if (width < 1200) 24f else 32f
            val availableColumns = (minOf(width, 1320f) - margin * 2 - gap) / 2
            val twoColumns = width >= 760 && availableColumns >= 320 * scale
            val centered = width >= 600
            val compactHeight = height < 480
            return LauncherLayout(
                twoColumns, centered, compactHeight,
                if (twoColumns) 1320f else if (centered) 800f else 640f, margin, gap,
                minOf(560f * scale, 680f, (width - 48f).coerceAtLeast(0f)),
                minOf(height * (if (compactHeight) .84f else .78f), if (centered) 720f else 640f),
                if (width < 360) 16f else 24f
            )
        }
    }
}
