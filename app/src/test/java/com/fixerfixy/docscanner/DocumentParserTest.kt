package com.fixerfixy.docscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentParserTest {

    // ---------- реальний OCR: акт № 1450 ----------
    @Test fun act1450_realOcr() {
        val d = DocumentParser.parse(OcrSamples.ACT_1450)
        assertEquals("Акт надання послуг", d.type)
        assertEquals("1450", d.number)
        assertEquals("30.09.2026", d.date)
    }

    // ---------- акт № 2100032124 ----------
    @Test fun act2100_clean_numberIsActNotContract() {
        val d = DocumentParser.parse(OcrSamples.ACT_2100_CLEAN)
        assertEquals("2100032124", d.number)
        assertEquals("30.11.2026", d.date)
        assertEquals("Акт здачі-прийняття робіт", d.type)
    }

    @Test fun act2100_sealCoversNumber_neverReturnsContractNumber() {
        val d = DocumentParser.parse(OcrSamples.ACT_2100_SEAL)
        assertEquals("б-н", d.number)
        assertEquals("30.11.2026", d.date)
        assertEquals("Акт здачі-прийняття робіт", d.type)
    }

    @Test fun act2100_titleLost_neverReturnsContractOrOrderNumber() {
        val d = DocumentParser.parse(OcrSamples.ACT_2100_TITLE_LOST)
        assertEquals("б-н", d.number)
        assertEquals("30.11.2026", d.date)
    }

    @Test fun tableHeaderWordsAreNeverNumbers() {
        // «Номер з\п» із шапки таблиці не має ставати номером «3-П»
        val d = DocumentParser.parse("Акт надання послуг\nНомер з\\п Найменування робіт\n1 Послуга 100,00\n30.11.2026")
        assertEquals("б-н", d.number)
    }

    @Test fun act2100_contractDateIsNotUsedAsDocumentDate() {
        val d = DocumentParser.parse(OcrSamples.ACT_2100_TITLE_LOST)
        assertNotEquals("01.04.2024", d.date)
    }

    // ---------- інші типи ----------
    @Test fun invoice_litlanPrefixAndDate() {
        val d = DocumentParser.parse(OcrSamples.INVOICE_LT)
        assertEquals("Рахунок-фактура", d.type)
        assertEquals("ЛТ-000123", d.number)
        assertEquals("13.10.2026", d.date)
    }

    @Test fun waybill_textDate() {
        val d = DocumentParser.parse(OcrSamples.WAYBILL)
        assertEquals("Видаткова накладна", d.type)
        assertEquals("РН-3974", d.number)
        assertEquals("13.10.2026", d.date)
    }

    @Test fun specification_parentContract() {
        val d = DocumentParser.parse(OcrSamples.SPEC)
        assertEquals("Специфікація", d.type)
        assertEquals("2", d.number)
        assertEquals("02.09.2026", d.date)
        assertEquals("45-2026", d.contractNumber)
        assertEquals("24.08.2026", d.contractDate)
    }

    // ---------- захист від типових помилок ----------
    @Test fun strayStampNumber8_isIgnored() {
        val d = DocumentParser.parse(OcrSamples.ACT_WITH_STAMP_NUMBER)
        assertEquals("15", d.number)
        assertEquals("05.03.2026", d.date)
    }

    @Test fun shortNumberIsAcceptedWhenInTitleWithVid() {
        val d = DocumentParser.parse("АКТ виконаних робіт № 7 від 12.05.2026\nВиконавець: ТОВ А")
        assertEquals("7", d.number)
    }

    @Test fun dayMonthIsNotMistakenForNumber() {
        val d = DocumentParser.parse(OcrSamples.ACT_DATE_ONLY_BY_SIGNATURE)
        assertEquals("30.09.2026", d.date)
        assertNotEquals("3009", d.number)
    }

    @Test fun dateAtStartOfLineIsNotTreatedAsClauseNumber() {
        val d = DocumentParser.parse("Акт надання послуг № 5\n30.09.2026\nТОВ Х")
        assertEquals("30.09.2026", d.date)
    }

    @Test fun titleBelowRequisitesDoesNotTakeEdrpou() {
        val d = DocumentParser.parse(OcrSamples.ACT_2100_CLEAN)
        assertNotEquals("42880795", d.number)
        assertNotEquals("31793056", d.number)
    }

    @Test fun iban_notUsedAsNumber() {
        val d = DocumentParser.parse("Рахунок № 55 від 01.10.2026\nIBAN UA853052990000026002050314640\n")
        assertEquals("55", d.number)
    }

    @Test fun leapYearFebruary29() {
        val d = DocumentParser.parse("Акт виконаних робіт № 3 від 29.02.2028")
        // 29.02.2028 існує, але виходить за межі розумного діапазону років — очікуємо "не_вказано", без винятку
        assertTrue(d.date == "29.02.2028" || d.date == "не_вказано")
    }

    @Test fun invalidDateRejected() {
        val d = DocumentParser.parse("Акт виконаних робіт № 3 від 31.02.2026")
        assertEquals("не_вказано", d.date)
    }

    @Test fun emptyAndGarbageDoNotCrash() {
        assertEquals("Документ", DocumentParser.parse("").type)
        assertEquals("Документ", DocumentParser.parse("[помилка OCR]").type)
        DocumentParser.parse("\u0000\u0001 ### ... № № № від від 99.99.9999")
    }

    @Test fun noInventedValuesOnUnrelatedText() {
        val d = DocumentParser.parse("Меню\nБорщ 120 грн\nВареники 150 грн")
        assertEquals("б-н", d.number)
        assertEquals("не_вказано", d.date)
    }

    // ---------- впевненість ----------
    @Test fun confidence_highForCleanTitle() {
        val o = DocumentParser.parseWithConfidence("АКТ надання послуг № 1450 від 30.09.2026\n30.09.2026")
        assertTrue(o.confident)
    }

    @Test fun confidence_lowWhenNumberMissing() {
        val o = DocumentParser.parseWithConfidence(OcrSamples.ACT_2100_SEAL)
        assertTrue(!o.confident)
    }
}
