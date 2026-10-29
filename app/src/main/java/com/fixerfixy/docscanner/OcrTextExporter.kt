package com.fixerfixy.docscanner

import java.io.File

/** Збереження сирого OCR-тексту в .txt поруч із PDF (з тією самою назвою). */
object OcrTextExporter {

    /** Ім'я .txt за іменем документа: "Акт_No_1_від_01.01.2026_р.pdf" -> "Акт_No_1_від_01.01.2026_р.txt". */
    fun txtNameFor(docName: String): String {
        val dot = docName.lastIndexOf('.')
        val base = if (dot > 0) docName.substring(0, dot) else docName
        return "$base.txt"
    }

    fun txtFileFor(pdf: File): File =
        File(pdf.parentFile, txtNameFor(pdf.name))

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
