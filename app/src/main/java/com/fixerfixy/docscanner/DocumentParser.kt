package com.fixerfixy.docscanner

object DocumentParser {

    private val MONTH_MAP = mapOf(
        "січ" to "01", "лют" to "02", "бер" to "03", "квіт" to "04",
        "трав" to "05", "черв" to "06", "лип" to "07", "серп" to "08",
        "верес" to "09", "жовт" to "10", "лист" to "11", "груд" to "12"
    )

    fun parse(rawText: String): ParsedDocument {
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

    private fun isNoiseOrContract(line: String): Boolean {
        return Regex(
            """(?:до\s+договор|договір|договору|замовленн|підстав|розр\.?\s*док|додаток|вид\s+цивільн|склали\s+цей\s+акт|представники)""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(line)
    }

    private fun extractType(lines: List<String>): String {
        val header = normalize(lines.take(25).filterNot { isNoiseOrContract(it) }.joinToString(" "))

        return when {
            Regex("""видаткова\s+накладна""").containsMatchIn(header) -> "Видаткова накладна"
            Regex("""податкова\s+накладна""").containsMatchIn(header) -> "Податкова накладна"
            Regex("""товарно-транспортна|\bттн\b""").containsMatchIn(header) -> "Товарно-транспортна накладна"
            Regex("""рахунок\s*[-—–]?\s*акт""").containsMatchIn(header) -> "Рахунок-акт"
            Regex("""рахунок\s*[-—–]?\s*фактур""").containsMatchIn(header) -> "Рахунок-фактура"
            Regex("""рахунок\s+на\s+оплат""").containsMatchIn(header) -> "Рахунок на оплату"
            Regex("""\bакт\b.*(?:здач|прий[оа]м)""").containsMatchIn(header) -> "Акт здачі-прийняття робіт"
            Regex("""\bакт\b.*(?:надання\s+послуг|послуг)""").containsMatchIn(header) -> "Акт надання послуг"
            Regex("""\bакт\b.*викон""").containsMatchIn(header) -> "Акт виконаних робіт"
            Regex("""специфікаці""").containsMatchIn(header) -> "Специфікація"
            Regex("""платіжне\s+доручення""").containsMatchIn(header) -> "Платіжне доручення"
            Regex("""квитанці""").containsMatchIn(header) -> "Квитанція"
            Regex("""\bчек\b""").containsMatchIn(header) -> "Чек"
            Regex("""\bрахунок\b""").containsMatchIn(header) -> "Рахунок"
            Regex("""\bакт\b""").containsMatchIn(header) -> "Акт"
            Regex("""\bнакладна\b""").containsMatchIn(header) -> "Накладна"
            else -> "Документ"
        }
    }

    private fun extractNumber(lines: List<String>): String {
        val cleanLines = lines.filterNot { isNoiseOrContract(it) }

        // 1. Прямий заголовок: АКТ № ЛТ-0000272 або Рахунок № 1600032771
        val headerPattern = Regex(
            """(?i)(?:акт|рахунок|накладна)\s*(?:№|N[oOоОeEеЕ2]?|номер|#)?\s*[:.]?\s*([\p{L}\p{N}]+(?:[-/._][\p{L}\p{N}]+)*)"""
        )
        for (line in cleanLines.take(15)) {
            val match = headerPattern.find(normalize(line))
            if (match != null) {
                val candidate = match.groupValues[1].replace("""\s+""".toRegex(), "").trimEnd('.', ',', ';', ':')
                if (candidate.any { it.isDigit() }) return candidate
            }
        }

        // 2. Будь-який рядок зі знаком № (включаючи кириличні префікси ЛТ-, СФ-)
        val numPattern = Regex(
            """(?:№|N[oOоОeEеЕ2]?|номер|#)\s*[:.]?\s*([\p{L}\p{N}]+(?:\s*[-/._]\s*[\p{L}\p{N}]+)*)""",
            RegexOption.IGNORE_CASE
        )
        for (line in cleanLines) {
            val match = numPattern.find(line)
            if (match != null) {
                val candidate = match.groupValues[1].replace("""\s+""".toRegex(), "").trimEnd('.', ',', ';', ':')
                if (candidate.any { it.isDigit() } || candidate.equals("б-н", true) || candidate.equals("б/н", true)) {
                    return candidate
                }
            }
        }

        // 3. Слово перед словом "від"
        for (line in cleanLines) {
            val vidMatch = Regex("""(.*)\s+[вВ][іІiI1][дД]\s+""").find(line)
            if (vidMatch != null) {
                val words = vidMatch.groupValues[1].trim().split("""\s+""".toRegex())
                val lastWord = words.lastOrNull()?.trimEnd('.', ',', ';', ':') ?: ""
                val cleanWord = lastWord.replace(Regex("""^(?:№|N[oOоОeEеЕ2]?|номер|#)[:.-]*""", RegexOption.IGNORE_CASE), "")
                if (cleanWord.any { it.isDigit() } || cleanWord.equals("б-н", true)) {
                    return cleanWord
                }
            }
        }

        return "б-н"
    }

    private fun extractDate(lines: List<String>): String {
        val cleanLines = lines.filterNot { isNoiseOrContract(it) }
        val block = cleanLines.joinToString(" ")

        // Пошук дати: ДД.ММ.ГГГГ або ДД-Місяць-ГГГГ (у шапці або в підписах акта)
        val dateRegex = Regex(
            """(?<!\d)([0-9]{1,2})\s*[-–—./]?\s*(\p{L}{3,12})\s*([0-9]{2,4})|(?<!\d)([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{2,4})(?!\d)"""
        )

        for (match in dateRegex.findAll(block)) {
            val raw = match.value
            val numDate = Regex("""([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{2,4})""").find(raw)
            if (numDate != null) {
                val d = numDate.groupValues[1].padStart(2, '0')
                val m = numDate.groupValues[2].padStart(2, '0')
                var y = numDate.groupValues[3]
                if (y.length == 2) y = "20$y"
                return "$d.$m.$y"
            }

            val textDate = Regex("""([0-9]{1,2})\s*[-–—./]?\s*(\p{L}{3,12})\s*([0-9]{2,4})""").find(raw)
            if (textDate != null) {
                val d = textDate.groupValues[1].padStart(2, '0')
                val monthWord = normalize(textDate.groupValues[2].lowercase())
                var y = textDate.groupValues[3]
                if (y.length == 2) y = "20$y"

                val m = MONTH_MAP.entries.firstOrNull { monthWord.contains(it.key) }?.value
                if (m != null) return "$d.$m.$y"
            }
        }

        return "не_вказано"
    }

    private fun normalize(text: String): String {
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
