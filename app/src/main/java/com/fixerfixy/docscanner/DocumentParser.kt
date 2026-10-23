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

        // 1. Пошук номера документа (з найвищим пріоритетом для рядка "АКТ № ...")
        val num = extractDocumentNumber(lines)

        // 2. Визначення типу документа
        val title = detectTitle(lines)

        // 3. Пошук дати документа
        val date = extractDocumentDate(lines)

        return "${title}_No_${num}_від_${date}_р.pdf"
    }

    private fun isContractOrOrderLine(line: String): Boolean {
        return Regex(
            """(?:до\s+договор|договір|договору|замовленн|підстав|розр\.?\s*док|додаток|вид\s+цивільн)""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(line)
    }

    private fun isBodySentence(line: String): Boolean {
        return Regex(
            """(?:склали\s+цей\s+акт|представники|з\s+одного\s+боку|проведені\s+такі\s+роботи|претензій\s+не\s+мають)""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(line)
    }

    private fun detectTitle(lines: List<String>): String {
        // Аналізуємо перші 25 рядків (шапку) як єдиний контекст
        val topLines = lines.take(25).filterNot { isContractOrOrderLine(it) || isBodySentence(it) }
        val headerBlock = normalizeText(topLines.joinToString(" "))

        return when {
            Regex("""видаткова\s+накладна""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Видаткова накладна"
            Regex("""податкова\s+накладна""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Податкова накладна"
            Regex("""товарно-транспортна|\bттн\b""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Товарно-транспортна накладна"
            Regex("""рахунок\s*[-—–]?\s*акт""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Рахунок-акт"
            Regex("""рахунок\s*[-—–]?\s*фактур""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Рахунок-фактура"
            Regex("""рахунок\s+на\s+оплат""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Рахунок на оплату"
            Regex("""\bакт\b.*(?:здач[іе]-прийнятт|прий[оа]м.*передач)""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Акт здачі-прийняття робіт"
            Regex("""\bакт\b.*(?:надання\s+послуг|послуг)""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Акт надання послуг"
            Regex("""\bакт\b.*викон""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Акт виконаних робіт"
            Regex("""специфікаці""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Специфікація"
            Regex("""платіжне\s+доручення""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Платіжне доручення"
            Regex("""квитанці""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Квитанція"
            Regex("""\bчек\b""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Чек"
            Regex("""\bрахунок\b""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Рахунок"
            Regex("""\bакт\b""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Акт"
            Regex("""\bнакладна\b""", RegexOption.IGNORE_CASE).containsMatchIn(headerBlock) -> "Накладна"
            else -> "Документ"
        }
    }

    private fun extractDocumentNumber(lines: List<String>): String {
        val cleanLines = lines.filterNot { isContractOrOrderLine(it) }

        // Пріоритет 1: Рядок прямого заголовка документа "АКТ / РАХУНОК № ЛТ-0000272"
        val directHeaderPattern = Regex(
            """(?i)(?:акт|рахунок|накладна)\s*(?:№|N[oOоОeEеЕ2]?|номер|#)?\s*[:.]?\s*([\p{L}\p{N}]+(?:[-/._][\p{L}\p{N}]+)*)"""
        )
        for (line in cleanLines.take(15)) {
            val norm = normalizeText(line)
            val match = directHeaderPattern.find(norm)
            if (match != null) {
                val candidate = match.groupValues[1].replace("""\s+""".toRegex(), "").trimEnd('.', ',', ';', ':')
                if (candidate.any { it.isDigit() }) {
                    return candidate.replace('/', '-')
                }
            }
        }

        // Пріоритет 2: Будь-який рядок зі знаком №
        val numPattern = Regex(
            """(?:№|N[oOоОeEеЕ2]?|номер|#)\s*[:.]?\s*([\p{L}\p{N}]+(?:\s*[-/._]\s*[\p{L}\p{N}]+)*)""",
            RegexOption.IGNORE_CASE
        )
        for (line in cleanLines) {
            val match = numPattern.find(line)
            if (match != null) {
                val candidate = match.groupValues[1].replace("""\s+""".toRegex(), "").trimEnd('.', ',', ';', ':')
                if (candidate.any { it.isDigit() } || candidate.equals("б-н", true) || candidate.equals("б/н", true)) {
                    return candidate.replace('/', '-')
                }
            }
        }

        // Пріоритет 3: Останнє слово перед словом "від"
        for (line in cleanLines) {
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

    private fun extractDocumentDate(lines: List<String>): String {
        val cleanLines = lines.filterNot { isContractOrOrderLine(it) }
        val block = cleanLines.joinToString(" ")

        // 1. Дата після слів "від" або "дата"
        val explicitMatch = Regex(
            """(?:від|дата)\s*[:.]?\s*([0-9]{1,2}[. /-][0-9]{1,2}[. /-][0-9]{2,4}|[0-9]{1,2}\s*[-–—./]?\s*\p{L}{3,12}\s*[0-9]{2,4})""",
            RegexOption.IGNORE_CASE
        ).find(block)

        val rawDate = explicitMatch?.groupValues?.get(1) ?: run {
            // 2. Будь-яка дата у форматі ДД.ММ.ГГГГ або ДД-Місяць-ГГГГ (як у підписах акта: "30-Вересня 2026")
            Regex("""(?<!\d)([0-9]{1,2})\s*[-–—./]\s*(\p{L}{3,12})\s*([0-9]{2,4})""").find(block)?.value
                ?: Regex("""(?<!\d)([0-9]{1,2})\s+(\p{L}{3,12})\s+([0-9]{2,4})""").find(block)?.value
                ?: Regex("""(?<!\d)([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{2,4})(?!\d)""").find(block)?.value
        }

        if (rawDate != null) {
            // Числовий формат
            val numDate = Regex("""([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{2,4})""").find(rawDate)
            if (numDate != null) {
                val d = numDate.groupValues[1].padStart(2, '0')
                val m = numDate.groupValues[2].padStart(2, '0')
                var y = numDate.groupValues[3]
                if (y.length == 2) y = "20$y"
                return "$d.$m.$y"
            }

            // Текстовий формат з назвою місяця
            val textDate = Regex("""([0-9]{1,2})\s*[-–—./]?\s*(\p{L}{3,12})\s*([0-9]{2,4})""").find(rawDate)
            if (textDate != null) {
                val d = textDate.groupValues[1].padStart(2, '0')
                val monthWord = normalizeText(textDate.groupValues[2].lowercase())
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

    // Заміна схожих латинських літер на кирилицю для усунення помилок OCR
    private fun normalizeText(text: String): String {
        return text
            .replace('a', 'а').replace('A', 'А')
            .replace('e', 'е').replace('E', 'Е')
            .replace('i', 'і').replace('I', 'І')
            .replace('o', 'о').replace('O', 'О')
            .replace('p', 'р').replace('P', 'Р')
            .replace('c', 'с').replace('C', 'С')
            .replace('x', 'х').replace('X', 'Х')
            .replace('y', 'у').replace('Y', 'У')
            .replace('T', 'Т').replace('K', 'К')
            .replace('H', 'Н').replace('B', 'В')
            .replace('M', 'М')
    }
}
