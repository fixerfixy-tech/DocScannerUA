package com.fixerfixy.docscanner

object DocumentParser {

    private val MONTH_MAP = mapOf(
        "січ" to "01", "лют" to "02", "бер" to "03", "квіт" to "04",
        "трав" to "05", "черв" to "06", "лип" to "07", "серп" to "08",
        "верес" to "09", "жовт" to "10", "лист" to "11", "груд" to "12"
    )

    fun parse(rawText: String): ParsedDocument {
        if (rawText.isBlank() || rawText.startsWith("[")) {
            return ParsedDocument(
                type = "Документ",
                number = "б-н",
                date = "не_вказано",
                rawOcrText = rawText
            )
        }

        val lines = rawText.lines()
            .map { it.replace('\u00A0', ' ').trim() }
            .filter { it.isNotBlank() }

        val type = extractType(lines)
        val number = extractNumber(lines, rawText)
        val date = extractDate(lines)

        return ParsedDocument(
            type = type,
            number = number,
            date = date,
            rawOcrText = rawText
        )
    }

    private fun isContractOrNoise(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("договір") || l.contains("договору") || l.contains("до договор") ||
               l.contains("замовленн") || l.contains("розр. док") || l.contains("розр.док") ||
               l.contains("підстав") || l.contains("склали цей акт") || l.contains("представники") ||
               l.contains("претензій не мають")
    }

    private fun extractType(lines: List<String>): String {
        val cleanHeader = lines.take(25).filterNot { isContractOrNoise(it) }.joinToString(" ").lowercase()
        val norm = normalizeToCyrillic(cleanHeader)

        return when {
            norm.contains("видаткова накладна") || (norm.contains("накладна") && norm.contains("видатк")) -> "Видаткова накладна"
            norm.contains("податкова накладна") -> "Податкова накладна"
            norm.contains("товарно-транспортна") || norm.contains(" ттн ") -> "Товарно-транспортна накладна"
            norm.contains("рахунок-акт") || norm.contains("рахунок акт") -> "Рахунок-акт"
            norm.contains("рахунок-фактура") || norm.contains("рахунок фактура") -> "Рахунок-фактура"
            norm.contains("рахунок на оплат") -> "Рахунок на оплату"
            norm.contains("акт") && (norm.contains("здач") || norm.contains("прийм") || norm.contains("прийом")) -> "Акт здачі-прийняття робіт"
            norm.contains("акт") && norm.contains("викон") -> "Акт виконаних робіт"
            norm.contains("акт") && norm.contains("послуг") -> "Акт надання послуг"
            norm.contains("специфікаці") -> "Специфікація"
            norm.contains("платіжне доручення") -> "Платіжне доручення"
            norm.contains("квитанці") -> "Квитанція"
            norm.contains("чек") -> "Чек"
            norm.contains("рахунок") -> "Рахунок"
            norm.contains("акт") -> "Акт"
            norm.contains("накладна") -> "Накладна"
            else -> "Документ"
        }
    }

    private fun extractNumber(lines: List<String>, fullText: String): String {
        val cleanLines = lines.filterNot { isContractOrNoise(it) }

        // 1. Пошук номера з префіксом (наприклад: ЛТ-0000272 або Л Т-0000272)
        val prefixRegex = Regex("""(?iu)\b([а-яА-Яa-zA-Z]{1,2})\s*[-_]\s*([0-9]{4,10})\b""")
        for (line in cleanLines.take(15)) {
            val match = prefixRegex.find(line)
            if (match != null) {
                var p = match.groupValues[1].uppercase().replace('T', 'Т')
                val n = match.groupValues[2]
                if (p == "Т" && (fullText.contains("ЛІТЛАН", true) || fullText.contains("ЛТ-", true))) {
                    p = "ЛТ"
                }
                return "$p-$n"
            }
        }

        // 2. Стандартний пошук зі знаком №
        val numRegex = Regex(
            """(?iu)(?:№|N[oOоОeEеЕ2]?|номер|#)\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-/._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*)"""
        )
        for (line in cleanLines) {
            val match = numRegex.find(line)
            if (match != null) {
                var candidate = match.groupValues[1].replace("""\s+""".toRegex(), "").trimEnd('.', ',', ';', ':')
                if (candidate.any { it.isDigit() } || candidate.equals("б-н", true) || candidate.equals("б/н", true)) {
                    candidate = candidate.replace('/', '-').uppercase()
                    if (candidate.startsWith("T-") || candidate.startsWith("Т-")) {
                        if (fullText.contains("ЛІТЛАН", true) || fullText.contains("ЛТ-", true)) {
                            candidate = "Л" + candidate
                        }
                    }
                    return candidate
                }
            }
        }

        // 3. Значення перед словом "від"
        for (line in cleanLines) {
            val vidMatch = Regex("""(?iu)(.*)\s+[вВ][іІiI1][дД]\s+""").find(line)
            if (vidMatch != null) {
                val words = vidMatch.groupValues[1].trim().split("""\s+""".toRegex())
                val lastWord = words.lastOrNull()?.trimEnd('.', ',', ';', ':') ?: ""
                var cleanWord = lastWord.replace(Regex("""(?iu)^(?:№|N[oOоОeEеЕ2]?|номер|#)[:.-]*"""), "")
                if (cleanWord.any { it.isDigit() } || cleanWord.equals("б-н", true)) {
                    cleanWord = cleanWord.replace('/', '-').uppercase()
                    if (cleanWord.startsWith("T-") || cleanWord.startsWith("Т-")) {
                        if (fullText.contains("ЛІТЛАН", true) || fullText.contains("ЛТ-", true)) {
                            cleanWord = "Л" + cleanWord
                        }
                    }
                    return cleanWord
                }
            }
        }

        return "б-н"
    }

    private fun extractDate(lines: List<String>): String {
        val cleanLines = lines.filterNot { isContractOrNoise(it) }
        val block = cleanLines.joinToString("\n")

        // 1. Пошук текстової дати з ОБОВ'ЯЗКОВОЮ наявністю українського місяця
        // Знаходить "30-Вересня 2026", "30 Вересня 2026", ігнорує таблиці типу "1 посл. 1540"
        val textMonthRegex = Regex(
            """(?iu)\b([0-3]?[0-9])\s*[-–—./\s]\s*((?:січ|лют|бер|квіт|трав|черв|лип|серп|верес|жовт|лист|груд)[a-zA-Zа-яА-ЯіїєґІЇЄҐ]*)\s*(20[2-3][0-9])\b"""
        )
        val textMatches = textMonthRegex.findAll(block).toList()
        if (textMatches.isNotEmpty()) {
            val lastMatch = textMatches.last() // Підписи внизу акта
            val day = lastMatch.groupValues[1].padStart(2, '0')
            val rawMonth = normalizeToCyrillic(lastMatch.groupValues[2].lowercase())
            val year = lastMatch.groupValues[3]
            val monthNum = MONTH_MAP.entries.firstOrNull { rawMonth.contains(it.key) }?.value
            if (monthNum != null && day.toInt() in 1..31) {
                return "$day.$monthNum.$year"
            }
        }

        // 2. Пошук дати після слів "від" або "дата"
        val explicitDateRegex = Regex(
            """(?iu)\b(?:від|дата)\s*[:.]?\s*([0-3]?[0-9]\s*[-–—./\s]\s*(?:січ|лют|бер|квіт|трав|черв|лип|серп|верес|жовт|лист|груд)[a-zA-Zа-яА-ЯіїєґІЇЄҐ]*\s*20[2-3][0-9]|[0-3]?[0-9][./-][0-1]?[0-9][./-](?:20)?[2-3][0-9])"""
        )
        val explicitMatch = explicitDateRegex.find(block)
        if (explicitMatch != null) {
            val parsed = parseDateString(explicitMatch.groupValues[1])
            if (parsed != "не_вказано") return parsed
        }

        // 3. Пошук числової дати ДД.ММ.РРРР
        val numericDateRegex = Regex(
            """(?iu)\b([0-3]?[0-9])[./-]([0-1]?[0-9])[./-](20[2-3][0-9]|[2-3][0-9])\b"""
        )
        val numMatches = numericDateRegex.findAll(block).toList()
        for (m in numMatches) {
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            var y = m.groupValues[3]
            if (y.length == 2) y = "20$y"
            if (d in 1..31 && mo in 1..12) {
                return "${d.toString().padStart(2, '0')}.${mo.toString().padStart(2, '0')}.$y"
            }
        }

        return "не_вказано"
    }

    private fun parseDateString(raw: String): String {
        val numDate = Regex("""([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{2,4})""").find(raw)
        if (numDate != null) {
            val d = numDate.groupValues[1].toInt()
            val m = numDate.groupValues[2].toInt()
            var y = numDate.groupValues[3]
            if (y.length == 2) y = "20$y"
            if (d in 1..31 && m in 1..12) {
                return "${d.toString().padStart(2, '0')}.${m.toString().padStart(2, '0')}.$y"
            }
        }

        val textDate = Regex("""(?iu)([0-9]{1,2})\s*[-–—./\s]\s*([a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)\s*(20[2-3][0-9])""").find(raw)
        if (textDate != null) {
            val d = textDate.groupValues[1].padStart(2, '0')
            val monthWord = normalizeToCyrillic(textDate.groupValues[2].lowercase())
            val y = textDate.groupValues[3]
            val m = MONTH_MAP.entries.firstOrNull { monthWord.contains(it.key) }?.value
            if (m != null && d.toInt() in 1..31) {
                return "$d.$m.$y"
            }
        }
        return "не_вказано"
    }

    private fun normalizeToCyrillic(text: String): String {
        return text
            .replace('a', 'а').replace('e', 'е')
            .replace('i', 'і').replace('o', 'о')
            .replace('p', 'р').replace('c', 'с')
            .replace('x', 'х').replace('y', 'у')
            .replace('k', 'к').replace('m', 'м')
            .replace('h', 'н').replace('t', 'т')
    }
}
