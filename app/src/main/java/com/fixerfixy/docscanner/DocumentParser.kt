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
        val number = extractNumber(lines)
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

    private fun extractNumber(lines: List<String>): String {
        val cleanLines = lines.filterNot { isContractOrNoise(it) }

        // Шаблон номера документа з підтримкою знаків №, No, # та змішаних кириличних літер (наприклад, ЛТ-0000272)
        val numRegex = Regex(
            """(?iu)(?:№|N[oOоОeEеЕ2]?|номер|#)\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-/._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*)"""
        )

        for (line in cleanLines) {
            val match = numRegex.find(line)
            if (match != null) {
                val candidate = match.groupValues[1].replace("""\s+""".toRegex(), "").trimEnd('.', ',', ';', ':')
                if (candidate.any { it.isDigit() } || candidate.equals("б-н", true) || candidate.equals("б/н", true)) {
                    return candidate.replace('/', '-').uppercase()
                }
            }
        }

        // Резервний варіант: слово перед "від"
        for (line in cleanLines) {
            val vidMatch = Regex("""(?iu)(.*)\s+[вВ][іІiI1][дД]\s+""").find(line)
            if (vidMatch != null) {
                val words = vidMatch.groupValues[1].trim().split("""\s+""".toRegex())
                val lastWord = words.lastOrNull()?.trimEnd('.', ',', ';', ':') ?: ""
                val cleanWord = lastWord.replace(Regex("""(?iu)^(?:№|N[oOоОeEеЕ2]?|номер|#)[:.-]*"""), "")
                if (cleanWord.any { it.isDigit() } || cleanWord.equals("б-н", true)) {
                    return cleanWord.replace('/', '-').uppercase()
                }
            }
        }

        return "б-н"
    }

    private fun extractDate(lines: List<String>): String {
        val cleanLines = lines.filterNot { isContractOrNoise(it) }
        val block = cleanLines.joinToString(" ")

        // 1. Пошук дати після слів "від" або "дата"
        val vidMatch = Regex(
            """(?iu)(?:від|дата)\s*[:.]?\s*([0-9]{1,2}\s*[-–—./]?\s*[a-zA-Zа-яА-ЯіїєґІЇЄҐ']+\s*[0-9]{2,4}|[0-9]{1,2}[./-][0-9]{1,2}[./-][0-9]{2,4})"""
        ).find(block)

        val rawDate = vidMatch?.groupValues?.get(1) ?: run {
            // 2. Будь-яка дата формату ДД-Місяць-РРРР або ДД.ММ.РРРР у тексті чи підписах сторін
            Regex(
                """(?iu)([0-9]{1,2}\s*[-–—./]\s*[a-zA-Zа-яА-ЯіїєґІЇЄҐ']+\s*[0-9]{2,4}|[0-9]{1,2}\s+[a-zA-Zа-яА-ЯіїєґІЇЄҐ']+\s+[0-9]{2,4}|(?<!\d)[0-9]{1,2}[./-][0-9]{1,2}[./-][0-9]{2,4}(?!\d))"""
            ).find(block)?.value
        }

        if (rawDate != null) {
            // Числовий варіант (29.09.2026)
            val numDate = Regex("""([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{2,4})""").find(rawDate)
            if (numDate != null) {
                val d = numDate.groupValues[1].padStart(2, '0')
                val m = numDate.groupValues[2].padStart(2, '0')
                var y = numDate.groupValues[3]
                if (y.length == 2) y = "20$y"
                return "$d.$m.$y"
            }

            // Текстовий варіант (30-Вересня 2026 або 19 жовтня 2026)
            val textDate = Regex("""(?iu)([0-9]{1,2})\s*[-–—./]?\s*([a-zA-Zа-яА-ЯіїєґІЇЄҐ']+)\s*([0-9]{2,4})""").find(rawDate)
            if (textDate != null) {
                val d = textDate.groupValues[1].padStart(2, '0')
                val monthWord = normalizeToCyrillic(textDate.groupValues[2].lowercase())
                var y = textDate.groupValues[3]
                if (y.length == 2) y = "20$y"

                val m = MONTH_MAP.entries.firstOrNull { monthWord.contains(it.key) }?.value
                if (m != null) return "$d.$m.$y"
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
