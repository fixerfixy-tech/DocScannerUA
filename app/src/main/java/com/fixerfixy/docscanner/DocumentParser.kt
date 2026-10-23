package com.fixerfixy.docscanner

object DocumentParser {

    private val MONTH_MAP = mapOf(
        "січ" to "01", "лют" to "02", "бер" to "03", "квіт" to "04",
        "трав" to "05", "черв" to "06", "лип" to "07", "серп" to "08",
        "верес" to "09", "жовт" to "10", "лист" to "11", "груд" to "12"
    )

    fun generateFileName(rawText: String): String {
        val lines = rawText.lines()
            .map { it.replace('\u00A0', ' ').trim() }
            .filter { it.isNotBlank() }

        // 1. Визначення назви та її розташування
        val (title, titleIndex) = detectTitleAndPosition(lines)

        // 2. Формування робочого блоку шапки для пошуку номера
        val headerArea = if (titleIndex != -1) {
            val start = (titleIndex - 1).coerceAtLeast(0)
            val end = (titleIndex + 10).coerceAtMost(lines.size)
            lines.subList(start, end)
        } else {
            lines.take(20)
        }

        // Очищаємо від рядків з договорами та розрахунковими документами
        val cleanHeader = headerArea.filterNot { isContractOrOrderLine(it) }

        // 3. Пошук номера документа
        var num = extractNumber(cleanHeader)
        if (num == "б-н") {
            // Резервний пошук номера по всьому тексту без договорів
            val allClean = lines.filterNot { isContractOrOrderLine(it) }
            num = extractNumber(allClean)
        }

        // 4. Пошук дати документа
        // Крок А: шукаємо дату безпосередньо в шапці (для рахунків і накладних)
        var date = extractDate(cleanHeader)

        // Крок Б: якщо в шапці дати немає (як в актах), шукаємо по всьому документу
        // суворо ігноруючи рядки договорів, замовлень і додатків
        if (date == "не_вказано") {
            val allClean = lines.filterNot { isContractOrOrderLine(it) }
            date = extractDate(allClean)
        }

        return "${title}_No_${num}_від_${date}_р.pdf"
    }

    private fun isContractOrOrderLine(line: String): Boolean {
        return Regex(
            """(?:до\s+договор|договір|договору|замовленн|підстав|розр\.?\s*док|додаток|вид\s+цивільн)""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(line)
    }

    private fun detectTitleAndPosition(lines: List<String>): Pair<String, Int> {
        for ((idx, line) in lines.withIndex()) {
            if (isContractOrOrderLine(line)) continue

            // Перевіряємо контекст (поточний і наступні 3 рядки) для складних назв актів
            val context = lines.subList(idx, (idx + 4).coerceAtMost(lines.size)).joinToString(" ")

            when {
                Regex("""видаткова\s+накладна""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> return Pair("Видаткова накладна", idx)
                Regex("""податкова\s+накладна""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> return Pair("Податкова накладна", idx)
                Regex("""товарно-транспортна|\bттн\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> return Pair("Товарно-транспортна накладна", idx)
                Regex("""рахунок\s*[-—–]?\s*акт""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> return Pair("Рахунок-акт", idx)
                Regex("""рахунок\s*[-—–]?\s*фактур""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> return Pair("Рахунок-фактура", idx)
                Regex("""рахунок\s+на\s+оплат""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> return Pair("Рахунок на оплату", idx)
                Regex("""\bакт\b.*(?:здач[іе]-прийнятт|прий[оа]м.*передач)""", RegexOption.IGNORE_CASE).containsMatchIn(context) -> return Pair("Акт здачі-прийняття робіт", idx)
                Regex("""\bакт\b.*(?:надання\s+послуг|послуг)""", RegexOption.IGNORE_CASE).containsMatchIn(context) -> return Pair("Акт надання послуг", idx)
                Regex("""\bакт\b.*викон""", RegexOption.IGNORE_CASE).containsMatchIn(context) -> return Pair("Акт виконаних робіт", idx)
                Regex("""специфікаці""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> return Pair("Специфікація", idx)
                Regex("""платіжне\s+доручення""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> return Pair("Платіжне доручення", idx)
                Regex("""квитанці""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> return Pair("Квитанція", idx)
                Regex("""\bчек\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> return Pair("Чек", idx)
                Regex("""\bрахунок\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> return Pair("Рахунок", idx)
                Regex("""\bакт\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> return Pair("Акт", idx)
                Regex("""\bнакладна\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> return Pair("Накладна", idx)
            }
        }

        return Pair("Документ", -1)
    }

    private fun extractNumber(lines: List<String>): String {
        // Підтримка кирилиці, латиниці, цифр, дефісів та слешів (ЛТ-0000272, 1600032771 тощо)
        val numPattern = Regex(
            """(?:№|N[oOоОeEеЕ2]?|номер|#)\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-/._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*)""",
            RegexOption.IGNORE_CASE
        )

        // Спроба 1: Знак номера і значення в одному рядку
        for (line in lines) {
            val match = numPattern.find(line)
            if (match != null) {
                val candidate = match.groupValues[1]
                    .replace("""\s+""".toRegex(), "")
                    .trimEnd('.', ',', ';', ':')
                if (candidate.any { it.isDigit() } || candidate.equals("б-н", true) || candidate.equals("б/н", true)) {
                    return candidate.replace('/', '-')
                }
            }
        }

        // Спроба 2: Рядок "АКТ [номер]" без знака № (якщо OCR пропустив значок)
        for (line in lines) {
            val directMatch = Regex("""\bакт\s+([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+[-/._][0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)""", RegexOption.IGNORE_CASE).find(line)
            if (directMatch != null) {
                val candidate = directMatch.groupValues[1].trimEnd('.', ',', ';', ':')
                if (candidate.any { it.isDigit() }) {
                    return candidate.replace('/', '-')
                }
            }
        }

        // Спроба 3: Значення перед словом "від"
        for (line in lines) {
            val vidMatch = Regex("""(.*)\s+[вВ][іІiI1][дД]\s+""", RegexOption.IGNORE_CASE).find(line)
            if (vidMatch != null) {
                val words = vidMatch.groupValues[1].trim().split("""\s+""".toRegex())
                val lastWord = words.lastOrNull()?.trimEnd('.', ',', ';', ':') ?: ""
                val cleanWord = lastWord.replace(Regex("""^(?:№|N[oOоОeEеЕ2]?|номер|#)[:.-]*""", RegexOption.IGNORE_CASE), "")
                if (cleanWord.any { it.isDigit() } || cleanWord.equals("б-н", true) || cleanWord.equals("б/н", true)) {
                    return cleanWord.replace('/', '-')
                }
            }
        }

        return "б-н"
    }

    private fun extractDate(lines: List<String>): String {
        val block = lines.joinToString(" ")

        // 1. Пріоритетний пошук: дата з прив'язкою до слів "від" або "дата"
        val explicitMatch = Regex(
            """(?:від|дата)\s*[:.]?\s*([0-9]{1,2}[. /-][0-9]{1,2}[. /-][0-9]{2,4}|[0-9]{1,2}\s+[^0-9\s.,/-]+\s+[0-9]{2,4})""",
            RegexOption.IGNORE_CASE
        ).find(block)

        // 2. Якщо явного слова "від" немає (як у підписах акта), шукаємо будь-яку дату формату "DD місяць YYYY" або "DD.MM.YYYY"
        val rawDate = explicitMatch?.groupValues?.get(1) ?: run {
            Regex("""([0-9]{1,2}\s+[а-яА-ЯіїєґІЇЄҐ']+\s+[0-9]{4}|[0-9]{1,2}[. /-][0-9]{1,2}[. /-][0-9]{2,4})""").find(block)?.value
        }

        if (rawDate != null) {
            // Числовий формат: 30.09.2026
            val numDate = Regex("""([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{2,4})""").find(rawDate)
            if (numDate != null) {
                val d = numDate.groupValues[1].padStart(2, '0')
                val m = numDate.groupValues[2].padStart(2, '0')
                var y = numDate.groupValues[3]
                if (y.length == 2) y = "20$y"
                return "$d.$m.$y"
            }

            // Текстовий формат: 30 Вересня 2026
            val textDate = Regex("""([0-9]{1,2})\s+([^0-9\s.,/-]+)\s+([0-9]{2,4})""").find(rawDate)
            if (textDate != null) {
                val d = textDate.groupValues[1].padStart(2, '0')
                val monthWord = textDate.groupValues[2].lowercase()
                var y = textDate.groupValues[3]
                if (y.length == 2) y = "20$y"

                val m = MONTH_MAP.entries.firstOrNull { monthWord.contains(it.key) }?.value
                if (m != null) {
                    return "$d.$m.$y"
                }
            }
        }

        return "не_вказано"
    }
}
