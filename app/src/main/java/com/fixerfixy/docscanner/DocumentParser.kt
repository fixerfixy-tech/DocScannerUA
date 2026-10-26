package com.fixerfixy.docscanner

object DocumentParser {

    private val MONTH_PATTERNS = listOf(
        listOf("січн", "січ") to "01",
        listOf("лютог", "лют") to "02",
        listOf("берез", "ерезн", "бер") to "03",
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
            .map { preCleanLine(it) }
            .filter { it.isNotBlank() }

        val docType = detectType(lines)
        val anchorIdx = findTitleAnchorIndex(lines, docType)
        val docNumber = extractNumberByScope(lines, rawText, docType, anchorIdx)
        val docDate = extractDateByScope(lines, rawText, docType, anchorIdx, docNumber)

        var parentContractNum = ""
        var parentContractDate = ""
        if (docType == "Специфікація" || docType == "Додаткова угода") {
            val parentInfo = extractParentContractForSpec(lines, rawText, docDate)
            parentContractNum = parentInfo.first
            parentContractDate = parentInfo.second
        }

        return ParsedDocument(
            type = docType,
            number = docNumber,
            date = docDate,
            rawOcrText = rawText,
            contractNumber = parentContractNum,
            contractDate = parentContractDate
        )
    }

    private fun preCleanLine(line: String): String {
        return line.replace('\u00A0', ' ')
            .replace('–', '-')
            .replace('—', '-')
            .replace('−', '-')
            .replace("""\s+""".toRegex(), " ")
            .trim()
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

    // Відсікаємо нумеровані пункти договору (1.1., 2.4.), примітки-оферти та терміни оплати/балансу
    private fun isBodyOrReferenceClause(line: String): Boolean {
        val norm = normalize(line)
        if (Regex("""^\d+\.\d+\.?""").containsMatchIn(norm)) return true
        return norm.contains("на підставі підписаних") ||
               norm.contains("актів здачі") ||
               norm.contains("у специфікації") ||
               norm.contains("у рахунку-фактурі") ||
               norm.contains("склали цей акт") ||
               norm.contains("уклали цей договір") ||
               norm.contains("уклали цю специфікацію") ||
               norm.contains("одночасно актом") ||
               norm.contains("є актом") ||
               norm.contains("передавання-приймання") ||
               norm.contains("складання рахунка-акта") ||
               norm.contains("претензій одна до одної") ||
               norm.contains("претензій не мають") ||
               norm.contains("дійсний до") ||
               norm.contains("до сплати до") ||
               norm.contains("оплатити до") ||
               norm.contains("сплатити до") ||
               norm.contains("залишок авансу на") ||
               norm.contains("погашено борг на")
    }

    // Відсікаємо рядки банківських та особових рахунків, щоб "Рахунок отримувача 2600..." не ставав типом чи номером документа
    private fun isBankOrAccountLine(line: String): Boolean {
        val norm = normalize(line)
        return norm.contains("рахунок отримувача") ||
               norm.contains("рахунок постачальника") ||
               norm.contains("поточний рахунок") ||
               norm.contains("розрахунковий рахунок") ||
               norm.contains("особовий рахунок") ||
               norm.contains("р/р") ||
               norm.contains("p/p") ||
               norm.contains("iban") ||
               Regex("""\bua\d{15,29}\b""").containsMatchIn(norm) ||
               Regex("""\b26[025]\d{11,16}\b""").containsMatchIn(norm)
    }

    // Перевіряємо, чи рядок є посиланням на договір у шапці рахунку/акта/специфікації
    private fun isContractReferenceLine(line: String): Boolean {
        val norm = normalize(line)
        return norm.contains("договір") ||
               norm.contains("договору") ||
               norm.contains("договором") ||
               norm.contains("замовлення")
    }

    private fun detectType(lines: List<String>): String {
        val nonClauseLines = lines.filterNot { isBodyOrReferenceClause(it) }
        val nonBankLines = nonClauseLines.filterNot { isBankOrAccountLine(it) }
        val nonClauseNorm = normalize(nonBankLines.joinToString("\n"))
        val fullNorm = normalize(lines.joinToString("\n"))
        val top10Norm = normalize(nonBankLines.take(10).joinToString("\n"))

        // 1. Специфікація
        val hasSpecHeader = nonBankLines.any { line ->
            val n = normalize(line)
            Regex("""(?:^|\s)специфікаці[яїю](?:\s|№|$)""").containsMatchIn(n)
        }
        if (hasSpecHeader) return "Специфікація"

        // 2. Рахунки (включно з Рахунком-актом Укртелекому) та Накладні (включно з простою "НАКЛАДНА №")
        when {
            Regex("""рахун(?:ок|ка)\s*[-/.]?\s*акт""").containsMatchIn(fullNorm) ||
            (top10Norm.contains("рахунок") && fullNorm.contains("одночасно актом")) ->
                return "Рахунок-акт"
            Regex("""рахунок\s*[-/]\s*фактура""").containsMatchIn(nonClauseNorm) || nonClauseNorm.contains("рахунок фактура") ->
                return "Рахунок-фактура"
            nonClauseNorm.contains("інвойс") || nonClauseNorm.contains("invoice") ->
                return "Рахунок-фактура (Інвойс)"
            nonClauseNorm.contains("рахунок на оплат") ->
                return "Рахунок на оплату"
            nonClauseNorm.contains("видаткова накладна") || (nonClauseNorm.contains("видатк") && nonClauseNorm.contains("накладна")) ->
                return "Видаткова накладна"
            nonClauseNorm.contains("податкова накладна") ->
                return "Податкова накладна"
            nonClauseNorm.contains("товарно-транспортна") || nonClauseNorm.contains(" ттн ") ->
                return "Товарно-транспортна накладна"
            nonClauseNorm.contains("накладна-вимога") || (nonClauseNorm.contains("накладна") && nonClauseNorm.contains("внутрішнього переміщення")) ->
                return "Накладна-вимога на переміщення"
            Regex("""(?:^|\s)накладна(?:\s|№|$)""").containsMatchIn(nonClauseNorm) ->
                return "Накладна"
        }

        // 3. Акти (перевіряємо наявність заголовка АКТ поза пунктами договору та без слова "рахунок")
        val hasActHeader = nonBankLines.any { line ->
            val n = normalize(line)
            !n.contains("договор") && !n.contains("договір") && !n.contains("рахунок") && !n.contains("рахунка") &&
            (Regex("""(?:^|[^а-яіїєґ])акт(?:[^а-яіїєґ]|$)""").containsMatchIn(n) || n.contains("здачі-прийняття"))
        }
        if (hasActHeader) {
            return when {
                nonClauseNorm.contains("основних засобів") || nonClauseNorm.contains("оз-1") -> "Акт приймання-передачі ОЗ"
                nonClauseNorm.contains("списання") -> "Акт списання ТМЦ"
                nonClauseNorm.contains("здач") || nonClauseNorm.contains("прийм") || nonClauseNorm.contains("прийом") -> "Акт здачі-прийняття робіт"
                nonClauseNorm.contains("викон") -> "Акт виконаних робіт"
                nonClauseNorm.contains("послуг") -> "Акт надання послуг"
                else -> "Акт"
            }
        }

        // 4. Касові, банківські та кадрові документи
        when {
            nonClauseNorm.contains("прибутковий касовий ордер") || nonClauseNorm.contains("пко ") -> return "Прибутковий касовий ордер"
            nonClauseNorm.contains("видатковий касовий ордер") || nonClauseNorm.contains("вко ") -> return "Видатковий касовий ордер"
            nonClauseNorm.contains("журнал реєстрації") && nonClauseNorm.contains("касов") -> return "Журнал реєстрації касових документів"
            nonClauseNorm.contains("касова книга") -> return "Касова книга"
            nonClauseNorm.contains("платіжна інструкція") -> return "Платіжна інструкція"
            nonClauseNorm.contains("платіжне доручення") -> return "Платіжне доручення"
            nonClauseNorm.contains("виписка банку") || nonClauseNorm.contains("банківська виписка") || nonClauseNorm.contains("виписка по рахунку") -> return "Банківська виписка"
            nonClauseNorm.contains("табель обліку") && nonClauseNorm.contains("робочого часу") -> return "Табель обліку робочого часу"
            nonClauseNorm.contains("розрахунково-платіжна відомість") || nonClauseNorm.contains("розрахункова відомість") -> return "Розрахунково-платіжна відомість"
            nonClauseNorm.contains("наказ") && nonClauseNorm.contains("прийняття") -> return "Наказ про прийняття на роботу"
            nonClauseNorm.contains("наказ") && nonClauseNorm.contains("звільнення") -> return "Наказ про звільнення"
            nonClauseNorm.contains("наказ") && nonClauseNorm.contains("відпустк") -> return "Наказ про надання відпустки"
            nonClauseNorm.contains("наказ") || nonClauseNorm.contains("розпорядження") -> return "Наказ"
            nonClauseNorm.contains("авансовий звіт") || nonClauseNorm.contains("звіт про використання коштів") -> return "Авансовий звіт"
            nonClauseNorm.contains("бухгалтерська довідка") -> return "Бухгалтерська довідка"
            nonClauseNorm.contains("прибутковий ордер") -> return "Прибутковий ордер"
        }

        // 5. Додаткова угода та Договір
        if (top10Norm.contains("додаткова угода")) return "Додаткова угода"
        if (Regex("""(?:^|[^а-яіїєґ])догов[іi1l]р(?:[^а-яіїєґ]|$)""").containsMatchIn(top10Norm)) {
            return "Договір"
        }

        // 6. Загальні резервні типи (виключно поза рядками банківських рахунків)
        return when {
            nonClauseNorm.contains("накладна") -> "Накладна"
            nonClauseNorm.contains("квитанці") -> "Квитанція"
            nonClauseNorm.contains("чек") -> "Чек"
            nonClauseNorm.contains("рахунок") -> "Рахунок"
            nonClauseNorm.contains("договір") -> "Договір"
            else -> "Документ"
        }
    }

    // Знаходимо точний індекс рядка, де стоїть назва самого документа
    private fun findTitleAnchorIndex(lines: List<String>, docType: String): Int {
        for (i in lines.indices) {
            if (isBodyOrReferenceClause(lines[i]) || isBankOrAccountLine(lines[i])) continue
            val n = normalize(lines[i])
            val matched = when {
                docType == "Специфікація" -> n.contains("специфікаці")
                docType == "Рахунок-акт" -> Regex("""рахунок\s*[-/.]?\s*акт""").containsMatchIn(n) ||
                        (n.contains("рахунок") && n.contains("№"))
                docType == "Рахунок-фактура" -> Regex("""рахунок\s*[-/]?\s*фактура""").containsMatchIn(n)
                docType.startsWith("Рахунок") -> n.contains("рахунок") && !n.contains("по рахунку")
                docType.startsWith("Акт") -> !n.contains("договор") && !n.contains("договір") && !n.contains("рахунок") &&
                        Regex("""(?:^|[^а-яіїєґ])акт(?:[^а-яіїєґ]|$)""").containsMatchIn(n)
                docType == "Договір" -> !n.contains("до договор") && !n.contains("по договор") && !n.contains("згідно") &&
                        Regex("""(?:^|[^а-яіїєґ])догов[іi1l]р(?:[^а-яіїєґ]|$)""").containsMatchIn(n)
                docType == "Додаткова угода" -> n.contains("додаткова угода")
                docType.contains("накладна", true) -> n.contains("накладна")
                docType.startsWith("Наказ") -> n.contains("наказ") || n.contains("розпорядження")
                docType.contains("ордер", true) -> n.contains("ордер")
                docType.contains("інструкція", true) -> n.contains("інструкція")
                else -> false
            }
            if (matched) return i
        }
        return 0
    }

    private fun extractNumberByScope(lines: List<String>, fullText: String, docType: String, anchorIdx: Int): String {
        val isContract = docType == "Договір" || docType == "Додаткова угода"

        // 1. Ізольоване правило для Специфікації
        if (docType == "Специфікація") {
            for (line in lines) {
                if (isBodyOrReferenceClause(line)) continue
                val m = Regex("""(?iu)специфікація\s*(?:№|#|номер|[nмн][o0оеe°.]*)?\s*[:.]?\s*(\d+)""").find(line)
                if (m != null) return m.groupValues[1]
            }
            for (line in lines) {
                if (isBodyOrReferenceClause(line)) continue
                val m = Regex("""(?iu)додаток\s*(?:№|#|номер|[nмн][o0оеe°.]*)?\s*[:.]?\s*(\d+)""").find(line)
                if (m != null) return m.groupValues[1]
            }
            return "1"
        }

        // Крапка всередині номера (як 1240000000989370.8.2026) дозволена тільки БЕЗ пробілів, щоб не чіпляти слова після крапки
        val tokenRegex = Regex("""[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:(?:\s*[-/\\_]\s*|\.)[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*""")

        // 2. ПРІОРИТЕТ 1: Витягуємо номер безпосередньо з рядка заголовка (anchorIdx)
        // А також із наступного рядка (anchorIdx + 1), якщо номер перенесено вниз (як у "НАКЛАДНА № \n 39743")
        if (anchorIdx in lines.indices) {
            val anchorLine = lines[anchorIdx]
            val normAnchor = normalize(anchorLine)
            val titleKey = when {
                docType == "Рахунок-акт" -> if (normAnchor.contains("акт")) "акт" else "рахунок"
                docType == "Рахунок-фактура" -> "фактура"
                docType.startsWith("Рахунок") -> "рахунок"
                docType.startsWith("Акт") -> "акт"
                docType == "Договір" -> "догов"
                docType == "Додаткова угода" -> "угода"
                docType.contains("накладна", true) -> "накладна"
                else -> ""
            }
            val keyPos = if (titleKey.isNotEmpty()) normAnchor.indexOf(titleKey) else -1
            var tail = if (keyPos != -1) {
                val sub = anchorLine.substring(keyPos)
                val sp = sub.indexOf(' ')
                if (sp != -1) sub.substring(sp + 1) else ""
            } else {
                anchorLine
            }

            // Відрізаємо частину рядка після слів "від" або "за" ("за електронні комунікаційні послуги...")
            val stopWordSplit = Regex("""(?iu)\s+(?:[вВbB][іІiI1][дД]|[зЗzZ][аАaA])\s+""").find(tail)
            if (stopWordSplit != null) tail = tail.substring(0, stopWordSplit.range.first)

            for (tok in tokenRegex.findAll(tail)) {
                val cand = finalizeNumber(tok.value, fullText)
                if (isValidDocNumber(cand)) return cand
            }

            // Якщо в самому рядку заголовка після "НАКЛАДНА №" не було цифр (номер перенесено на наступний рядок)
            if (anchorIdx + 1 < lines.size) {
                val nextLine = lines[anchorIdx + 1]
                if (!isBodyOrReferenceClause(nextLine) && !isBankOrAccountLine(nextLine) && !isContractReferenceLine(nextLine)) {
                    var nextTail = nextLine
                    val nextStopSplit = Regex("""(?iu)\s+(?:[вВbB][іІiI1][дД]|[зЗzZ][аАaA])\s+""").find(nextTail)
                    if (nextStopSplit != null) nextTail = nextTail.substring(0, nextStopSplit.range.first)

                    for (tok in tokenRegex.findAll(nextTail)) {
                        val cand = finalizeNumber(tok.value, fullText)
                        if (isValidDocNumber(cand)) return cand
                    }
                }
            }
        }

        // 3. ПРІОРИТЕТ 2: Пошук за префіксом або складеним індексом у чистих рядках (без банківських рахунків!)
        val compoundRegex = Regex("""(?iu)\b([а-яa-zіїєґ]{1,4}\s*[-_]\s*0*\d{3,10}|\d{10,20}\.\d{1,2}\.\d{2,4}|\d{1,5}\s*[-/]\s*\d{1,5}(?:\s*[-/]\s*\d{1,5})?|\d{3,7})\b""")
        for (line in lines.take(15)) {
            if (isBodyOrReferenceClause(line) || isBankOrAccountLine(line)) continue
            if (!isContract && isContractReferenceLine(line)) continue
            val norm = normalize(line)
            if (norm.contains("єдрпоу") || norm.contains("іпн") || norm.contains("тел") || norm.contains("мфо") || norm.contains("корпус") || norm.contains("вул.")) continue

            val m = compoundRegex.find(line)
            if (m != null) {
                val cand = finalizeNumber(m.groupValues[1], fullText)
                if (isValidDocNumber(cand)) return cand
            }
        }

        return "б-н"
    }

    private fun extractDateByScope(
        lines: List<String>,
        fullText: String,
        docType: String,
        anchorIdx: Int,
        docNumber: String
    ): String {
        val isContractOrSpec = docType == "Договір" || docType == "Додаткова угода" || docType == "Специфікація"
        val defaultYear = Regex("""\b(202[4-9])\b""").find(fullText)?.groupValues?.get(1) ?: "2026"

        // ЗОНА 1 (Вікно заголовка: сам рядок заголовка + 3 наступні рядки)
        // Знаходить і "від 12 Жовтня 2026 р." у Рахунку-фактурі, і "Дата: 31.08.2026" у Рахунку-акті Укртелекому
        if (anchorIdx in lines.indices) {
            val endIdx = (anchorIdx + 3).coerceAtMost(lines.size - 1)
            val windowLines = (anchorIdx..endIdx).map { lines[it] }.filterNot { line ->
                isBodyOrReferenceClause(line) || isBankOrAccountLine(line) || (!isContractOrSpec && isContractReferenceLine(line))
            }
            val windowText = windowLines.joinToString(" ")
            val windowDates = findAllDatesInText(windowText, defaultYear, docNumber)
            if (windowDates.isNotEmpty()) {
                return windowDates.first()
            }
        }

        // ЗОНА 2 (Для Специфікації та Накладних, де дата стоїть на 1-3 рядки ВИЩЕ від заголовка, як у "від 13.10.2026 р. \n ... \n НАКЛАДНА №")
        if ((docType == "Специфікація" || docType.contains("накладна", true)) && anchorIdx in lines.indices) {
            val startIdx = (anchorIdx - 3).coerceAtLeast(0)
            val endIdx = (anchorIdx + 2).coerceAtMost(lines.size - 1)
            val upWindow = (startIdx..endIdx).map { lines[it] }.filterNot { line ->
                isBodyOrReferenceClause(line) || isBankOrAccountLine(line) || (!isContractOrSpec && isContractReferenceLine(line))
            }.joinToString(" ")
            val upDates = findAllDatesInText(upWindow, defaultYear, docNumber)
            if (upDates.isNotEmpty()) {
                return upDates.last()
            }
        }

        // ЗОНА 3 (Пошук по всіх дозволених рядках документа)
        val cleanLines = lines.filterNot { line ->
            isBodyOrReferenceClause(line) || isBankOrAccountLine(line) || (!isContractOrSpec && isContractReferenceLine(line))
        }
        val cleanBlock = cleanLines.joinToString(" ")
        val allDates = findAllDatesInText(cleanBlock, defaultYear, docNumber)
        if (allDates.isNotEmpty()) {
            return if (docType.startsWith("Акт")) allDates.last() else allDates.first()
        }

        // ЗОНА 4 (Резервна реконструкція для Актів, якщо печатка закрила число дня у підвалі)
        if (docType.startsWith("Акт")) {
            val monthYearRegex = Regex("""(?iu)([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*[-./\s]{1,3}\s*(202[4-9])""")
            for (m in monthYearRegex.findAll(cleanBlock)) {
                val monthCode = matchMonth(m.groupValues[1]) ?: continue
                val yearStr = m.groupValues[2]
                val day = LAST_DAY_OF_MONTH[monthCode] ?: "30"
                return "$day.$monthCode.$yearStr"
            }
        }

        return "не_вказано"
    }

    private fun findAllDatesInText(text: String, defaultYear: String, docNumber: String): List<String> {
        val results = mutableListOf<String>()

        // 1. Словесно-цифрові дати: «24» серпня 2026, 30-Вересня 2026 р., від 12 Жовтня 2026 р.
        val monthYearAnchor = Regex(
            """(?iu)([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*[-./\s]{1,3}\s*(20[0-3][0-9ОоOб]|[2-3][0-9ОоOб])(?!\d)"""
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
                results.add("${day.toString().padStart(2, '0')}.$monthCode.$yearStr")
            }
        }

        // 2. Числові дати через крапку: ДД.ММ.РРРР або ДД.ММ.РР (включно з "від 13.10.2026 р." та "Дата: 31.08.2026")
        val numericRegex = Regex("""(?<!\d)([0-3]?[0-9])\.([0-1]?[0-9])\.(20[2-3][0-9]|[2-3][0-9])(?!\d)""")
        for (match in numericRegex.findAll(text)) {
            val rawMatch = match.value
            val asHyphen = rawMatch.replace('.', '-')
            if (asHyphen == docNumber || (docNumber.isNotEmpty() && docNumber.contains(rawMatch))) continue

            val d = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            var y = match.groupValues[3]
            if (y.length == 2) y = "20$y"
            if (d in 1..31 && m in 1..12) {
                results.add("${d.toString().padStart(2, '0')}.${m.toString().padStart(2, '0')}.$y")
            }
        }

        return results
    }

    private fun extractParentContractForSpec(lines: List<String>, fullText: String, specDate: String): Pair<String, String> {
        var cNum = ""
        var cDate = ""

        for (i in lines.indices) {
            val norm = normalize(lines[i])
            if (norm.contains("договор") || norm.contains("договір")) {
                val endIdx = (i + 2).coerceAtMost(lines.size - 1)
                val window = (i..endIdx).joinToString(" ") { lines[it] }
                val numMatch = Regex("""(\d{1,5}\s*[-/]\s*\d{1,5}(?:\s*[-/]\s*\d{1,5})?|[а-яa-zіїєґ]{1,4}\s*[-]\s*\d{1,10})""", RegexOption.IGNORE_CASE).find(window)
                if (numMatch != null) {
                    cNum = finalizeNumber(numMatch.groupValues[1], fullText)
                }
                val foundDate = findAllDatesInText(window, "2026", cNum).firstOrNull()
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
        // Відсікаємо дати
        if (cand.matches(Regex("""^\d{2}\.\d{2}\.20\d{2}$"""))) return false
        // Відсікаємо IBAN та 14-19 значні банківські рахунки (напр. 26008639633300), МФО (351005) та поштові індекси (20300, 49000, 01601)
        if (cand.startsWith("UA") && cand.length >= 20) return false
        if (cand.matches(Regex("""^26[025]\d{11,16}$"""))) return false
        if (cand.matches(Regex("""^(?:20300|49000|49001|01601|351005|334851)$"""))) return false
        return true
    }

    private fun finalizeNumber(raw: String, fullText: String): String {
        var clean = raw.replace("""\s+""".toRegex(), "")
            .trim('.', ',', ';', ':', '-', '«', '»', '"')
            .replace('/', '-')
            .replace('\\', '-')
            .uppercase()

        clean = clean.replace(Regex("""^(?:№|#|НОМЕР|N[O0ОЕE°.]*|М[ОO0ЕE°.]*|Н[ОO0°.]*)(?=\d)"""), "")
        clean = clean.replace(Regex("""^(?:№|#|НОМЕР)[:.-]*"""), "")
        clean = clean.replace(Regex("""[.-]ЗА$"""), "")
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
        val safeFixed = prefix
            .replace(Regex("""(?<=\d)[ОоO]"""), "0")
            .replace(Regex("""[ОоO](?=\d)"""), "0")
            .replace(Regex("""(?<=\d)[Зз]"""), "3")
            .replace(Regex("""[Зз](?=\d)"""), "3")
            .replace(Regex("""(?<=\d)б"""), "6")
            .replace(Regex("""(?<=\d)[ІіlI]"""), "1")
            .replace(Regex("""[ІіlI](?=\d)"""), "1")

        val tokens = Regex("""\d+""").findAll(safeFixed).map { it.value }.toList()
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
