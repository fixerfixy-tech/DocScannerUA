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
            val engFile = File(tessDir, "eng.traineddata")

            copyAssetIfNeeded(context, "tessdata/ukr.traineddata", ukrFile)
            copyAssetIfNeeded(context, "tessdata/eng.traineddata", engFile)

            // Важливо: шлях до батьківської папки tessdata ПОВИНЕН закінчуватися скісним слешем '/'
            val dataPath = context.filesDir.absolutePath + File.separator

            val success = tessBaseAPI.init(dataPath, "ukr+eng")
            if (success) {
                tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                isInitialized = true
                initError = null
                Log.d("OcrManager", "Tesseract успішно ініціалізовано")
                return true
            } else {
                val ukrOnly = tessBaseAPI.init(dataPath, "ukr")
                if (ukrOnly) {
                    tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                    isInitialized = true
                    initError = null
                    return true
                }
                initError = "tessBaseAPI.init повернув false для шляху: $dataPath"
                Log.e("OcrManager", initError!!)
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
                val safeBmp = if (bitmap.config == Bitmap.Config.HARDWARE) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    bitmap
                }
                tessBaseAPI.setImage(safeBmp)
                val text = tessBaseAPI.utF8Text ?: ""
                tessBaseAPI.clear()
                if (text.isBlank()) "[Текст не знайдено на сторінці]" else text
            } catch (e: Exception) {
                "[Помилка читання OCR: ${e.localizedMessage}]"
            }
        }
    }

    private fun copyAssetIfNeeded(context: Context, assetPath: String, destFile: File) {
        // Якщо файл вже є і його розмір нормальний (> 100 КБ), повторно не копіюємо
        if (destFile.exists() && destFile.length() > 100_000) return

        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("OcrManager", "Скопійовано $assetPath (${destFile.length()} байт)")
        } catch (e: Exception) {
            Log.e("OcrManager", "Не вдалося скопіювати $assetPath: ${e.message}")
        }
    }
}
