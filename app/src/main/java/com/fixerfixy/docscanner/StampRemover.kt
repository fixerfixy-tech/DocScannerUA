package com.fixerfixy.docscanner

import android.graphics.Bitmap
import kotlin.math.max

object StampRemover {
    /**
     * Приглушує сині, блакитні та фіолетові мокрі печатки й підписи ручкою на сканкопії,
     * залишаючи чорний друкований текст максимально контрастним для Tesseract OCR.
     */
    fun suppressBlueAndPurpleStamps(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            // Якщо синій канал домінує над червоним/зеленим (синя печатка або ручка),
            // беремо яскравість синього/максимального каналу, вибілюючи печатку до фону.
            // Чорний текст має низькі R, G, B одночасно, тому залишається чорним.
            val gray = if (b - r > 30 || b - g > 30) {
                max(b, max(r, g)).coerceAtLeast(235)
            } else {
                (r * 77 + g * 150 + b * 29) shr 8
            }
            pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }
}
