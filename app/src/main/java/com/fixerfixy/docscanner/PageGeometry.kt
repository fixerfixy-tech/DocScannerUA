package com.fixerfixy.docscanner

/** Геометрія сторінки A4 для скану (чиста логіка без Android). */
object PageGeometry {

    const val A4_SHORT = 595
    const val A4_LONG = 842
    private const val A4_LONG_INCH = 11.69
    private const val MIN_LONG_SIDE = 800

    /** Розмір сторінки PDF у пунктах: ландшафтний скан -> 842x595, інакше 595x842. */
    fun pageSize(width: Int, height: Int): Pair<Int, Int> =
        if (width > height) Pair(A4_LONG, A4_SHORT) else Pair(A4_SHORT, A4_LONG)

    /** Розмір зображення за DPI: довша сторона = 11.69" * dpi (не менше 800 px), пропорції зберігаються. */
    fun scaledSize(width: Int, height: Int, dpi: Int): Pair<Int, Int> {
        val longSide = (dpi * A4_LONG_INCH).toInt().coerceAtLeast(MIN_LONG_SIDE)
        val scale = longSide.toFloat() / maxOf(width, height)
        return Pair(maxOf(1, Math.round(width * scale)), maxOf(1, Math.round(height * scale)))
    }

    /** Прямокутник (left, top, right, bottom), у який вписується зображення на сторінці по центру. */
    fun fitRect(bmpWidth: Int, bmpHeight: Int, pageWidth: Int, pageHeight: Int): FloatArray {
        val bmpAspect = bmpWidth.toFloat() / bmpHeight
        val pageAspect = pageWidth.toFloat() / pageHeight
        return if (bmpAspect > pageAspect) {
            val h = pageWidth / bmpAspect
            floatArrayOf(0f, (pageHeight - h) / 2f, pageWidth.toFloat(), (pageHeight + h) / 2f)
        } else {
            val w = pageHeight * bmpAspect
            floatArrayOf((pageWidth - w) / 2f, 0f, (pageWidth + w) / 2f, pageHeight.toFloat())
        }
    }
}
