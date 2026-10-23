package com.fixerfixy.docscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

object ImageProcessor {

    fun scaleBitmapToDpi(src: Bitmap, dpi: Int): Bitmap {
        val targetMaxDimension = when (dpi) {
            100 -> 1200
            400 -> 4800
            else -> 2400 // 200 DPI (оптимальний)
        }

        val maxDim = maxOf(src.width, src.height)
        if (maxDim <= targetMaxDimension && dpi != 100) return src

        val scale = targetMaxDimension.toFloat() / maxDim
        val targetW = (src.width * scale).toInt().coerceAtLeast(1)
        val targetH = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    fun applyColorMode(src: Bitmap, mode: String): Bitmap {
        return when (mode.lowercase()) {
            "grayscale" -> {
                val dest = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(dest)
                val paint = Paint()
                val matrix = ColorMatrix().apply { setSaturation(0f) }
                paint.colorFilter = ColorMatrixColorFilter(matrix)
                canvas.drawBitmap(src, 0f, 0f, paint)
                dest
            }
            "bw" -> {
                val gray = applyColorMode(src, "grayscale")
                val dest = Bitmap.createBitmap(gray.width, gray.height, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(gray.width * gray.height)
                gray.getPixels(pixels, 0, gray.width, 0, 0, gray.width, gray.height)

                val threshold = 145
                for (i in pixels.indices) {
                    val c = pixels[i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val lum = (r * 299 + g * 587 + b * 114) / 1000
                    pixels[i] = if (lum < threshold) -0x1000000 else -0x1
                }
                dest.setPixels(pixels, 0, gray.width, 0, 0, gray.width,


cd ~/DocScannerUA
git pull

# 1. Менеджер налаштувань (SettingsManager.kt)
cat << 'EOF' > app/src/main/java/com/fixerfixy/docscanner/SettingsManager.kt
package com.fixerfixy.docscanner

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("doc_scanner_prefs", Context.MODE_PRIVATE)

    var folderName: String
        get() = prefs.getString("folder_name", "ScanDoc") ?: "ScanDoc"
        set(value) = prefs.edit().putString("folder_name", value.trim()).apply()

    var namingTemplate: String
        get() = prefs.getString("naming_template", "{type}_No_{number}_від_{date}_р.pdf") ?: "{type}_No_{number}_від_{date}_р.pdf"
        set(value) = prefs.edit().putString("naming_template", value.trim()).apply()
}
