package com.example.limbuszhcn

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import com.example.limbuszhcn.translation.AiConfig
import com.example.limbuszhcn.translation.AiSettingsStore
import com.example.limbuszhcn.translation.TranslationIcon
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Runs platform-dependent checks only on our disposable emulator; absent from release. */
class TranslationSourceProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val view = TextView(this).apply { text = "Checking Android icon and credential storage" }
        setContentView(view)
        Thread {
            val report = JSONObject()
            try {
                val settings = AiSettingsStore(this)
                check(!settings.hasKey()) { "Probe requires a fresh emulator without a saved credential" }
                val dummy = "bybus-probe-not-a-real-credential"
                settings.save(AiConfig(), dummy)
                check(AiSettingsStore(this).key() == dummy)
                check(getSharedPreferences("ai-translation-private", 0).all.values.none { it.toString().contains(dummy) })
                check(runCatching { settings.save(AiConfig(endpoint = "https://example.org/v1"), "") }.isFailure)
                settings.clearKey()
                check(!settings.hasKey() && runCatching { settings.key() }.isFailure)
                report.put("androidKeystoreRoundTripAndEndpointIsolation", true)

                val image = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
                Canvas(image).apply {
                    drawRect(0f, 10f, 40f, 90f, Paint().apply { color = Color.RED })
                    drawRect(160f, 10f, 200f, 90f, Paint().apply { color = Color.BLUE })
                }
                val input = ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
                val bytes = TranslationIcon.normalize(input)
                val result = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                check(result.width == 192 && result.height == 192)
                check(Color.alpha(result.getPixel(96, 0)) == 0 && Color.alpha(result.getPixel(96, 96)) == 0)
                check(Color.red(result.getPixel(10, 96)) > 200 && Color.blue(result.getPixel(180, 96)) > 200)
                check(runCatching { TranslationIcon.normalize(byteArrayOf(1, 2, 3)) }.isFailure)
                File(getExternalFilesDir(null), "transparent-icon.png").writeBytes(bytes)
                image.recycle(); result.recycle()
                report.put("transparentIconNoCrop", true).put("invalidImageRejected", true).put("passed", true)
            } catch (error: Throwable) {
                report.put("passed", false).put("error", error.javaClass.simpleName + ": " + error.message)
            }
            File(getExternalFilesDir(null), "translation-platform-report.json").writeText(report.toString())
            runOnUiThread { view.text = report.toString(2) }
        }.start()
    }
}
