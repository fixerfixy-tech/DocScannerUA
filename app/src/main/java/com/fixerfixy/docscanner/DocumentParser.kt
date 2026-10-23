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

        val topLines = lines.take(35)

        // 1. Визначення назви за найпершим згадуванням
        val (title, titleIndex) = detectTitleAndPosition(topLines)

        // 2. Ізоляція блоку після заголовка
        val candidateBlock = if (titleIndex != -1) {
            topLines.drop(titleIndex).take(8)
        } else {
            topLines.take(8)
        }

        // 3. Фільтрація рядків із супутніми документами (договори, замовлення тощо)
        val cleanBlock = candidateBlock.filterNot { line ->
            Regex("(до\\s+договор|договір|договору|замовленн|підстав|розр\\.?\\s*док|додаток|вид\\s+цивільн)", RegexOption.IGNORE_CASE)
                .containsMatchIn(line)
        }

        // 4. Пошук номера документа (з підтримкою кириличних літер ЛТ-, СФ-, ВН-)
        val num = extractNumber(cleanBlock)

        // 5. Пошук дати документа
        val date = extractDate(cleanBlock)

        return "${title}_No_${num}_від_${date}_р.pdf"
    }

    private fun detectTitleAndPosition(lines: List<String>): Pair<String, Int> {
        val patterns = listOf(
            Regex("видаткова\\s+накладна", RegexOption.IGNORE_CASE) to "Видаткова накладна",
            Regex("податкова\\s+накладна", RegexOption.IGNORE_CASE) to "Податкова накладна",
            Regex("товарно-транспортна|\\bттн\\b", RegexOption.IGNORE_CASE) to "Товарно-транспортна накладна",
            Regex("рахунок\\s*[-—–]?\\s*акт", RegexOption.IGNORE_CASE) to "Рахунок-акт",
            Regex("рахунок\\s*[-—–]?\\s*фактур", RegexOption.IGNORE_CASE) to "Рахунок-фактура",
            Regex("рахунок\\s+на\\s+оплат", RegexOption.IGNORE_CASE) to "Рахунок на оплату",
            Regex("акт\\s+(надання\\s+послуг|послуг)", RegexOption.IGNORE_CASE) to "Акт надання послуг",
            Regex("акт\\s+прий[оа]м", RegexOption.IGNORE_CASE) to "Акт прийому-передачі",
            Regex("акт\\s+викон", RegexOption.IGNORE_CASE) to "Акт виконаних робіт",
            Regex("специфікаці", RegexOption.IGNORE_CASE) to "Специфікація",
            Regex("платіжне\\s+доручення", RegexOption.IGNORE_CASE) to "Платіжне доручення",
            Regex("квитанці", RegexOption.IGNORE_CASE) to "Квитанція",
            Regex("\\bчек\\b", RegexOption.IGNORE_CASE) to "Чек",
            Regex("\\bрахунок\\b", RegexOption.IGNORE_CASE) to "Рахунок",
            Regex("\\bакт\\b", RegexOption.IGNORE_CASE) to "Акт",
            Regex("\\bнакладна\\b", RegexOption.IGNORE_CASE) to "Накладна"
        )

        for ((idx, line) in lines.withIndex()) {
            if (Regex("(розр\\.?\\s*док|згідно|підстава)", RegexOption.IGNORE_CASE).containsMatchIn(line)) continue

            for ((regex, title) in patterns) {
                if (regex.containsMatchIn(line)) {
                    return Pair(title, idx)
                }
            }
        }

        return Pair("Документ", -1)
    }

    private fun extractNumber(lines: List<String>): String {
        // Регулярний вираз підтримує кирилицю, латиницю, цифри, дефіси та дроби (наприклад: ЛТ-0000176, СФ-42, 128/3)
        val numPattern = Regex(
            """(?:№|N[oOоОeEеЕ2]?|номер|#)\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-/._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*)""",
            RegexOption.IGNORE_CASE
        )

        // Спроба 1: Знак номера і сам номер в одному рядку
        for (line in lines) {
            val match = numPattern.find(line)
            if (match != null) {
                val candidate = match.groupValues[1]
                    .replace("\\s+".toRegex(), "") // видаляємо випадкові пробіли навколо дефіса
                    .trimEnd('.', ',', ';', ':')
                if (candidate.any { it.isDigit() } || candidate.equals("б-н", true) || candidate.equals("б/н", true)) {
                    return candidate.replace('/', '-')
                }
            }
        }

        // Спроба 2: Останнє слово/номер перед словом "від"
        for (line in lines) {
            val vidMatch = Regex("""(.*)\s+[вВ][іІiI1][дД]\s+""", RegexOption.IGNORE_CASE).find(line)
            if (vidMatch != null) {
                val words = vidMatch.groupValues[1].trim().split("\\s+".toRegex())
                val lastWord = words.lastOrNull()?.trimEnd('.', ',', ';', ':') ?: ""
                val cleanWord = lastWord.replace(Regex("""^(?:№|N[oOоОeEеЕ2]?|номер|#)[:.-]*""", RegexOption.IGNORE_CASE), "")
                if (cleanWord.any { it.isDigit() } || cleanWord.equals("б-н", true) || cleanWord.equals("б/н", true)) {
                    return cleanWord.replace('/', '-')
                }
            }
        }

        // Спроба 3: Знак номера в кінці рядка, а номер перенесено на наступний
        for (i in 0 until lines.size - 1) {
            if (Regex("""(?:№|N[oOоОeEеЕ2]?|номер|#)\s*$""", RegexOption.IGNORE_CASE).containsMatchIn(lines[i])) {
                val nextWords = lines[i + 1].trim().split("\\s+".toRegex())
                val candidate = nextWords.firstOrNull()?.trimEnd('.', ',', ';', ':') ?: ""
                if (candidate.any { it.isDigit() }) {
                    return candidate.replace('/', '-')
                }
            }
        }

        return "б-н"
    }

    private fun extractDate(lines: List<String>): String {
        val block = lines.joinToString(" ")

        val explicitMatch = Regex("""(?:від|дата)\s*[:.]?\s*([0-9]{1,2}[. /-][0-9]{1,2}[. /-][0-9]{2,4}|[0-9]{1,2}\s+[^0-9\s.,/-]+\s+[0-9]{2,4})""", RegexOption.IGNORE_CASE).find(block)
        val rawDate = explicitMatch?.groupValues?.get(1) ?: run {
            Regex("""([0-9]{1,2}[. /-][0-9]{1,2}[. /-][0-9]{2,4}|[0-9]{1,2}\s+[^0-9\s.,/-]+\s+[0-9]{2,4})""").find(block)?.value
        }

        if (rawDate != null) {
            val numDate = Regex("""([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{2,4})""").find(rawDate)
            if (numDate != null) {
                val d = numDate.groupValues[1].padStart(2, '0')
                val m = numDate.groupValues[2].padStart(2, '0')
                var y = numDate.groupValues[3]
                if (y.length == 2) y = "20$y"
                return "$d.$m.$y"
            }

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
