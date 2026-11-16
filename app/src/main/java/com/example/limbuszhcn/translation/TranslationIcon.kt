package com.example.limbuszhcn.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream

/** Preserve the full image and its alpha; the UI owns no background for a custom icon. */
internal object TranslationIcon {
    fun normalize(data: ByteArray): ByteArray {
        require(data.size <= 16 * 1024 * 1024) { "图标文件过大" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384) { "请选择 PNG、JPEG 或 WebP 图片" }
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val original = requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size, options)) { "图片读取失败" }
        val output = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        try {
            val scale = 192f / maxOf(original.width, original.height)
            val width = original.width * scale
            val height = original.height * scale
            val left = (192f - width) / 2f
            val top = (192f - height) / 2f
            Canvas(output).drawBitmap(original, null, RectF(left, top, left + width, top + height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return ByteArrayOutputStream().also { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally {
            original.recycle()
            output.recycle()
        }
    }
}
