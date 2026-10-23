package com.fixerfixy.docscanner

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

object OcrManager {

    @Volatile
    private var isInitialized = false
    private var initError: String? = null
    private val tessBaseAPI = TessBaseAPI()

    @Synchronized
    fun init(context: Context): Boolean {
        if (isInitialized) return true

        try {
            val tessDir = File(context.filesDir, "tessdata")
            if (!tessDir.exists()) {
                tessDir.mkdirs()
            }

            val ukrFile = File(tessDir, "ukr.traineddata")
            copyAssetIfNeeded(context, "tessdata/ukr.traineddata", ukrFile)

            val dataPath = context.filesDir.absolutePath + File.separator

            // Використовуємо виключно українську модель, щоб уникнути підміни кирилиці латиницею
            val success = tessBaseAPI.init(dataPath, "ukr")
            if (success) {
                tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                isInitialized = true
                initError = null
                return true
            } else {
                initError = "Не вдалося ініціалізувати Tesseract для шляху: $dataPath"
                return false
            }
        } catch (e: Exception) {
            initError = "Помилка init: ${e.localizedMessage}"
            Log.e("OcrManager", initError, e)
            return false
        }
    }

    fun extractText(context: Context, bitmap: Bitmap): String {
        if (!isInitialized) {
            val ready = init(context)
            if (!ready) {
                return "[Помилка ініціалізації OCR: ${initError ?: "невідома"}]"
            }
        }

        return synchronized(this) {
            try {
                val argbBmp = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    bitmap
                }
                // Очищуємо копію зображення від синіх печаток та підписів перед розпізнаванням
                val cleanBmp = removeBlueStampsForOcr(argbBmp)
                tessBaseAPI.setImage(cleanBmp)
                val text = tessBaseAPI.utF8Text ?: ""
                tessBaseAPI.clear()
                if (text.isBlank()) "[Текст не знайдено на сторінці]" else text
            } catch (e: Exception) {
                "[Помилка читання OCR: ${e.localizedMessage}]"
            }
        }
    }

    private fun removeBlueStampsForOcr(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            // Якщо піксель належить синій печатці або синій ручці (синій канал суттєво перевищує червоний) — вибілюємо його
            val gray = if (b - r > 28 && b > 80) {
                255
            } else {
                val lum = (r * 77 + g * 150 + b * 29) shr 8
                maxOf(lum, b)
            }
            pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun copyAssetIfNeeded(context: Context, assetPath: String, destFile: File) {
        if (destFile.exists() && destFile.length() > 100_000) return
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (ignored: Exception) {}
    }
}
