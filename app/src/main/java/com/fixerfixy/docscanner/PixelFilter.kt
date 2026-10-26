package com.fixerfixy.docscanner

/** Режими підготовки зображення для OCR. */
enum class ChannelMode(val label: String) {
    /** Без змін (звичайна яскравість). */
    ORIGINAL("оригінал"),
    /** Береться ТІЛЬКИ синій канал: синє чорнило (печатки, підписи кулькою) стає світлим, чорний друк лишається темним. */
    BLUE("синій канал"),
    /** Береться ТІЛЬКИ червоний канал: червоне/оранжеве чорнило зникає, чорний друк лишається. */
    RED("червоний канал"),
    /** Усе насичене кольорове (будь-яке чорнило) вибілюється; лишаються лише майже чорні/сірі пікселі. */
    SUPPRESS_COLOR("без кольору")
}

/** Порядок запуску OCR: оригінал, далі проти синього/червоного чорнила, далі без кольору. */
object ChannelOrder {
    val ALL: List<ChannelMode> = listOf(
        ChannelMode.ORIGINAL, ChannelMode.BLUE, ChannelMode.RED, ChannelMode.SUPPRESS_COLOR
    )
}

/**
 * Чиста піксельна логіка без залежності від Android (тестується звичайним JUnit).
 * Пікселі — у форматі ARGB (як Bitmap.getPixels).
 */
object PixelFilter {

    /** Яскравість 0..255 для пікселя у вибраному режимі. */
    fun gray(argb: Int, mode: ChannelMode): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return when (mode) {
            ChannelMode.ORIGINAL -> (r * 299 + g * 587 + b * 114) / 1000
            ChannelMode.BLUE -> b
            ChannelMode.RED -> r
            ChannelMode.SUPPRESS_COLOR -> {
                val mx = maxOf(r, g, b)
                val mn = minOf(r, g, b)
                val saturation = if (mx == 0) 0f else (mx - mn).toFloat() / mx
                if (saturation > 0.35f && mx > 90) 255 else (r * 299 + g * 587 + b * 114) / 1000
            }
        }
    }

    fun newHistogram(): IntArray = IntArray(256)

    fun addToHistogram(hist: IntArray, row: IntArray, length: Int, mode: ChannelMode) {
        for (i in 0 until length) hist[gray(row[i], mode)]++
    }

    /**
     * Таблиця розтягування контрасту: темна точка = 1-й процентиль, фон = медіана
     * (на документах >50% площі — біле тло). Світлі залишки печаток (>= whiten) відбілюються.
     */
    fun contrastLut(hist: IntArray, whiten: Int = 165): IntArray {
        val total = hist.sum()
        val lut = IntArray(256) { it }
        if (total == 0) return lut
        fun percentile(p: Double): Int {
            val target = (total * p).toLong()
            var acc = 0L
            for (v in 0..255) {
                acc += hist[v]
                if (acc >= target) return v
            }
            return 255
        }
        val lo = percentile(0.01)
        var hi = percentile(0.50)
        if (hi - lo < 40) { // майже порожня або дуже темна сторінка — не чіпаємо
            return lut
        }
        if (hi <= lo) hi = lo + 1
        for (v in 0..255) {
            val s = ((v - lo) * 255) / (hi - lo)
            val c = s.coerceIn(0, 255)
            lut[v] = if (c >= whiten) 255 else c
        }
        return lut
    }

    /** Перетворює рядок пікселів у сірий ARGB за таблицею. */
    fun applyRow(src: IntArray, dst: IntArray, length: Int, mode: ChannelMode, lut: IntArray) {
        for (i in 0 until length) {
            val g = lut[gray(src[i], mode)]
            dst[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    /** Зручний варіант для тестів: весь масив одразу. */
    fun process(pixels: IntArray, mode: ChannelMode): IntArray {
        val hist = newHistogram()
        addToHistogram(hist, pixels, pixels.size, mode)
        val lut = contrastLut(hist)
        val out = IntArray(pixels.size)
        applyRow(pixels, out, pixels.size, mode, lut)
        return out
    }

    /** Яскравість (0..255) пікселя результату. */
    fun lum(argb: Int): Int = argb and 0xFF
}
