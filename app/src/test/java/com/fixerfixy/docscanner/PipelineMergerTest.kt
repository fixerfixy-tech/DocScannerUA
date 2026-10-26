package com.fixerfixy.docscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineMergerTest {

    private fun variants(names: List<String>, released: MutableList<String>) =
        names.map { n -> ImageVariant(n, { n }, { released.add(it) }) }

    @Test fun stopsAfterFirstConfidentVariant() {
        val released = mutableListOf<String>()
        val called = mutableListOf<String>()
        var result: PipelineResult? = null
        OcrPipeline.run(
            variants(listOf("orig", "blue", "red"), released),
            recognize = { img, cb -> called.add(img); cb("АКТ надання послуг № 1450 від 30.09.2026\n30.09.2026") },
            onDone = { result = it }
        )
        assertEquals(listOf("orig"), called)
        assertEquals(1, result!!.variantsUsed)
        assertEquals("1450", result!!.doc.number)
        assertEquals(listOf("orig"), released)
    }

    @Test fun runsAllVariantsAndMergesWhenFirstIsWeak() {
        val released = mutableListOf<String>()
        val texts = mapOf(
            "orig" to OcrSamples.ACT_2100_SEAL,           // номера нема
            "blue" to OcrSamples.ACT_2100_CLEAN,          // печатку прибрано — номер видно
            "red" to OcrSamples.ACT_2100_SEAL
        )
        var result: PipelineResult? = null
        OcrPipeline.run(
            variants(listOf("orig", "blue", "red"), released),
            recognize = { img, cb -> cb(texts.getValue(img)) },
            onDone = { result = it },
            stopWhenConfident = true
        )
        val doc = result!!.doc
        assertEquals("2100032124", doc.number)
        assertEquals("30.11.2026", doc.date)
        assertEquals("Акт здачі-прийняття робіт", doc.type)
        // blue дав надійний результат -> red вже не запускався
        assertEquals(2, result!!.variantsUsed)
        assertEquals(listOf("orig", "blue"), released)
    }

    @Test fun mergedTextContainsAllVariantsWithLabels() {
        var result: PipelineResult? = null
        OcrPipeline.run(
            listOf(ImageVariant("orig", { "a" }), ImageVariant("blue", { "b" })),
            recognize = { img, cb -> cb(if (img == "a") "Текст один" else "Текст два") },
            onDone = { result = it }
        )
        val t = result!!.doc.rawOcrText
        assertTrue(t.contains("===== orig =====") && t.contains("Текст один"))
        assertTrue(t.contains("===== blue =====") && t.contains("Текст два"))
    }

    @Test fun failedCreateOrRecognizeIsSkipped() {
        var result: PipelineResult? = null
        OcrPipeline.run(
            listOf(
                ImageVariant<String>("bad", { throw OutOfMemoryError() }),
                ImageVariant<String>("boom", { "x" }),
                ImageVariant<String>("good", { "g" })
            ),
            recognize = { img, cb ->
                if (img == "x") throw IllegalStateException("ocr failed")
                cb("АКТ надання послуг № 9 від 01.10.2026\n01.10.2026")
            },
            onDone = { result = it }
        )
        assertEquals("9", result!!.doc.number)
    }

    @Test fun doneCalledOnceEvenIfCallbackFiresTwice() {
        var count = 0
        OcrPipeline.run(
            listOf(ImageVariant("v", { "v" })),
            recognize = { _, cb -> cb("Акт № 1 від 01.10.2026"); cb("повторно") },
            onDone = { count++ }
        )
        assertEquals(1, count)
    }

    @Test fun noVariantsGivesEmptyDocument() {
        var result: PipelineResult? = null
        OcrPipeline.run(emptyList<ImageVariant<String>>(), { _, cb -> cb("") }, { result = it })
        assertEquals("Документ", result!!.doc.type)
        assertEquals(0, result!!.variantsUsed)
    }

    // ---------- ResultMerger ----------
    @Test fun merger_prefersSpecificTypeOverGeneric() {
        val generic = DocumentParser.parseWithConfidence("Якийсь текст\nАкт")
        val specific = DocumentParser.parseWithConfidence("АКТ надання послуг № 5 від 01.10.2026")
        val m = ResultMerger.merge(listOf(generic, specific))
        assertEquals("Акт надання послуг", m.type)
    }

    @Test fun merger_ignoresMissingValuesFromWeakVariants() {
        val a = DocumentParser.parseWithConfidence("АКТ надання послуг\nнічого")
        val b = DocumentParser.parseWithConfidence("АКТ надання послуг № 77 від 02.10.2026")
        val m = ResultMerger.merge(listOf(a, b))
        assertEquals("77", m.number)
        assertEquals("02.10.2026", m.date)
    }

    @Test fun merger_votesForRepeatedValue() {
        val x1 = DocumentParser.parseWithConfidence("АКТ надання послуг № 100 від 02.10.2026")
        val x2 = DocumentParser.parseWithConfidence("АКТ надання послуг № 100 від 02.10.2026")
        val y = DocumentParser.parseWithConfidence("АКТ надання послуг № 108 від 02.10.2026")
        val m = ResultMerger.merge(listOf(y, x1, x2))
        assertEquals("100", m.number)
    }

    @Test fun merger_singleOutcomeReturnedAsIs() {
        val o = DocumentParser.parseWithConfidence("АКТ надання послуг № 3 від 02.10.2026")
        assertEquals(o.doc.number, ResultMerger.merge(listOf(o)).number)
    }

    @Test fun merger_neverInventsContractNumberAsDocumentNumber() {
        val a = DocumentParser.parseWithConfidence(OcrSamples.ACT_2100_SEAL)
        val b = DocumentParser.parseWithConfidence(OcrSamples.ACT_2100_TITLE_LOST)
        val m = ResultMerger.merge(listOf(a, b))
        assertNotEquals("300334481", m.number)
    }
}
