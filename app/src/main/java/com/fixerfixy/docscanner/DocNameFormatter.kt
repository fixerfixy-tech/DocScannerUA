package com.fixerfixy.docscanner

/**
 * Коротка назва файлу для списку (в один рядок):
 *   Акт здачі-прийняття робіт_No_ЛТ-0000272_від_30.09.2026_р.pdf
 *   -> Акт здачі-пр…_№000272_від_…09.2026.pdf
 * Скорочується лише відображення, сама назва файлу не змінюється.
 */
object DocNameFormatter {

    private const val TYPE_MAX = 12
    private const val NUMBER_DIGITS = 6
    private const val NUMBER_PLAIN_MAX = 10

    private val NAME_REGEX = Regex("""^(.*?)_(?:No|№)_?(.+?)_від_(.+)$""")
    private val DATE_REGEX = Regex("""\d{1,2}\.(\d{1,2})\.(\d{4})""")
    private val DUP_REGEX = Regex("""_р_(\d+)$""")
    private val DIGITS_REGEX = Regex("""\d+""")

    fun shorten(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return fileName
        val ext = fileName.substring(dot)
        val base = fileName.substring(0, dot)

        val m = NAME_REGEX.find(base) ?: return fileName
        val type = m.groupValues[1].trim()
        val number = m.groupValues[2]
        val rest = m.groupValues[3]

        val shortType = if (type.length > TYPE_MAX) {
            type.take(TYPE_MAX).trimEnd(' ', '-', '_', '(') + "…"
        } else {
            type
        }

        val dm = DATE_REGEX.find(rest)
        val shortDate = if (dm != null) {
            "…" + dm.groupValues[1].padStart(2, '0') + "." + dm.groupValues[2]
        } else {
            "не_вказано"
        }

        val dup = DUP_REGEX.find(rest)?.let { "_" + it.groupValues[1] } ?: ""

        return shortType + "_№" + shortNumber(number) + "_від_" + shortDate + dup + ext
    }

    /** Найдовша група цифр (від 4 цифр) -> останні 6 цифр; інакше номер як є (не довше 10 символів). */
    fun shortNumber(number: String): String {
        val longest = DIGITS_REGEX.findAll(number).map { it.value }.maxByOrNull { it.length }
        return if (longest != null && longest.length >= 4) {
            longest.takeLast(NUMBER_DIGITS)
        } else {
            number.takeLast(NUMBER_PLAIN_MAX)
        }
    }
}
