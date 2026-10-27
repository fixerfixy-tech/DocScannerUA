package com.fixerfixy.docscanner

/**
 * Варіанти відображення назви файлу від повного до найкоротшого.
 * Пріоритет скорочення: 1) тип документа (до TYPE_MIN символів), 2) номер (до 6 цифр), 3) дата, 4) номер до 4 цифр.
 * Формат (.pdf/.jpg) лишається завжди. Сама назва файлу не змінюється.
 *
 *   Акт здачі-прийняття робіт_No_ЛТ-0000272_від_30.09.2026_р.pdf   (повна назва)
 *   Акт здачі-прийняття робіт_№ЛТ-0000272_від_30.09.2026.pdf
 *   Акт здачі-пр…_№ЛТ-0000272_від_30.09.2026.pdf
 *   Акт здач…_№000272_від_30.09.2026.pdf
 *   Акт здач…_№000272_від_…09.2026.pdf
 */
object DocNameFormatter {

    const val TYPE_MIN = 8
    private const val NUMBER_DIGITS = 6
    private const val NUMBER_SHORTEST = 4
    private const val NUMBER_PLAIN_MAX = 10

    private val NAME_REGEX = Regex("""^(.*?)_(?:No|№)_?(.+?)_від_(.+)$""")
    private val DATE_REGEX = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})""")
    private val DUP_REGEX = Regex("""_р_(\d+)$""")
    private val DIGITS_REGEX = Regex("""\d+""")

    private class Parts(val type: String, val number: String, val rest: String, val ext: String)

    private fun parse(fileName: String): Parts? {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return null
        val ext = fileName.substring(dot)
        val m = NAME_REGEX.find(fileName.substring(0, dot)) ?: return null
        return Parts(m.groupValues[1].trim(), m.groupValues[2], m.groupValues[3], ext)
    }

    /**
     * Список варіантів у порядку пріоритету: беріть перший, що вміщується в рядок.
     * Перший елемент - точна назва файлу. Якщо назва не за шаблоном - лише вона сама.
     */
    fun candidates(fileName: String): List<String> {
        val p = parse(fileName) ?: return listOf(fileName)
        val dup = DUP_REGEX.find(p.rest)?.let { "_" + it.groupValues[1] } ?: ""

        val out = LinkedHashSet<String>()
        out.add(fileName)
        // Спершу номер не коротший за 6 цифр; 4 цифри - лише останній засіб (після всіх скорочень дати)
        for (lastResort in listOf(false, true)) {
            for (date in dateVariants(p.rest)) {
                for (num in numberVariants(p.number, lastResort)) {
                    for (type in typeVariants(p.type)) {
                        out.add(type + "_№" + num + "_від_" + date + dup + p.ext)
                    }
                }
            }
        }
        return out.toList()
    }

    /** Тип: повний, далі коротші до TYPE_MIN символів + "…". */
    private fun typeVariants(type: String): List<String> {
        val list = ArrayList<String>()
        list.add(type)
        val floor = minOf(TYPE_MIN, type.length)
        var k = type.length - 2
        while (k >= floor) {
            val cut = type.take(k).trimEnd(' ', '-', '_', '(') + "…"
            if (cut !in list) list.add(cut)
            k--
        }
        return list
    }

    /** Номер: повний і останні 6 цифр; в останню чергу (lastResort) - лише останні 4 цифри. */
    private fun numberVariants(number: String, lastResort: Boolean): List<String> {
        if (lastResort) {
            val longest = longestDigits(number)
            return if (longest != null && longest.length > NUMBER_SHORTEST) {
                listOf(longest.takeLast(NUMBER_SHORTEST))
            } else {
                emptyList()
            }
        }
        val set = LinkedHashSet<String>()
        set.add(number)
        set.add(shortNumber(number))
        return set.toList()
    }

    /** Дата: повна, далі "…ММ.рррр", далі "…ММ.рр". */
    private fun dateVariants(rest: String): List<String> {
        val dm = DATE_REGEX.find(rest) ?: return listOf("не_вказано")
        val month = dm.groupValues[2].padStart(2, '0')
        val year = dm.groupValues[3]
        return listOf(dm.value, "…$month.$year", "…$month.${year.takeLast(2)}")
    }

    /** Найдовша група цифр (від 4 цифр) -> останні 6 цифр; інакше номер як є (не довше 10 символів). */
    fun shortNumber(number: String): String {
        val longest = longestDigits(number)
        return if (longest != null && longest.length >= 4) {
            longest.takeLast(NUMBER_DIGITS)
        } else {
            number.takeLast(NUMBER_PLAIN_MAX)
        }
    }

    private fun longestDigits(number: String): String? =
        DIGITS_REGEX.findAll(number).map { it.value }.maxByOrNull { it.length }
}
