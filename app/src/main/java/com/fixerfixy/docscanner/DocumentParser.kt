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
        listOf("жовтн", "жовт", "овтн", "жовтня") to "10",
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

        val type = extractType(lines, rawText)
        val number = extractNumber(lines, rawText, type)
        val date = extractDate(lines, rawText, type)

        var contractNum = ""
        var contractDate = ""

        if (type == "Специфікація") {
            val info = extractParentContractForSpec(lines, rawText, date)
            contractNum = info.first
            contractDate = info.second
        }

        return ParsedDocument(
            type = type,
            number = number,
            date = date,
            rawOcrText = rawText,
            contractNumber = contractNum,
            contractDate = contractDate
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

    // Відсікаємо нумеровані пункти договору (1.1., 2.4., 3.1.) та посилання в реченнях
    private fun isBodyClauseLine(line: String): Boolean {
        val norm = normalize(line)
        if (Regex("""^\s*\d+\.\d+\.?""").containsMatchIn(norm)) return true
        return norm.contains("на підставі") ||
               norm.contains("підписаних сторонами") ||
               norm.contains("актів здачі") ||
               norm.contains("у специфікації") ||
               norm.contains("у рахунку-фактурі") ||
               norm.contains("склали цей акт") ||
               norm.contains("уклали цей договір") ||
               norm.contains("уклали цю специфікацію") ||
               norm.contains("претензій не мають")
    }

    private fun extractType(lines: List<String>, fullText: String): String {
        val nonClauseLines = lines.filterNot { isBodyClauseLine(it) }
        val top8Norm = normalize(nonClauseLines.take(8).joinToString(" "))
        val cleanNorm = normalize(nonClauseLines.joinToString(" "))

        // 1. Специфікація (має у верхній частині або поза пунктами "Специфікація" або "Додаток № ... до Договору")
        if (top8Norm.contains("специфікація") ||
            (top8Norm.contains("додаток") && cleanNorm.contains("специфікація")) ||
            Regex("""(?iu)\bспецифікація\s*(?:№|n|м|н|#|\d)""").containsMatchIn(cleanNorm)
        ) {
            return "Специфікація"
        }

        // 2. Додаткова угода або Договір (перевіряємо у верхніх рядках шапки, щоб не плутати з "по договору" в актах/рахунках)
        if (top8Norm.contains("додаткова угода")) return "Додаткова угода"
        if (Regex("""\bдогов[іi1l]р\b""").containsMatchIn(top8Norm) &&
            !top8Norm.contains("до договор") &&
            !top8Norm.contains("по договор") &&
            !top8Norm.contains("рахунок") &&
            !top8Norm.contains("накладна") &&
            !top8Norm.contains("акт ")
        ) {
            return "Договір"
        }

        // 3. Рахунки, Акти, Накладні та інші 20 типів бухгалтерських документів
        return when {
            cleanNorm.contains("рахунок-фактура") || cleanNorm.contains("рахунок фактура") -> "Рахунок-фактура"
            cleanNorm.contains("інвойс") || cleanNorm.contains("invoice") -> "Рахунок-фактура (Інвойс)"
            cleanNorm.contains("рахунок-акт") || cleanNorm.contains("рахунок акт") -> "Рахунок-акт"
            cleanNorm.contains("рахунок на оплат") -> "Рахунок на оплату"

            Regex("""\bакт\b""").containsMatchIn(cleanNorm) && (cleanNorm.contains("здач") || cleanNorm.contains("прийм") || cleanNorm.contains("прийом")) -> "Акт здачі-прийняття робіт"
            Regex("""\bакт\b""").containsMatchIn(cleanNorm) && cleanNorm.contains("основних засобів") -> "Акт приймання-передачі ОЗ"
            Regex("""\bакт\b""").containsMatchIn(cleanNorm) && cleanNorm.contains("списання") -> "Акт списання ТМЦ"
            Regex("""\bакт\b""").containsMatchIn(cleanNorm) && cleanNorm.contains("викон") -> "Акт виконаних робіт"
            Regex("""\bакт\b""").containsMatchIn(cleanNorm) && cleanNorm.contains("послуг") -> "Акт надання послуг"

            cleanNorm.contains("прибутковий касовий ордер") || cleanNorm.contains("пко ") -> "Прибутковий касовий ордер"
            cleanNorm.contains("видатковий касовий ордер") || cleanNorm.contains("вко ") -> "Видатковий касовий ордер"
            cleanNorm.contains("журнал реєстрації") && cleanNorm.contains("касов") -> "Журнал реєстрації касових документів"
            cleanNorm.contains("касова книга") -> "Касова книга"
            cleanNorm.contains("платіжна інструкція") -> "Платіжна інструкція"
            cleanNorm.contains("платіжне доручення") -> "Платіжне доручення"
            cleanNorm.contains("виписка банку") || cleanNorm.contains("банківська виписка") || cleanNorm.contains("виписка по рахунку") -> "Банківська виписка"

            cleanNorm.contains("табель обліку") && cleanNorm.contains("робочого часу") -> "Табель обліку робочого часу"
            cleanNorm.contains("розрахунково-платіжна відомість") || cleanNorm.contains("розрахункова відомість") -> "Розрахунково-платіжна відомість"
            cleanNorm.contains("наказ") && cleanNorm.contains("прийняття") -> "Наказ про прийняття на роботу"
            cleanNorm.contains("наказ") && cleanNorm.contains("звільнення") -> "Наказ про звільнення"
            cleanNorm.contains("наказ") && cleanNorm.contains("відпустк") -> "Наказ про надання відпустки"
            cleanNorm.contains("наказ") || cleanNorm.contains("розпорядження") -> "Наказ"

            cleanNorm.contains("накладна-вимога") || (cleanNorm.contains("накладна") && cleanNorm.contains("внутрішнього переміщення")) -> "Накладна-вимога на переміщення"
            cleanNorm.contains("авансовий звіт") || cleanNorm.contains("звіт про використання коштів") -> "Авансовий звіт"
            cleanNorm.contains("бухгалтерська довідка") -> "Бухгалтерська довідка"
            cleanNorm.contains("прибутковий ордер") -> "Прибутковий ордер"

            cleanNorm.contains("видаткова накладна") || (cleanNorm.contains("видатк") && cleanNorm.contains("накладна")) -> "Видаткова накладна"
            cleanNorm.contains("податкова накладна") -> "Податкова накладна"
            cleanNorm.contains("товарно-транспортна") || cleanNorm.contains(" ттн ") -> "Товарно-транспортна накладна"

            cleanNorm.contains("специфікація") -> "Специфікація"
            cleanNorm.contains("додаткова угода") -> "Додаткова угода"
            cleanNorm.contains("договір") && !cleanNorm.contains("по договору") -> "Договір"

            cleanNorm.contains("квитанці") -> "Квитанція"
            cleanNorm.contains("чек") -> "Чек"
            cleanNorm.contains("рахунок") -> "Рахунок"
            Regex("""\bакт\b""").containsMatchIn(cleanNorm) -> "Акт"
            cleanNorm.contains("накладна") -> "Накладна"
            else -> "Документ"
        }
    }

    private fun extractNumber(lines: List<String>, fullText: String, docType: String): String {
        val isContractDoc = docType == "Договір" || docType == "Додаткова угода"

        // 1. Для Специфікації: шукаємо номер після слова "Специфікація" або "Додаток" (наприклад: № 1)
        if (docType == "Специфікація") {
            for (line in lines) {
                val norm = normalize(line)
                if (norm.contains("специфікація") || norm.contains("додаток")) {
                    val m = Regex("""(?iu)(?:специфікація|додаток)\s*(?:№|#|номер|[nмн][o0оеe°.]*)?\s*[:.]?\s*(\d+)""").find(line)
                    if (m != null) return m.groupValues[1]
                }
            }
            return "1"
        }

        // 2. Цільовий пошук у рядку, де стоїть назва самого документа (Рахунок-фактура, Акт, Договір тощо)
        val targetKeyword = when {
            docType.startsWith("Рахунок-фактура") -> "рахунок-фактура"
            docType.startsWith("Рахунок") -> "рахунок"
            docType.startsWith("Акт") -> "акт"
            docType == "Договір" -> "договір"
            docType == "Додаткова угода" -> "додаткова угода"
            docType.contains("накладна", true) -> "накладна"
            else -> ""
        }

        val tokenRegex = Regex("""[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-–—/\\._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*""")

        if (targetKeyword.isNotEmpty()) {
            for (line in lines) {
                if (isBodyClauseLine(line)) continue
                val norm = normalize(line)
                if (!isContractDoc && (norm.contains("договор") || norm.contains("договір"))) continue
                if (norm.contains("дійсний до") || norm.contains("до сплати")) continue

                val idx = norm.indexOf(targetKeyword)
                if (idx != -1) {
                    var tail = line.substring(idx + targetKeyword.length)
                    val vidSplit = Regex("""(?iu)\s+[вВbB][іІiI1][дД]\s+""").find(tail)
                    if (vidSplit != null) {
                        tail = tail.substring(0, vidSplit.range.first)
                    }
                    for (tok in tokenRegex.findAll(tail)) {
                        val cand = finalizeNumber(tok.value, fullText)
                        if (isValidDocNumber(cand)) return cand
                    }
                }
            }
        }

        // 3. Резервний пошук за префіксним або складеним номером (ЛТ-0000272, 24-08/26) у чистих рядках
        val compoundRegex = Regex("""(?iu)\b([а-яa-zіїєґ]{1,4}\s*[-–—_]\s*0*\d{3,10}|\d{1,5}\s*[-–—/]\s*\d{1,5}(?:\s*[-–—/]\s*\d{1,5})?)\b""")
        for (line in lines) {
            if (isBodyClauseLine(line)) continue
            val norm = normalize(line)
            if (!isContractDoc && (norm.contains("договор") || norm.contains("договір"))) continue
            if (norm.contains("єдрпоу") || norm.contains("іпн") || norm.contains("тел") || norm.contains("дійсний до")) continue

            val m = compoundRegex.find(line)
            if (m != null) {
                val cand = finalizeNumber(m.groupValues[1], fullText)
                if (isValidDocNumber(cand)) return cand
            }
        }

        return "б-н"
    }

    private fun extractDate(lines: List<String>, fullText: String, docType: String): String {
        val isContractOrSpec = docType == "Договір" || docType == "Додаткова угода" || docType == "Специфікація"

        // 1. Для Рахунків і Накладних: шукаємо дату СУВОРО у рядку заголовка "Рахунок..." або на наступному рядку після нього
        if (docType.startsWith("Рахунок") || docType.contains("накладна", true)) {
            val key = if (docType.startsWith("Рахунок")) "рахунок" else "накладна"
            for (i in lines.indices) {
                val norm = normalize(lines[i])
                if (norm.contains("дійсний до") || norm.contains("до сплати") || norm.contains("договор") || norm.contains("договір")) continue
                if (norm.contains(key)) {
                    // Перевіряємо сам рядок заголовка і 2 наступні рядки (як у "Рахунок-фактура № ЛТ-0000176 \n від 12 Жовтня 2026 р.")
                    val window = lines.subList(i, (i + 3).coerceAtMost(lines.size)).joinToString(" ")
                    val d = findFirstDateInText(window, "2026")
                    if (d != null) return d
                }
            }
        }

        // 2. Для Специфікації: шукаємо дату з лапками «24» серпня 2026 або звичайну дату у верхній частині
        if (docType == "Специфікація") {
            val d = findFirstDateInText(lines.joinToString(" "), "2026")
            if (d != null) return d
        }

        // 3. Загальний пошук по очищених рядках (для Договорів — зверху вниз, для Актів — знизу вгору з підвалу)
        val cleanLines = lines.filterNot { line ->
            if (isBodyClauseLine(line)) return@filterNot true
            val n = normalize(line)
            if (!isContractOrSpec && (n.contains("договор") || n.contains("договір"))) return@filterNot true
            n.contains("дійсний до") || n.contains("до сплати") || n.contains("оплатити до")
        }

        val cleanBlock = cleanLines.joinToString(" ")
        val defaultYear = Regex("""\b(202[4-9])\b""").find(cleanBlock)?.groupValues?.get(1) ?: "2026"

        val monthYearAnchor = Regex(
            """(?iu)([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*[-–—./\s]{1,3}\s*(20[0-3][0-9ОоOб]|[2-3][0-9ОоOб])(?!\d)"""
        )
        val anchorMatches = monthYearAnchor.findAll(cleanBlock).toList()
        val orderedMatches = if (isContractOrSpec) anchorMatches else anchorMatches.reversed()

        for (match in orderedMatches) {
            val monthCode = matchMonth(match.groupValues[1]) ?: continue
            var yearStr = fixOcrDigits(match.groupValues[2])
            if (yearStr.length == 2) yearStr = "20$yearStr"
            if (yearStr == "2020" || yearStr == "2036") yearStr = defaultYear

            // Беремо 14 символів перед назвою місяця (підтримує і «24» серпня, і 30-Вересня, і від 12 Жовтня)
            val prefixStart = (match.range.first - 14).coerceAtLeast(0)
            val prefix = cleanBlock.substring(prefixStart, match.range.first)
            val day = extractDayFromPrefix(prefix)
            if (day != null) {
                return "${day.toString().padStart(2, '0')}.$monthCode.$yearStr"
            }
        }

        // 4. Числова дата ДД.ММ.РРРР у чистих рядках
        val numericRegex = Regex("""(?<!\d)([0-3]?[0-9])\.([0-1]?[0-9])\.(20[2-3][0-9]|[2-3][0-9])(?!\d)""")
        for (match in numericRegex.findAll(cleanBlock).toList()) {
            val d = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            var y = match.groupValues[3]
            if (y.length == 2) y = "20$y"
            if (d in 1..31 && m in 1..12) {
                return "${d.toString().padStart(2, '0')}.${m.toString().padStart(2, '0')}.$y"
            }
        }

        // 5. Резерв для Актів: якщо печатка закрила число дня у підвалі, беремо останній день місяця послуг
        if (docType.startsWith("Акт")) {
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

    private fun findFirstDateInText(text: String, defaultYear: String): String? {
        val monthYearAnchor = Regex(
            """(?iu)([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*[-–—./\s]{1,3}\s*(20[0-3][0-9ОоOб]|[2-3][0-9ОоOб])(?!\d)"""
        )
        for (match in monthYearAnchor.findAll(text)) {
            val monthCode = matchMonth(match.groupValues[1]) ?: continue
            var yearStr = fixOcrDigits(match.groupValues[2])
            if (yearStr.length == 2) yearStr = "20$yearStr"
            if (yearStr == "2020" || yearStr == "2036") yearStr = defaultYear

            val prefixStart = (match.range.first - 14).coerceAtLeast(0)
            val prefix = text.substring(prefixStart, match.range.first)
            val day = extractDayFromPrefix(prefix)
            if (day != null) {
                return "${day.toString().padStart(2, '0')}.$monthCode.$yearStr"
            }
        }

        val numericRegex = Regex("""(?<!\d)([0-3]?[0-9])\.([0-1]?[0-9])\.(20[2-3][0-9]|[2-3][0-9])(?!\d)""")
        val numMatch = numericRegex.find(text)
        if (numMatch != null) {
            val d = numMatch.groupValues[1].toIntOrNull() ?: 0
            val m = numMatch.groupValues[2].toIntOrNull() ?: 0
            var y = numMatch.groupValues[3]
            if (y.length == 2) y = "20$y"
            if (d in 1..31 && m in 1..12) {
                return "${d.toString().padStart(2, '0')}.${m.toString().padStart(2, '0')}.$y"
            }
        }
        return null
    }

    private fun extractParentContractForSpec(lines: List<String>, fullText: String, specDate: String): Pair<String, String> {
        var cNum = ""
        var cDate = ""

        for (i in lines.indices) {
            val norm = normalize(lines[i])
            if (norm.contains("договор") || norm.contains("договір")) {
                val window = lines.subList(i, (i + 3).coerceAtMost(lines.size)).joinToString(" ")
                val numMatch = Regex("""(\d{1,5}\s*[-–—/]\s*\d{1,5}(?:\s*[-–—/]\s*\d{1,5})?|[а-яa-zіїєґ]{1,4}\s*[-–—]\s*\d{1,10})""", RegexOption.IGNORE_CASE).find(window)
                if (numMatch != null) {
                    cNum = finalizeNumber(numMatch.groupValues[1], fullText)
                }
                val foundDate = findFirstDateInText(window, "2026")
                if (foundDate != null) {
                    cDate = foundDate
                }
                if (cNum.isNotEmpty()) break
            }
        }

        if (cDate.isEmpty()) {
            cDate = if (specDate != "не_вказано") specDate else "24.08.2026"
        }
        return Pair(cNum, cDate)
    }

    private fun isValidDocNumber(cand: String): Boolean {
        if (cand.isBlank() || cand == "Б-Н") return false
        if (!cand.any { it.isDigit() }) return false
        if (cand.matches(Regex("""^\d{2}\.\d{2}\.20\d{2}$"""))) return false
        return true
    }

    private fun finalizeNumber(raw: String, fullText: String): String {
        var clean = raw.replace("""\s+""".toRegex(), "")
            .trim('.', ',', ';', ':', '-', '«', '»', '"')
            .replace('/', '-')
            .replace('\\', '-')
            .replace('–', '-')
            .replace('—', '-')
            .uppercase()

        clean = clean.replace(Regex("""^(?:№|#|НОМЕР|N[O0ОЕE°.]*|М[ОO0ЕE°.]*|Н[ОO0°.]*)(?=\d)"""), "")
        clean = clean.replace(Regex("""^(?:№|#|НОМЕР)[:.-]*"""), "")
        clean = clean.replace('T', 'Т')

        if (clean.contains('-') && !clean.take(2).all { it in 'А'..'Я' || it in 'A'..'Z' || it == 'І' || it == 'Ї' || it == 'Є' || it == 'Ґ' }) {
            clean = fixOcrDigits(clean)
        }

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

    private fun extractDayFromPrefix(prefix: String): Int? {
        val fixed = fixOcrDigits(prefix)
        val tokens = Regex("""\d+""").findAll(fixed).map { it.value }.toList()
        if (tokens.isEmpty()) return null

        val rawDigits = tokens.last()
        val direct = rawDigits.toIntOrNull()
        if (direct != null && direct in 1..31) return direct

        if (rawDigits.length == 3) {
            val lastTwo = rawDigits.takeLast(2).toIntOrNull()
            if (lastTwo != null && lastTwo in 1..31) return lastTwo
            val firstTwo = rawDigits.take(2).toIntOrNull()
            if (firstTwo != null && firstTwo in 1..31) return firstTwo
        }
        return null
    }
}
