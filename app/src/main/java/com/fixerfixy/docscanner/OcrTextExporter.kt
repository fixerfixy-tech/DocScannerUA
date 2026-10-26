package com.fixerfixy.docscanner

import java.io.File

/** Збереження сирого OCR-тексту в .txt поруч із PDF (з тією самою назвою). */
object OcrTextExporter {

    fun txtFileFor(pdf: File): File =
        File(pdf.parentFile, pdf.nameWithoutExtension + ".txt")

    /** Повертає створений файл або null у разі помилки. */
    fun save(pdf: File, text: String): File? {
        return try {
            val f = txtFileFor(pdf)
            f.writeText(text, Charsets.UTF_8)
            f
        } catch (e: Exception) {
            null
        }
    }

    /** Після перейменування PDF переносить .txt за новою назвою. */
    fun moveAlong(oldPdf: File, newPdf: File): Boolean {
        return try {
            val old = txtFileFor(oldPdf)
            if (!old.exists()) return false
            val target = txtFileFor(newPdf)
            if (target.exists()) target.delete()
            old.renameTo(target)
        } catch (e: Exception) {
            false
        }
    }

    fun delete(pdf: File): Boolean {
        return try {
            val f = txtFileFor(pdf)
            f.exists() && f.delete()
        } catch (e: Exception) {
            false
        }
    }
}
