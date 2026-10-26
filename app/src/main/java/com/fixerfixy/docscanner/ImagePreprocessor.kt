package com.fixerfixy.docscanner

import android.graphics.Bitmap

/**
 * Android-обгортка над PixelFilter. Обробка йде смугами по рядках,
 * тому додаткова пам'ять — лише один новий Bitmap та буфер на кілька рядків.
 */
object ImagePreprocessor {

    private const val STRIP_ROWS = 64

    /** Створює новий Bitmap у вибраному режимі. Викликаючий має зробити recycle() після OCR. */
    fun variant(src: Bitmap, mode: ChannelMode): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bufIn = IntArray(w * STRIP_ROWS)
        val bufOut = IntArray(w * STRIP_ROWS)

        // Прохід 1: гістограма
        val hist = PixelFilter.newHistogram()
        var y = 0
        while (y < h) {
            val rows = minOf(STRIP_ROWS, h - y)
            src.getPixels(bufIn, 0, w, 0, y, w, rows)
            PixelFilter.addToHistogram(hist, bufIn, w * rows, mode)
            y += rows
        }
        val lut = PixelFilter.contrastLut(hist)

        // Прохід 2: застосування
        y = 0
        while (y < h) {
            val rows = minOf(STRIP_ROWS, h - y)
            src.getPixels(bufIn, 0, w, 0, y, w, rows)
            PixelFilter.applyRow(bufIn, bufOut, w * rows, mode, lut)
            out.setPixels(bufOut, 0, w, 0, y, w, rows)
            y += rows
        }
        return out
    }

}
