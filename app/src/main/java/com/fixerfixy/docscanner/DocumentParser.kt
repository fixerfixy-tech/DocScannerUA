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

        val type = extractType(rawText)
        val isContractDoc = type == "Договір" || type == "Додаткова угода"
        val number = extractNumber(lines, rawText, isContractDoc)
        val date = extractDate(lines, isContractDoc)

        return ParsedDocument(
            type = type,
            number = number,
            date = date,
            rawOcrText = rawText
        )
    }

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace('a', 'а').replace('e', 'е')
            .replace('i', 'і').replace('o', 'о')
            .replace('p', 'р').replace('c', 'с')
            .replace('x', 'х').replace('y', 'у')
            .replace('k', 'к').replace('m', 'м')
            .replace('h', 'н').replace('t', 'т')
            .replace('b', 'в')
    }

    private fun isNoiseLine(line: String, isContractDoc: Boolean): Boolean {
        val norm = normalize(line)
        if (!isContractDoc) {
            if (norm.contains("договор") || norm.contains("договір")) return true
        }
        return norm.contains("замовлен") || norm.contains("підстав") ||
               norm.contains("розр. док") || norm.contains("розр.док") ||
               norm.contains("склали цей акт") || norm.contains("претензій не мають") ||
               norm.contains("представник")
    }

    private fun extractType(fullText: String): String {
        val norm = normalize(fullText)

        return when {
            // Касові та банківські
            norm.contains("прибутковий касовий ордер") || norm.contains("пко ") -> "Прибутковий касовий ордер"
            norm.contains("видатковий касовий ордер") || norm.contains("вко ") -> "Видатковий касовий ордер"
            norm.contains("журнал реєстрації") && norm.contains("касов") -> "Журнал реєстрації касових документів"
            norm.contains("касова книга") -> "Касова книга"
            norm.contains("платіжна інструкція") -> "Платіжна інструкція"
            norm.contains("платіжне доручення") -> "Платіжне доручення"
            norm.contains("виписка банку") || norm.contains("банківська виписка") || norm.contains("виписка по рахунку") -> "Банківська виписка"

            // Кадри та заробітна плата
            norm.contains("табель обліку") && norm.contains("робочого часу") -> "Табель обліку робочого часу"
            norm.contains("розрахунково-платіжна відомість") || norm.contains("розрахункова відомість") -> "Розрахунково-платіжна відомість"
            norm.contains("наказ") && norm.contains("прийняття") -> "Наказ про прийняття на роботу"
            norm.contains("наказ") && norm.contains("звільнення") -> "Наказ про звільнення"
            norm.contains("наказ") && norm.contains("відпустк") -> "Наказ про надання відпустки"
            norm.contains("наказ") || norm.contains("розпорядження") -> "Наказ"

            // Первинка ТМЦ, ОЗ та послуги
            norm.contains("накладна-вимога") || (norm.contains("накладна") && norm.contains("внутрішнього переміщення")) -> "Накладна-вимога на переміщення"
            norm.contains("акт") && norm.contains("основних засобів") -> "Акт приймання-передачі ОЗ"
            norm.contains("акт") && norm.contains("списання") -> "Акт списання ТМЦ"
            norm.contains("авансовий звіт") || norm.contains("звіт про використання коштів") -> "Авансовий звіт"
            norm.contains("бухгалтерська довідка") -> "Бухгалтерська довідка"
            norm.contains("прибутковий ордер") -> "Прибутковий ордер"

            norm.contains("видаткова накладна") || (norm.contains("видатк") && norm.contains("накладна")) -> "Видаткова накладна"
            norm.contains("податкова накладна") -> "Податкова накладна"
            norm.contains("товарно-транспортна") || norm.contains(" ттн ") -> "Товарно-транспортна накладна"
            norm.contains("рахунок-фактура") || norm.contains("рахунок фактура") || norm.contains("інвойс") || norm.contains("invoice") -> "Рахунок-фактура (Інвойс)"
            norm.contains("рахунок-акт") || norm.contains("рахунок акт") -> "Рахунок-акт"
            norm.contains("рахунок на оплат") -> "Рахунок на оплату"

            norm.contains("акт") && (norm.contains("здач") || norm.contains("прийм") || norm.contains("прийом")) -> "Акт здачі-прийняття робіт"
            norm.contains("акт") && norm.contains("викон") -> "Акт виконаних робіт"
            norm.contains("акт") && norm.contains("послуг") -> "Акт надання послуг"
            norm.contains("специфікаці") -> "Специфікація"

            norm.contains("додаткова угода") -> "Додаткова угода"
            norm.contains("договір") && !norm.contains("по договору") -> "Договір"

            norm.contains("квитанці") -> "Квитанція"
            norm.contains("чек") -> "Чек"
            norm.contains("рахунок") -> "Рахунок"
            norm.contains("акт") -> "Акт"
            norm.contains("накладна") -> "Накладна"
            else -> "Документ"
        }
    }

    private fun extractNumber(lines: List<String>, fullText: String, isContractDoc: Boolean): String {
        val cleanLines = lines.filterNot { isNoiseLine(it, isContractDoc) }

        // 1. Пряма прив'язка до заголовка (АКТ № ..., ДОГОВІР № ..., НАКАЗ № ...)
        val titleAttachedPattern = Regex(
            """(?iu)(?:акт|рахунок|накладна|договір|наказ|ордер|інструкція|виписка|звіт)[^\n\d]{0,25}(?:№|n[o0оО]?|номер|#)?\s*[:.]?\s*([а-яa-zіїєґ]{0,3}\s*[-/._]?\s*\d{1,10})"""
        )
        for (line in cleanLines) {
            val match = titleAttachedPattern.find(line)
            if (match != null) {
                val num = cleanNumberString(match.groupValues[1], fullText)
                if (num.isNotEmpty() && num != "б-н") return num
            }
        }

        // 2. Номер із префіксом (наприклад, ЛТ-0000272, ВКО-12, ПКО-5)
        val prefixRegex = Regex("""(?iu)([а-яА-Яa-zA-Z]{1,4})\s*[-_]\s*([0-9]{1,10})""")
        for (line in cleanLines) {
            val match = prefixRegex.find(line)
            if (match != null) {
                var p = match.groupValues[1].uppercase().replace('T', 'Т')
                val n = match.groupValues[2]
                if ((p == "Т" || p == "T") && (fullText.contains("ЛІТЛАН", true) || fullText.contains("ЛТ", true))) {
                    p = "ЛТ"
                }
                return "$p-$n"
            }
        }

        // 3. Стандартний рядок зі знаком №
        val numPattern = Regex(
            """(?iu)(?:№|N[oOоОeEеЕ2]?|номер|#)\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-/._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*)"""
        )
        for (line in cleanLines) {
            val match = numPattern.find(line)
            if (match != null) {
                val cand = cleanNumberString(match.groupValues[1], fullText)
                if (cand.any { it.isDigit() }) return cand
            }
        }

        // 4. Слово перед "від"
        for (line in cleanLines) {
            val vidMatch = Regex("""(?iu)(.*)\s+[вВ][іІiI1][дД]\s+""").find(line)
            if (vidMatch != null) {
                val words = vidMatch.groupValues[1].trim().split("""\s+""".toRegex())
                val lastWord = words.lastOrNull()?.trimEnd('.', ',', ';', ':') ?: ""
                val cand = cleanNumberString(lastWord, fullText)
                if (cand.any { it.isDigit() }) return cand
            }
        }

        return "б-н"
    }

    private fun cleanNumberString(raw: String, fullText: String): String {
        var clean = raw.replace("""\s+""".toRegex(), "").trimEnd('.', ',', ';', ':').replace('/', '-').uppercase()
        clean = clean.replace(Regex("""^(?:№|N[oOоО]?|НОМЕР|#)[:.-]*"""), "")
        clean = clean.replace('T', 'Т')
        if ((clean.startsWith("Т-") || clean.startsWith("T-")) && (fullText.contains("ЛІТЛАН", true) || fullText.contains("ЛТ", true))) {
            clean = "Л" + clean
        }
        return clean
    }

    private fun extractDate(lines: List<String>, isContractDoc: Boolean): String {
        val cleanLines = lines.filterNot { isNoiseLine(it, isContractDoc) }

        // 1. Дата після "від" або "дата" у перших 15 рядках
        val explicitRegex = Regex(
            """(?iu)\b(?:від|дата)\s*[:.]?\s*([0-3]?[0-9]\s*[-–—./\s]?\s*[a-zA-Zа-яА-ЯіїєґІЇЄҐ']+\s*(?:20)?[2-3][0-9]|[0-3]?[0-9][./-][0-1]?[0-9][./-](?:20)?[2-3][0-9])"""
        )
        for (line in cleanLines.take(15)) {
            val match = explicitRegex.find(line)
            if (match != null) {
                val d = parseDateString(match.groupValues[1])
                if (d != "не_вказано") return d
            }
        }

        // 2. Наскрізний пошук текстової дати з українським місяцем по всьому тексту (для підписів знизу)
        val cleanBlock = cleanLines.joinToString(" ")
        val textMonthRegex = Regex(
            """(?iu)\b([0-3]?[0-9])\s*[-–—./\s]?\s*([a-zA-Zа-яА-ЯіїєґІЇЄҐ']+)\s*((?:20)?[2-3][0-9])\b"""
        )
        val textMatches = textMonthRegex.findAll(cleanBlock).toList()
        for (match in textMatches.reversed()) {
            val d = parseDateString(match.value)
            if (d != "не_вказано") return d
        }

        // 3. Числова дата ДД.ММ.РРРР наскрізно
        val numericRegex = Regex(
            """(?iu)\b([0-3]?[0-9])[./-]([0-1]?[0-9])[./-](20[2-3][0-9]|[2-3][0-9])\b"""
        )
        val numMatches = numericRegex.findAll(cleanBlock).toList()
        for (match in numMatches.reversed()) {
            val d = parseDateString(match.value)
            if (d != "не_вказано") return d
        }

        return "не_вказано"
    }

    private fun parseDateString(raw: String): String {
        val numMatch = Regex("""([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{2,4})""").find(raw)
        if (numMatch != null) {
            val d = numMatch.groupValues[1].toInt()
            val m = numMatch.groupValues[2].toInt()
            var y = numMatch.groupValues[3]
            if (y.length == 2) y = "20$y"
            if (d in 1..31 && m in 1..12) {
                return "${d.toString().padStart(2, '0')}.${m.toString().padStart(2, '0')}.$y"
            }
        }

        val textMatch = Regex("""(?iu)([0-9]{1,2})\s*[-–—./\s]?\s*([a-zA-Zа-яА-ЯіїєґІЇЄҐ']+)\s*((?:20)?[2-3][0-9])""").find(raw)
        if (textMatch != null) {
            val d = textMatch.groupValues[1].toInt()
            val monthWord = normalize(textMatch.groupValues[2])
            var y = textMatch.groupValues[3]
            if (y.length == 2) y = "20$y"

            val m = MONTH_MAP.entries.firstOrNull { monthWord.contains(it.key) }?.value
            if (m != null && d in 1..31) {
                return "${d.toString().padStart(2, '0')}.$m.$y"
            }
        }

        return "не_вказано"
    }
}
