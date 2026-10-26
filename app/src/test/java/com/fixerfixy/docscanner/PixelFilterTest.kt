package com.fixerfixy.docscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelFilterTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val WHITE = rgb(255, 255, 255)
    private val BLACK_TEXT = rgb(20, 20, 20)
    private val BLUE_STAMP = rgb(30, 60, 200)
    private val RED_STAMP = rgb(200, 30, 30)

    /** Сторінка: 75% біле, 10% чорний друк, 15% печатка потрібного кольору. */
    private fun page(stamp: Int): IntArray {
        val px = IntArray(1000) { WHITE }
        for (i in 0 until 100) px[i] = BLACK_TEXT
        for (i in 100 until 250) px[i] = stamp
        return px
    }

    @Test fun blueChannel_removesBlueStamp_keepsText() {
        val out = PixelFilter.process(page(BLUE_STAMP), ChannelMode.BLUE)
        assertTrue(PixelFilter.lum(out[50]) < 60)       // текст лишився темним
        assertEquals(255, PixelFilter.lum(out[200]))     // печатка зникла
        assertEquals(255, PixelFilter.lum(out[900]))     // фон білий
    }

    @Test fun blueChannel_doesNotRemoveRedStamp() {
        val out = PixelFilter.process(page(RED_STAMP), ChannelMode.BLUE)
        assertTrue(PixelFilter.lum(out[200]) < 100)
    }

    @Test fun redChannel_removesRedStamp_keepsText() {
        val out = PixelFilter.process(page(RED_STAMP), ChannelMode.RED)
        assertTrue(PixelFilter.lum(out[50]) < 60)
        assertEquals(255, PixelFilter.lum(out[200]))
    }

    @Test fun redChannel_doesNotRemoveBlueStamp() {
        val out = PixelFilter.process(page(BLUE_STAMP), ChannelMode.RED)
        assertTrue(PixelFilter.lum(out[200]) < 100)
    }

    @Test fun suppressColor_removesBothStamps_keepsText() {
        for (stamp in listOf(BLUE_STAMP, RED_STAMP, rgb(10, 20, 120), rgb(120, 40, 160))) {
            val out = PixelFilter.process(page(stamp), ChannelMode.SUPPRESS_COLOR)
            assertTrue(PixelFilter.lum(out[50]) < 60)
            assertEquals(255, PixelFilter.lum(out[200]))
        }
    }

    @Test fun original_keepsStampDark() {
        val out = PixelFilter.process(page(BLUE_STAMP), ChannelMode.ORIGINAL)
        assertTrue(PixelFilter.lum(out[200]) < 120)
    }

    @Test fun grayInkTextIsNotBleached() {
        // темно-сірий напівтон друку не повинен вважатися «кольором»
        val px = IntArray(1000) { WHITE }
        for (i in 0 until 100) px[i] = rgb(60, 60, 66)
        val out = PixelFilter.process(px, ChannelMode.SUPPRESS_COLOR)
        assertTrue(PixelFilter.lum(out[10]) < 80)
    }

    @Test fun blankPageStaysUntouchedAndDoesNotCrash() {
        val px = IntArray(500) { WHITE }
        val out = PixelFilter.process(px, ChannelMode.BLUE)
        assertTrue(out.all { PixelFilter.lum(it) == 255 })
    }

    @Test fun emptyArrayDoesNotCrash() {
        assertEquals(0, PixelFilter.process(IntArray(0), ChannelMode.RED).size)
    }

    @Test fun outputIsOpaque() {
        val out = PixelFilter.process(page(BLUE_STAMP), ChannelMode.BLUE)
        assertTrue(out.all { (it ushr 24) == 0xFF })
    }

    @Test fun channelOrderStartsWithOriginal() {
        assertEquals(ChannelMode.ORIGINAL, ChannelOrder.ALL.first())
        assertEquals(4, ChannelOrder.ALL.size)
    }
}
