package com.fixerfixy.docscanner

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

object OcrManager {

    private var isInitialized = false
    private lateinit var tessBaseAPI: TessBaseAPI

    fun init(context: Context) {
        if (isInitialized) return

        val tessDir = File(context.filesDir, "tessdata")
        if (!tessDir.exists()) {
            tessDir.mkdirs()
        }

        copyAssetToFile(context, "tessdata/ukr.traineddata", File(tessDir, "ukr.traineddata"))
        copyAssetToFile(context, "tessdata/eng.traineddata", File(tessDir, "eng.traineddata"))

        tessBaseAPI = TessBaseAPI()
        tessBaseAPI.init(context.filesDir.absolutePath, "ukr+eng")
        tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        isInitialized = true
    }

    fun extractText(bitmap: Bitmap): String {
        return try {
            tessBaseAPI.setImage(bitmap)
            tessBaseAPI.utF8Text ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun copyAssetToFile(context: Context, assetPath: String, destFile: File) {
        if (destFile.exists() && destFile.length() > 0) return
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (ignored: Exception) {}
    }
}
