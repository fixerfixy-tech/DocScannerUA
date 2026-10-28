package com.fixerfixy.docscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocNameFormatterTest {

    @Test
    fun periodNameKeepsConnectorAndShrinksInOrder() {
        val full = "Акт звірки за договором_No_19-12-17ФК_за_період_з_01.04.2020_по_30.06.2020_р.pdf"
        val c = DocNameFormatter.candidates(full)
        assertEquals(full, c.first())
        assertEquals("Акт звірки за договором_№19-12-17ФК_за_період_з_01.04.2020_по_30.06.2020.pdf", c[1])
        assertTrue(c.any { it == "Акт звірки за договором_№19-12-17ФК_за_період_…06.2020.pdf" })
        assertTrue(c.all { it.endsWith(".pdf") })
    }

    @Test
    fun ordinaryNameStillShrinksTypeFirst() {
        val c = DocNameFormatter.candidates("Акт здачі-прийняття робіт_No_ЛТ-0000272_від_30.09.2026_р.pdf")
        assertEquals("Акт здачі-прийняття робіт_№ЛТ-0000272_від_30.09.2026.pdf", c[1])
        assertTrue(c.contains("Акт здач…_№000272_від_…09.2026.pdf"))
    }
}
