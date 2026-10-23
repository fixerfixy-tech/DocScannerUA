package com.fixerfixy.docscanner

object DocumentParser {

    private val MONTH_PATTERNS = listOf(
        listOf("січн", "січ") to "01",
        listOf("лютог", "лют") to "02",
        listOf("берез", "ерезн") to "03",
        listOf("квітн", "квіт") to "04",
        listOf("травн", "трав") to "05",
        listOf("червн", "черв") to "06",
        listOf("липн", "лип") to "07",
        listOf("серпн", "серп", "српня", "ерпня") to "08",
        listOf("верес", "ересн", "ресня", "всрес") to "09",
        listOf("жовтн", "жовт", "овтн") to "10",
        listOf("листоп", "истоп", "лист") to "11",
        listOf("грудн", "груд") to "12"
    )

    private val LAST_DAY_OF_MONTH = mapOf(
        "01" to "31", "02" to "28", "03" to "31", "04" to "30",
        "05" to "31", "06" to "30", "07" to "31", "08" to "31",
        "09" to "30", "10" to "31", "11" to "30", "12" to "31"
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

    // Відсікаємо рядки тіла документа та посилання на супутні документи
    private fun isBodyOrReferenceLine(line: String): Boolean {
        val norm = normalize(line)
        // Пункти договору на кшталт "1.1.", "2.4.", "3.1."
        if (Regex("""^\s*\d+\.\d+\.?""").containsMatchIn(norm)) return true
        return norm.contains("на підставі") ||
               norm.contains("підписаних сторонами") ||
               norm.contains("актів здачі") ||
               norm.contains("у специфікації") ||
               norm.contains("у рахунку-фактурі") ||
               norm.contains("додаток №") || norm.contains("додаток n") ||
               norm.contains("склали цей акт") ||
               norm.contains("уклали цей договір") ||
               norm.contains("претензій не мають") ||
               norm.contains("представник")
    }

    private fun isNoiseLine(line: String, isContractDoc: Boolean): Boolean {
        if (isBodyOrReferenceLine(line)) return true
        val norm = normalize(line)
        if (!isContractDoc) {
            if (norm.contains("договор") || norm.contains("договір")) return true
        }
        return norm.contains("замовлен") || norm.contains("підстав") ||
               norm.contains("розр. док") || norm.contains("розр.док") ||
               norm.contains("дійсний до") || norm.contains("до сплати до") ||
               norm.contains("специфікації №")
    }

    private fun extractType(lines: List<String>): String {
        // Для визначення типу беремо лише чисті рядки шапки (перші 22 рядки без пунктів 1.1, 2.4 тощо)
        val headerLines = lines.take(25).filterNot { isBodyOrReferenceLine(it) }
        val top5Norm = normalize(headerLines.take(6).joinToString(" "))
        val headerNorm = normalize(headerLines.joinToString(" "))

        // Якщо в перших рядках шапки явно стоїть заголовок "ДОГОВІР №..." або "ДОДАТКОВА УГОДА"
        if (top5Norm.contains("додаткова угода")) return "Додаткова угода"
        if (Regex("""\bдоговір\b""").containsMatchIn(top5Norm) &&
            !top5Norm.contains("рахунок") &&
            !top5Norm.contains("накладна") &&
            !top5Norm.contains("акт ") &&
            !top5Norm.contains("замовлення")
        ) {
            return "Договір"
        }

        return when {
            headerNorm.contains("прибутковий касовий ордер") || headerNorm.contains("пко ") -> "Прибутковий касовий ордер"
            headerNorm.contains("видатковий касовий ордер") || headerNorm.contains("вко ") -> "Видатковий касовий ордер"
            headerNorm.contains("журнал реєстрації") && headerNorm.contains("касов") -> "Журнал реєстрації касових документів"
            headerNorm.contains("касова книга") -> "Касова книга"
            headerNorm.contains("платіжна інструкція") -> "Платіжна інструкція"
            headerNorm.contains("платіжне доручення") -> "Платіжне доручення"
            headerNorm.contains("виписка банку") || headerNorm.contains("банківська виписка") || headerNorm.contains("виписка по рахунку") -> "Банківська виписка"

            headerNorm.contains("табель обліку") && headerNorm.contains("робочого часу") -> "Табель обліку робочого часу"
            headerNorm.contains("розрахунково-платіжна відомість") || headerNorm.contains("розрахункова відомість") -> "Розрахунково-платіжна відомість"
            headerNorm.contains("наказ") && headerNorm.contains("прийняття") -> "Наказ про прийняття на роботу"
            headerNorm.contains("наказ") && headerNorm.contains("звільнення") -> "Наказ про звільнення"
            headerNorm.contains("наказ") && headerNorm.contains("відпустк") -> "Наказ про надання відпустки"
            headerNorm.contains("наказ") || headerNorm.contains("розпорядження") -> "Наказ"

            headerNorm.contains("накладна-вимога") || (headerNorm.contains("накладна") && headerNorm.contains("внутрішнього переміщення")) -> "Накладна-вимога на переміщення"
            headerNorm.contains("акт") && headerNorm.contains("основних засобів") -> "Акт приймання-передачі ОЗ"
            headerNorm.contains("акт") && headerNorm.contains("списання") -> "Акт списання ТМЦ"
            headerNorm.contains("авансовий звіт") || headerNorm.contains("звіт про використання коштів") -> "Авансовий звіт"
            headerNorm.contains("бухгалтерська довідка") -> "Бухгалтерська довідка"
            headerNorm.contains("прибутковий ордер") -> "Прибутковий ордер"

            headerNorm.contains("видаткова накладна") || (headerNorm.contains("видатк") && headerNorm.contains("накладна")) -> "Видаткова накладна"
            headerNorm.contains("податкова накладна") -> "Податкова накладна"
            headerNorm.contains("товарно-транспортна") || headerNorm.contains(" ттн ") -> "Товарно-транспортна накладна"
            headerNorm.contains("рахунок-фактура") || headerNorm.contains("рахунок фактура") -> "Рахунок-фактура"
            headerNorm.contains("інвойс") || headerNorm.contains("invoice") -> "Рахунок-фактура (Інвойс)"
            headerNorm.contains("рахунок-акт") || headerNorm.contains("рахунок акт") -> "Рахунок-акт"
            headerNorm.contains("рахунок на оплат") -> "Рахунок на оплату"

            Regex("""\bакт\b""").containsMatchIn(headerNorm) && (headerNorm.contains("здач") || headerNorm.contains("прийм") || headerNorm.contains("прийом")) -> "Акт здачі-прийняття робіт"
            Regex("""\bакт\b""").containsMatchIn(headerNorm) && headerNorm.contains("викон") -> "Акт виконаних робіт"
            Regex("""\bакт\b""").containsMatchIn(headerNorm) && headerNorm.contains("послуг") -> "Акт надання послуг"
            headerNorm.contains("специфікація") -> "Специфікація"

            headerNorm.contains("додаткова угода") -> "Додаткова угода"
            headerNorm.contains("договір") && !headerNorm.contains("по договору") -> "Договір"

            headerNorm.contains("квитанці") -> "Квитанція"
            headerNorm.contains("чек") -> "Чек"
            headerNorm.contains("рахунок") -> "Рахунок"
            Regex("""\bакт\b""").containsMatchIn(headerNorm) -> "Акт"
            headerNorm.contains("накладна") -> "Накладна"
            else -> "Документ"
        }
    }

    private fun extractNumber(lines: List<String>, fullText: String, isContractDoc: Boolean): String {
        val cleanLines = lines.filterNot { isNoiseLine(it, isContractDoc) }

        // 1. Пріоритет: Номер безпосередньо в рядку заголовка (ДОГОВІР № 24-08/26, АКТ № ЛТ-0000272, Рахунок-фактура № ЛТ-0000176)
        val afterTitleRegex = Regex(
            """(?iu)(?:договір|додаткова\s+угода|акт|рахунок(?:-фактура)?|накладна|наказ|ордер|інструкція)\s*(?:№|n[o0оОeе]?|номер|#)?\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-–—/._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)+|[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]*\d[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]*)"""
        )
        for (line in cleanLines.take(15)) {
            val match = afterTitleRegex.find(line)
            if (match != null) {
                val cand = finalizeNumber(match.groupValues[1], fullText)
                if (cand.any { it.isDigit() }) return cand
            }
        }

        // 2. Номер з буквеним префіксом та дефісом (наприклад: ЛТ-0000272, ЛТ-0000176)
        val prefixRegex = Regex("""(?iu)([а-яa-zіїєґ]{1,4})\s*[-–—_]\s*(0*\d{3,10})""")
        for (line in cleanLines.take(18)) {
            val match = prefixRegex.find(line)
            if (match != null) {
                val rawPrefix = match.groupValues[1]
                val digits = match.groupValues[2]
                return finalizeNumber("$rawPrefix-$digits", fullText)
            }
        }

        // 3. Будь-який номер після знака № або No у чистих рядках
        val numSignRegex = Regex(
            """(?iu)(?:№|N[oOоО]?|номер|#)\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-–—/._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*)"""
        )
        for (line in cleanLines) {
            val match = numSignRegex.find(line)
            if (match != null) {
                val cand = finalizeNumber(match.groupValues[1], fullText)
                if (cand.any { it.isDigit() }) return cand
            }
        }

        // 4. Останнє слово перед словом "від"
        for (line in cleanLines) {
            val vidMatch = Regex("""(?iu)(.*)\s+[вВ][іІiI1][дД]\s+""").find(line)
            if (vidMatch != null) {
                val words = vidMatch.groupValues[1].trim().split("""\s+""".toRegex())
                val lastWord = words.lastOrNull()?.trimEnd('.', ',', ';', ':') ?: ""
                val cand = finalizeNumber(lastWord, fullText)
                if (cand.any { it.isDigit() }) return cand
            }
        }

        // 5. Окремий числовий номер у шапці (для табличних рахунків)
        for (line in cleanLines.take(12)) {
            if (line.contains("ЄДРПОУ", true) || line.contains("ІПН", true) || line.contains("код", true)) continue
            val standaloneNum = Regex("""^\s*(\d{6,10})\s*$""").find(line)
            if (standaloneNum != null) {
                return standaloneNum.groupValues[1]
            }
        }

        return "б-н"
    }

    private fun finalizeNumber(raw: String, fullText: String): String {
        var clean = raw.replace("""\s+""".toRegex(), "")
            .trimEnd('.', ',', ';', ':')
            .replace('/', '-')
            .replace('–', '-')
            .replace('—', '-')
            .uppercase()

        clean = clean.replace(Regex("""^(?:№|N[oOоО]?|НОМЕР|#)[:.-]*"""), "")
        clean = clean.replace('T', 'Т')

        val isLitlan = fullText.contains("ЛІТЛАН", true) || fullText.contains("ЛІГЛАН", true) || fullText.contains("ЛТ-", true)
        if (isLitlan) {
            if (clean.matches(Regex("""^[ТІП1]-?\d{5,9}$"""))) {
                val digits = clean.replace(Regex("""^[ТІП1]-?"""), "")
                clean = "ЛТ-$digits"
            } else if (clean.matches(Regex("""^00\d{4,7}$"""))) {
                clean = "ЛТ-$clean"
            }
        }
        return clean
    }

    private fun matchMonth(word: String): String? {
        val norm = normalize(word)
        for ((stems, code) in MONTH_PATTERNS) {
            if (stems.any { norm.contains(it) }) return code
        }
        return null
    }

    private fun fixOcrDigits(s: String): String {
        return s.replace('З', '3').replace('з', '3')
            .replace('О', '0').replace('о', '0').replace('O', '0')
            .replace('б', '6')
            .replace('І', '1').replace('і', '1').replace('l', '1').replace('I', '1')
    }

    private fun extractDate(lines: List<String>, isContractDoc: Boolean): String {
        val cleanLines = lines.filterNot { isNoiseLine(it, isContractDoc) }
        val cleanBlock = cleanLines.joinToString(" ")

        val defaultYear = Regex("""\b(202[4-9])\b""").find(cleanBlock)?.groupValues?.get(1) ?: "2026"

        // 1. Пошук текстової дати за якорем "[Місяць] [Рік]" (підтримує лапки «24» серпня 2026, дефіси 30-Вересня 2026 та звичайні дати)
        val monthYearAnchor = Regex(
            """(?iu)([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*[-–—./\s]{1,3}\s*(20[0-3][0-9ОоOб]|[2-3][0-9ОоOб])(?!\d)"""
        )

        val anchorMatches = monthYearAnchor.findAll(cleanBlock).toList()
        // Для договорів і рахунків дата стоїть на початку (шукаємо зверху вниз), для актів — у підвалі або шапці
        val orderedMatches = if (isContractDoc) anchorMatches else anchorMatches.reversed()

        for (match in orderedMatches) {
            val monthCode = matchMonth(match.groupValues[1]) ?: continue
            var yearStr = fixOcrDigits(match.groupValues[2])
            if (yearStr.length == 2) yearStr = "20$yearStr"
            if (yearStr == "2020" || yearStr == "2036") yearStr = defaultYear

            // Дивимось на 12 символів перед назвою місяця (там стоїть день, можливо в лапках «24» або з дефісом 30-)
            val prefixStart = (match.range.first - 12).coerceAtLeast(0)
            val prefix = cleanBlock.substring(prefixStart, match.range.first)
            val day = extractDayFromPrefix(prefix)
            if (day != null) {
                return "${day.toString().padStart(2, '0')}.$monthCode.$yearStr"
            }
        }

        // 2. Числова дата ДД.ММ.РРРР після слів "від" або "дата"
        val explicitNumRegex = Regex(
            """(?iu)(?:від|дата)\s*[:.]?\s*([0-3Зз]?[0-9ОоO])[./-]([0-1]?[0-9ОоO])[./-](20[2-3][0-9]|[2-3][0-9])"""
        )
        for (line in cleanLines) {
            val m = explicitNumRegex.find(line)
            if (m != null) {
                val d = fixOcrDigits(m.groupValues[1]).toIntOrNull() ?: 0
                val mo = fixOcrDigits(m.groupValues[2]).toIntOrNull() ?: 0
                var y = fixOcrDigits(m.groupValues[3])
                if (y.length == 2) y = "20$y"
                if (d in 1..31 && mo in 1..12) {
                    return "${d.toString().padStart(2, '0')}.${mo.toString().padStart(2, '0')}.$y"
                }
            }
        }

        // 3. Будь-яка числова дата ДД.ММ.РРРР у чистих рядках
        val numericRegex = Regex(
            """(?<!\d)([0-3]?[0-9])[./-]([0-1]?[0-9])[./-](20[2-3][0-9]|[2-3][0-9])(?!\d)"""
        )
        for (match in numericRegex.findAll(cleanBlock).toList()) {
            val d = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            var y = match.groupValues[3]
            if (y.length == 2) y = "20$y"
            if (d in 1..31 && m in 1..12) {
                return "${d.toString().padStart(2, '0')}.${m.toString().padStart(2, '0')}.$y"
            }
        }

        // 4. Резерв для Актів: якщо печатка повністю закрила день у підвалі, беремо місяць із рядка послуг ("вересень 2026 року")
        if (!isContractDoc) {
            for (match in anchorMatches) {
                val monthCode = matchMonth(match.groupValues[1]) ?: continue
                var yearStr = fixOcrDigits(match.groupValues[2])
                if (yearStr.length == 2) yearStr = "20$yearStr"
                val day = LAST_DAY_OF_MONTH[monthCode] ?: "30"
                return "$day.$monthCode.$yearStr"
            }
        }

        return "не_вказано"
    }

    // Витягує число дня (1..31) перед назвою місяця, очищаючи лапки «24», "24", дефіси 30- та OCR-артефакти лапок
    private fun extractDayFromPrefix(prefix: String): Int? {
        val fixed = fixOcrDigits(prefix)
        val tokens = Regex("""\d+""").findAll(fixed).map { it.value }.toList()
        if (tokens.isEmpty()) return null

        val rawDigits = tokens.last()
        val direct = rawDigits.toIntOrNull()
        if (direct != null && direct in 1..31) return direct

        // Якщо лапка « розпізналася як цифра перед днем (напр. «24 -> 124 або 424), беремо останні 2 цифри
        if (rawDigits.length == 3) {
            val lastTwo = rawDigits.takeLast(2).toIntOrNull()
            if (lastTwo != null && lastTwo in 1..31) return lastTwo
            val firstTwo = rawDigits.take(2).toIntOrNull()
            if (firstTwo != null && firstTwo in 1..31) return firstTwo
        }
        return null
    }
}
