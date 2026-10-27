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
        // Довша сторона аркуша A4 = 11.69 дюймів (для ландшафтного скану це ширина)
        val (targetWidth, targetHeight) = PageGeometry.scaledSize(src.width, src.height, targetDpi)

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
            // Орієнтація сторінки відповідає орієнтації скану (кожна сторінка - окремо)
            val (pageW, pageH) = PageGeometry.pageSize(bmp.width, bmp.height)
            val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create()
            val page = pdfDoc.startPage(pageInfo)

            val r = PageGeometry.fitRect(bmp.width, bmp.height, pageW, pageH)
            page.canvas.drawBitmap(bmp, null, RectF(r[0], r[1], r[2], r[3]), null)
            pdfDoc.finishPage(page)
        }
        pdfDoc.writeTo(outputStream)
        pdfDoc.close()
    }

    fun writeJpeg(bitmap: Bitmap, outputStream: OutputStream) {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
    }
}
