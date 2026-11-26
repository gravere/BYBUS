package com.example.limbuszhcn

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import java.io.File

/** Local emulator preview of production resources. Never included in release builds. */
class DesktopIconProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        enableEdgeToEdge()
        exportPreview()
        setContent { LimbusZhCNTheme {
            Surface(Modifier.fillMaxSize(), color = com.example.limbuszhcn.ui.theme.LauncherColors.Background) {
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                    LauncherDetailSurface(LauncherLayout.calculate(maxWidth.value, maxHeight.value,
                        LocalDensity.current.fontScale), "desktop-icon", onDismiss = ::finish, onBack = ::finish) {
                        DesktopIconPane()
                    }
                }
            }
        } }
    }

    private fun exportPreview() {
        val output = Bitmap.createBitmap(1400, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(247, 247, 242))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 49, 43); textSize = 23f; textAlign = Paint.Align.CENTER
        }
        DesktopIconStyle.entries.forEachIndexed { i, style ->
            val icon = getDrawable(style.drawable) as AdaptiveIconDrawable
            repeat(2) { row ->
                val left = i * 200f + 40f
                val top = row * 220f + 28f
                canvas.save()
                canvas.translate(left, top)
                val clip = Path().apply {
                    if (row == 0) addCircle(60f, 60f, 60f, Path.Direction.CW)
                    else addRoundRect(RectF(0f, 0f, 120f, 120f), 30f, 30f, Path.Direction.CW)
                }
                canvas.clipPath(clip)
                // Android expands adaptive layers by 25% on each side before applying the launcher mask.
                icon.background.setBounds(-30, -30, 150, 150)
                icon.foreground.setBounds(-30, -30, 150, 150)
                icon.background.draw(canvas); icon.foreground.draw(canvas)
                canvas.restore()
                canvas.drawText(style.label, i * 200f + 100f, top + 164f, paint)
            }
        }
        File(filesDir, "desktop-icon-preview.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
        output.recycle()
    }
}
