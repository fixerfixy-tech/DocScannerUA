package com.fixerfixy.docscanner

object DocumentParser {

    // Корені та типові фрагменти українських місяців (стійкі до пошкодження печаткою)
    private val MONTH_PATTERNS = listOf(
        listOf("січн", "січ") to "01",
        listOf("лютог", "лют") to "02",
        listOf("берез", "ерезн") to "03",
        listOf("квітн", "квіт") to "04",
        listOf("травн", "трав") to "05",
        listOf("червн", "черв") to "06",
        listOf("липн", "лип") to "07",
        listOf("серпн", "серп") to "08",
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

        val type = extractType(rawText)
        val isContractDoc = type == "Договір" || type == "Додаткова угода"
        val number = extractNumber(lines, rawText, isContractDoc)
        val date = extractDate(lines, rawText, type, isContractDoc)

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
               norm.contains("дійсний до") || norm.contains("до сплати до") ||
               norm.contains("склали цей акт") || norm.contains("претензій не мають") ||
               norm.contains("представник")
    }

    private fun extractType(fullText: String): String {
        val norm = normalize(fullText)

        return when {
            norm.contains("прибутковий касовий ордер") || norm.contains("пко ") -> "Прибутковий касовий ордер"
            norm.contains("видатковий касовий ордер") || norm.contains("вко ") -> "Видатковий касовий ордер"
            norm.contains("журнал реєстрації") && norm.contains("касов") -> "Журнал реєстрації касових документів"
            norm.contains("касова книга") -> "Касова книга"
            norm.contains("платіжна інструкція") -> "Платіжна інструкція"
            norm.contains("платіжне доручення") -> "Платіжне доручення"
            norm.contains("виписка банку") || norm.contains("банківська виписка") || norm.contains("виписка по рахунку") -> "Банківська виписка"

            norm.contains("табель обліку") && norm.contains("робочого часу") -> "Табель обліку робочого часу"
            norm.contains("розрахунково-платіжна відомість") || norm.contains("розрахункова відомість") -> "Розрахунково-платіжна відомість"
            norm.contains("наказ") && norm.contains("прийняття") -> "Наказ про прийняття на роботу"
            norm.contains("наказ") && norm.contains("звільнення") -> "Наказ про звільнення"
            norm.contains("наказ") && norm.contains("відпустк") -> "Наказ про надання відпустки"
            norm.contains("наказ") || norm.contains("розпорядження") -> "Наказ"

            norm.contains("накладна-вимога") || (norm.contains("накладна") && norm.contains("внутрішнього переміщення")) -> "Накладна-вимога на переміщення"
            norm.contains("акт") && norm.contains("основних засобів") -> "Акт приймання-передачі ОЗ"
            norm.contains("акт") && norm.contains("списання") -> "Акт списання ТМЦ"
            norm.contains("авансовий звіт") || norm.contains("звіт про використання коштів") -> "Авансовий звіт"
            norm.contains("бухгалтерська довідка") -> "Бухгалтерська довідка"
            norm.contains("прибутковий ордер") -> "Прибутковий ордер"

            norm.contains("видаткова накладна") || (norm.contains("видатк") && norm.contains("накладна")) -> "Видаткова накладна"
            norm.contains("податкова накладна") -> "Податкова накладна"
            norm.contains("товарно-транспортна") || norm.contains(" ттн ") -> "Товарно-транспортна накладна"
            norm.contains("рахунок-фактура") || norm.contains("рахунок фактура") -> "Рахунок-фактура"
            norm.contains("інвойс") || norm.contains("invoice") -> "Рахунок-фактура (Інвойс)"
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

        // 1. Пріоритет: Номер з буквеним префіксом та дефісом (наприклад: ЛТ-0000272, ЛТ-0000176)
        val prefixRegex = Regex("""(?iu)([а-яa-zіїєґ]{1,4})\s*[-–—_]\s*(0*\d{3,10})""")
        for (line in cleanLines) {
            val match = prefixRegex.find(line)
            if (match != null) {
                val rawPrefix = match.groupValues[1]
                val digits = match.groupValues[2]
                return finalizeNumber("$rawPrefix-$digits", fullText)
            }
        }

        // 2. Номер після знака № або No (без жадібного поглинання префікса!)
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

        // 3. Номер безпосередньо після слова АКТ / РАХУНОК / НАКЛАДНА
        val afterTitleRegex = Regex(
            """(?iu)(?:акт|рахунок(?:-фактура)?|накладна)\s+(?:№|n[o0оО]?|номер|#)?\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-–—/._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*)"""
        )
        for (line in cleanLines) {
            val match = afterTitleRegex.find(line)
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

        // 5. Для табличних рахунків (як ЦЕК), де номер стоїть окремим рядком у шапці
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
            // Якщо префікс "ЛТ-" частково або повністю з'ївся знаком № (наприклад "Т-0000272" або "0000272")
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

    private fun extractDate(lines: List<String>, fullText: String, docType: String, isContractDoc: Boolean): String {
        val cleanLines = lines.filterNot { isNoiseLine(it, isContractDoc) }

        // Визначаємо базовий рік документа (наприклад, 2026), якщо на печатці рік пошкоджено
        val defaultYear = Regex("""\b(202[4-9])\b""").find(cleanLines.joinToString(" "))?.groupValues?.get(1) ?: "2026"

        // 1. Дата після "від" або "дата" у шапці документа
        val explicitRegex = Regex(
            """(?iu)(?:від|дата)\s*[:.]?\s*([0-3Зз]?[0-9ОоO]\s*[-–—./\s]{1,3}\s*[a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12}\s*[-–—./\s]{0,3}\s*(?:20)?[2-3][0-9ОоOб]|[0-3Зз]?[0-9ОоO][./-][0-1]?[0-9ОоO][./-](?:20)?[2-3][0-9ОоOб])"""
        )
        for (line in cleanLines.take(18)) {
            val match = explicitRegex.find(line)
            if (match != null) {
                val parsed = parseFlexibleDate(match.groupValues[1], defaultYear)
                if (parsed != null) return parsed
            }
        }

        // 2. Наскрізний пошук текстової дати (наприклад: "30-Вересня 2026", "30 Вересня 2026", навіть якщо між ними символи печатки)
        val cleanBlock = cleanLines.joinToString(" ")
        val textDateRegex = Regex(
            """(?iu)(?<!\d)([0-3Зз]?[0-9ОоO])[^a-zA-Zа-яА-Я0-9\n]{1,5}([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})[^a-zA-Zа-яА-Я0-9\n]{1,5}(20[0-3][0-9ОоOб]|[2-3][0-9ОоOб])(?!\d)"""
        )
        for (match in textDateRegex.findAll(cleanBlock).toList().reversed()) {
            val dayStr = fixOcrDigits(match.groupValues[1])
            val monthCode = matchMonth(match.groupValues[2])
            var yearStr = fixOcrDigits(match.groupValues[3])
            if (yearStr.length == 2) yearStr = "20$yearStr"
            if (yearStr == "2020" || yearStr == "2036") yearStr = defaultYear

            val day = dayStr.toIntOrNull() ?: 0
            if (monthCode != null && day in 1..31) {
                return "${day.toString().padStart(2, '0')}.$monthCode.$yearStr"
            }
        }

        // 3. Числова дата ДД.ММ.РРРР у чистих рядках
        val numericRegex = Regex(
            """(?<!\d)([0-3]?[0-9])[./-]([0-1]?[0-9])[./-](20[2-3][0-9]|[2-3][0-9])(?!\d)"""
        )
        for (match in numericRegex.findAll(cleanBlock).toList().reversed()) {
            val d = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            var y = match.groupValues[3]
            if (y.length == 2) y = "20$y"
            if (d in 1..31 && m in 1..12) {
                return "${d.toString().padStart(2, '0')}.${m.toString().padStart(2, '0')}.$y"
            }
        }

        // 4. Резервний алгоритм для Актів: якщо печатки в підвалі закрили слово "Вересня",
        // беремо місяць і рік із рядка послуг (напр. "вересень 2026 року") та шукаємо число дня в підвалі (або беремо останній день місяця)
        val monthYearRegex = Regex("""(?iu)\b([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s+(202[4-9])\s*(?:року|р\b)""")
        for (match in monthYearRegex.findAll(cleanBlock)) {
            val monthCode = matchMonth(match.groupValues[1])
            val yearStr = match.groupValues[2]
            if (monthCode != null) {
                // Перевіряємо, чи є в останніх 18 рядках (підвалі) число 28..31 перед роком
                val footerText = cleanLines.takeLast(18).joinToString(" ")
                val footerDayMatch = Regex("""\b(3[01]|2[89]|[0-2][0-9])\b""").findAll(footerText)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .firstOrNull { it in 25..31 }

                val day = footerDayMatch?.toString()?.padStart(2, '0')
                    ?: LAST_DAY_OF_MONTH[monthCode]
                    ?: "30"
                return "$day.$monthCode.$yearStr"
            }
        }

        return "не_вказано"
    }

    private fun parseFlexibleDate(raw: String, defaultYear: String): String? {
        val fixed = fixOcrDigits(raw)
        val numMatch = Regex("""([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{2,4})""").find(fixed)
        if (numMatch != null) {
            val d = numMatch.groupValues[1].toIntOrNull() ?: 0
            val m = numMatch.groupValues[2].toIntOrNull() ?: 0
            var y = numMatch.groupValues[3]
            if (y.length == 2) y = "20$y"
            if (d in 1..31 && m in 1..12) {
                return "${d.toString().padStart(2, '0')}.${m.toString().padStart(2, '0')}.$y"
            }
        }

        val words = raw.split(Regex("""[^a-zA-Zа-яА-ЯіїєґІЇЄҐ0-9']+""")).filter { it.isNotBlank() }
        if (words.size >= 3) {
            val d = fixOcrDigits(words[0]).toIntOrNull() ?: 0
            val m = matchMonth(words[1])
            var y = fixOcrDigits(words[2])
            if (y.length == 2) y = "20$y"
            if (y == "2020" || y == "2036") y = defaultYear
            if (d in 1..31 && m != null) {
                return "${d.toString().padStart(2, '0')}.$m.$y"
            }
        }
        return null
    }
}
