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

        val title = detectTitle(topLines)

        val headerIdx = topLines.indexOfFirst { line ->
            Regex("(рахунок|акт|накладна|специфікац|договір|квитанц|чек)", RegexOption.IGNORE_CASE).containsMatchIn(line)
        }

        val targetBlock = if (headerIdx != -1) {
            topLines.drop(headerIdx).take(6)
        } else {
          topLines.take(8)
        }

        val cleanTarget = targetBlock.map { line ->
            line.replace(Regex("\\s+(згідно|до\\s+договор).*", RegexOption.IGNORE_CASE), "")
        }.filter { line ->
            !Regex("^(договір|підстава|вид цивільно):", RegexOption.IGNORE_CASE).containsMatchIn(line)
        }

        val num = extractNumber(cleanTarget)
        val date = extractDate(cleanTarget)

        return "${title}_No_${num}_від_${date}_р.pdf"
    }

    private fun detectTitle(lines: List<String>): String {
        val fullHeader = lines.take(15).joinToString(" ")
        return when {
            Regex("рахунок\\s*[-—–]?\\s*акт", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Рахунок-акт"
            Regex("рахунок\\s*[-—–]?\\s*фактур", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Рахунок-фактура"
            Regex("рахунок\\s+на\\s+оплат", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Рахунок на оплату"
            Regex("специфікаці", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Специфікація"
            Regex("видаткова\\s+накладна", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Видаткова накладна"
            Regex("податкова\\s+накладна", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Податкова накладна"
            Regex("товарно-транспортна|ттн", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Товарно-транспортна накладна"
            Regex("акт\\s+(надання\\s+послуг|послуг)", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Акт надання послуг"
            Regex("акт\\s+прийому", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Акт прийому-передачі"
            Regex("акт\\s+виконаних", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Акт виконаних робіт"
            Regex("(^|\\s)акт(\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Акт надання послуг"
            Regex("(^|\\s)рахунок(\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Рахунок"
            Regex("(^|\\s)накладна(\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Накладна"
            Regex("платіжне\\s+доручення", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Платіжне доручення"
            Regex("квитанці", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Квитанція"
            Regex("чек", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Чек"
            Regex("договір|угода", RegexOption.IGNORE_CASE).containsMatchIn(fullHeader) -> "Договір"
            else -> "Документ"
        }
    }

    private fun extractNumber(lines: List<String>): String {
        for (line in lines) {
            val match = Regex("(№|N[oOоОeEеЕ2]?|номер|#)\\s*([0-9a-zA-Z._/-]+)", RegexOption.IGNORE_CASE).find(line)
            if (match != null) {
                val candidate = match.groupValues[2].trim().trimEnd('.', ',', ';', ':')
                if (candidate.any { it.isDigit() } || candidate.equals("б-н", true) || candidate.equals("б/н", true)) {
                    return candidate.replace('/', '-')
                }
            }
        }

        for (line in lines) {
            val vidMatch = Regex("(.*)\\s+[вВ][іІiI1][дД]\\s+", RegexOption.IGNORE_CASE).find(line)
            if (vidMatch != null) {
                val words = vidMatch.groupValues[1].trim().split("\\s+".toRegex())
                val lastWord = words.lastOrNull()?.trimEnd('.', ',', ';', ':') ?: ""
                val cleanWord = lastWord.replace(Regex("^(№|N[oOоОeEеЕ2]?|номер|#)[:.-]*", RegexOption.IGNORE_CASE), "")
                if (cleanWord.any { it.isDigit() } || cleanWord.equals("б-н", true) || cleanWord.equals("б/н", true)) {
                    return cleanWord.replace('/', '-')
                }
            }
        }

        return "б-н"
    }

    private fun extractDate(lines: List<String>): String {
        val block = lines.joinToString(" ")

        val explicitMatch = Regex("(від|дата)\\s*[:]?\\s*([0-9]{1,2}[. /-][0-9]{1,2}[. /-][0-9]{2,4}|[0-9]{1,2}\\s+[^0-9\\s.,/-]+\\s+[0-9]{2,4})", RegexOption.IGNORE_CASE).find(block)
        val rawDate = explicitMatch?.groupValues?.get(2) ?: run {
            Regex("([0-9]{1,2}[. /-][0-9]{1,2}[. /-][0-9]{2,4}|[0-9]{1,2}\\s+[^0-9\\s.,/-]+\\s+[0-9]{2,4})").find(block)?.value
        }

        if (rawDate != null) {
            val numDate = Regex("([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{2,4})").find(rawDate)
            if (numDate != null) {
                val d = numDate.groupValues[1].padStart(2, '0')
                val m = numDate.groupValues[2].padStart(2, '0')
                var y = numDate.groupValues[3]
                if (y.length == 2) y = "20$y"
                return "$d.$m.$y"
            }

            val textDate = Regex("([0-9]{1,2})\\s+([^0-9\\s.,/-]+)\\s+([0-9]{2,4})").find(rawDate)
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
