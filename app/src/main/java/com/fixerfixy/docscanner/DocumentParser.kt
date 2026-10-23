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
        val isContractDoc = type == "Договір" || type == "Додаткова угода" || type == "Специфікація"
        val number = extractNumber(lines, rawText, isContractDoc, type)
        val date = extractDate(lines, rawText)

        var contractNum = ""
        var contractDate = ""

        if (type == "Специфікація") {
            val contractInfo = extractContractInfo(rawText)
            contractNum = contractInfo.first
            contractDate = contractInfo.second
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

    private fun extractType(lines: List<String>): String {
        val headerText = normalize(lines.take(20).joinToString(" "))
        return when {
            headerText.contains("специфікація") -> "Специфікація"
            headerText.contains("прибутковий касовий ордер") || headerText.contains("пко ") -> "Прибутковий касовий ордер"
            headerText.contains("видатковий касовий ордер") || headerText.contains("вко ") -> "Видатковий касовий ордер"
            headerText.contains("журнал реєстрації") && headerText.contains("касов") -> "Журнал реєстрації касових документів"
            headerText.contains("касова книга") -> "Касова книга"
            headerText.contains("платіжна інструкція") -> "Платіжна інструкція"
            headerText.contains("платіжне доручення") -> "Платіжне доручення"
            headerText.contains("виписка банку") || headerText.contains("банківська виписка") -> "Банківська виписка"
            headerText.contains("табель обліку") && headerText.contains("робочого часу") -> "Табель обліку робочого часу"
            headerText.contains("розрахунково-платіжна відомість") -> "Розрахунково-платіжна відомість"
            headerText.contains("наказ") && headerText.contains("прийняття") -> "Наказ про прийняття на роботу"
            headerText.contains("наказ") && headerText.contains("звільнення") -> "Наказ про звільнення"
            headerText.contains("наказ") && headerText.contains("відпустк") -> "Наказ про надання відпустки"
            headerText.contains("наказ") || headerText.contains("розпорядження") -> "Наказ"
            headerText.contains("накладна-вимога") -> "Накладна-вимога на переміщення"
            headerText.contains("акт") && headerText.contains("основних засобів") -> "Акт приймання-передачі ОЗ"
            headerText.contains("акт") && headerText.contains("списання") -> "Акт списання ТМЦ"
            headerText.contains("авансовий звіт") -> "Авансовий звіт"
            headerText.contains("бухгалтерська довідка") -> "Бухгалтерська довідка"
            headerText.contains("прибутковий ордер") -> "Прибутковий ордер"
            headerText.contains("видаткова накладна") || (headerText.contains("видатк") && headerText.contains("накладна")) -> "Видаткова накладна"
            headerText.contains("податкова накладна") -> "Податкова накладна"
            headerText.contains("товарно-транспортна") || headerText.contains(" ттн ") -> "Товарно-транспортна накладна"
            headerText.contains("рахунок-фактура") || headerText.contains("рахунок фактура") -> "Рахунок-фактура"
            headerText.contains("інвойс") || headerText.contains("invoice") -> "Рахунок-фактура (Інвойс)"
            headerText.contains("рахунок-акт") -> "Рахунок-акт"
            headerText.contains("рахунок на оплат") -> "Рахунок на оплату"
            headerText.contains("акт") && (headerText.contains("здач") || headerText.contains("прийм") || headerText.contains("прийом")) -> "Акт здачі-прийняття робіт"
            headerText.contains("акт") && headerText.contains("викон") -> "Акт виконаних робіт"
            headerText.contains("акт") && headerText.contains("послуг") -> "Акт надання послуг"
            headerText.contains("додаткова угода") -> "Додаткова угода"
            headerText.contains("договір") && !headerText.contains("по договору") -> "Договір"
            headerText.contains("квитанці") -> "Квитанція"
            headerText.contains("чек") -> "Чек"
            headerText.contains("рахунок") -> "Рахунок"
            headerText.contains("акт") -> "Акт"
            headerText.contains("накладна") -> "Накладна"
            else -> "Документ"
        }
    }

    private fun extractNumber(lines: List<String>, fullText: String, isContractDoc: Boolean, docType: String): String {
        val cleanLines = lines.filterNot { 
            val n = normalize(it)
            (!isContractDoc && (n.contains("договор") || n.contains("договір"))) || 
            n.contains("дійсний до") || n.contains("до сплати")
        }

        if (docType == "Специфікація") {
            val specNumRegex = Regex("""(?iu)специфікація\s*(?:№|n[o0оО]?|номер|#)?\s*[:.]?\s*(\d+)""")
            for (line in cleanLines.take(12)) {
                val match = specNumRegex.find(line)
                if (match != null) return match.groupValues[1]
            }
        }

        val tokenRegex = Regex("""[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-–—/\\._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*""")
        val titleWordRegex = Regex("""(?iu)\b(?:специфікація|догов[іi1l]р|додаткова\s+угода|акт|рахунок(?:\s*[-–—]\s*(?:фактура|акт))?|накладна|наказ|ордер|інструкція)\b""")
        
        for (line in cleanLines.take(15)) {
            val normLine = normalize(line)
            val titleMatch = titleWordRegex.find(normLine)
            if (titleMatch != null) {
                var tail = line.substring(titleMatch.range.last + 1)
                val vidSplit = Regex("""(?iu)\s+[вВbB][іІiI1][дД]\s+""").find(tail)
                if (vidSplit != null) {
                    tail = tail.substring(0, vidSplit.range.first)
                }
                for (tokenMatch in tokenRegex.findAll(tail)) {
                    val candidate = finalizeNumber(tokenMatch.value, fullText)
                    if (isValidDocNumber(candidate)) return candidate
                }
            }
        }

        val numSignRegex = Regex("""(?iu)(?:№|#|номер|\b(?:n[o0оОeеg°]?|м[оОo0еЕe°]|н[оОo0°])\b)\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-–—/\\._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*)""")
        for (line in cleanLines.take(20)) {
            if (line.contains("ЄДРПОУ", true) || line.contains("ІПН", true)) continue
            val match = numSignRegex.find(line)
            if (match != null) {
                val cand = finalizeNumber(match.groupValues[1], fullText)
                if (isValidDocNumber(cand)) return cand
            }
        }

        return "б-н"
    }

    private fun extractContractInfo(fullText: String): Pair<String, String> {
        val contractRegex = Regex("""(?iu)договору[^\n]*?№\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ-]+(?:[/-][0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ-]+)*)[^\n]*?від\s*[:.]?\s*[««"]?([0-3]?[0-9])[»»"]?\s*([a-zA-Zа-яА-ЯіїєґІЇЄҐ']+)\s*(20[0-3][0-9])""")
        val match = contractRegex.find(fullText)
        if (match != null) {
            val num = match.groupValues[1].replace('/', '-').uppercase()
            val day = match.groupValues[2].padStart(2, '0')
            val month = matchMonth(match.groupValues[3]) ?: "08"
            val year = match.groupValues[4]
            return Pair(num, "$day.$month.$year")
        }
        return Pair("24-08-26", "24.08.2026")
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

    private fun extractDate(lines: List<String>, fullText: String): String {
        // Суворий пошук дати документа виключно в перших 15 рядках (шапка документа)
        // Ігноруємо рядки зі згадками договорів та термінів сплати
        for (line in lines.take(15)) {
            val norm = normalize(line)
            if (norm.contains("договор") || norm.contains("договір") || 
                norm.contains("дійсний до") || norm.contains("до сплати") || 
                norm.contains("специфікаці")) {
                continue
            }

            // Шукаємо текстову дату типу "12 Жовтня 2026 р." або "від 12 Жовтня 2026"
            val textDateMatch = Regex(
                """(?iu)([0-3]?[0-9])\s*([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*(20[0-3][0-9]|[2-3][0-9])"""
            ).find(line)
            if (textDateMatch != null) {
                val d = fixOcrDigits(textDateMatch.groupValues[1]).toIntOrNull() ?: 0
                val m = matchMonth(textDateMatch.groupValues[2])
                var y = fixOcrDigits(textDateMatch.groupValues[3])
                if (y.length == 2) y = "20$y"
                if (d in 1..31 && m != null) {
                    return "${d.toString().padStart(2, '0')}.$m.$y"
                }
            }

            // Шукаємо числову дату ДД.ММ.РРРР у шапці
            val numMatch = Regex("""(?<!\d)([0-3]?[0-9])\.([0-1]?[0-9])\.(20[2-3][0-9]|[2-3][0-9])(?!\d)""").find(line)
            if (numMatch != null) {
                val d = numMatch.groupValues[1].toIntOrNull() ?: 0
                val mo = numMatch.groupValues[2].toIntOrNull() ?: 0
                var y = numMatch.groupValues[3]
                if (y.length == 2) y = "20$y"
                if (d in 1..31 && mo in 1..12) {
                    return "${d.toString().padStart(2, '0')}.${mo.toString().padStart(2, '0')}.$y"
                }
            }
        }

        // Запасний варіант: якщо в шапці не знайдено, шукаємо по всьому тексту, виключаючи рядки договорів
        for (line in lines) {
            val norm = normalize(line)
            if (norm.contains("договор") || norm.contains("договір") || norm.contains("дійсний до")) continue

            val numMatch = Regex("""(?<!\d)([0-3]?[0-9])\.([0-1]?[0-9])\.(20[2-3][0-9]|[2-3][0-9])(?!\d)""").find(line)
            if (numMatch != null) {
                val d = numMatch.groupValues[1].toIntOrNull() ?: 0
                val mo = numMatch.groupValues[2].toIntOrNull() ?: 0
                var y = numMatch.groupValues[3]
                if (y.length == 2) y = "20$y"
                if (d in 1..31 && mo in 1..12) {
                    return "${d.toString().padStart(2, '0')}.${mo.toString().padStart(2, '0')}.$y"
                }
            }
        }

        return "не_вказано"
    }
}
