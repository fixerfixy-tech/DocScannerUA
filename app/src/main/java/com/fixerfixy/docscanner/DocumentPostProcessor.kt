package com.fixerfixy.docscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

object DocumentPostProcessor {

    fun processBitmap(src: Bitmap, targetDpi: Int, colorMode: String): Bitmap {
        // Стандартна висота аркуша A4 = 11.69 дюймів
        val targetHeight = (targetDpi * 11.69).toInt().coerceAtLeast(800)
        val aspectRatio = src.width.toFloat() / src.height.toFloat()
        val targetWidth = (targetHeight * aspectRatio).toInt()

        val scaled = if (src.width != targetWidth || src.height != targetHeight) {
            Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
        } else {
            src
        }

        return when (colorMode) {
            "GRAYSCALE" -> toGrayscale(scaled)
            "BW" -> toBlackAndWhite(scaled)
            else -> scaled
        }
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val dest = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val ma = ColorMatrix().apply { setSaturation(0f) }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ma) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }

    private fun toBlackAndWhite(src: Bitmap): Bitmap {
        val gray = toGrayscale(src)
        val width = gray.width
        val height = gray.height
        val pixels = IntArray(width * height)
        gray.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            pixels[i] = if (lum > 135) -0x1 else -0x1000000 // Білий або Чорний
        }

        val bwBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bwBmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bwBmp
    }

    fun writePdf(bitmaps: List<Bitmap>, outputStream: OutputStream) {
        val pdfDoc = PdfDocument()
        for ((index, bmp) in bitmaps.withIndex()) {
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
            val page = pdfDoc.startPage(pageInfo)
            val canvas = page.canvas

            val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()
            val pageAspect = 595f / 842f

            val drawRect = if (bmpAspect > pageAspect) {
                val h = 595f / bmpAspect
                RectF(0f, (842f - h) / 2f, 595f, (842f + h) / 2f)
            } else {
                val w = 842f * bmpAspect
                RectF((595f - w) / 2f, 0f, (595f + w) / 2f, 842f)
            }

            canvas.drawBitmap(bmp, null, drawRect, null)
            pdfDoc.finishPage(page)
        }
        pdfDoc.writeTo(outputStream)
        pdfDoc.close()
    }

    fun writeJpeg(bitmap: Bitmap, outputStream: OutputStream) {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
    }
}
