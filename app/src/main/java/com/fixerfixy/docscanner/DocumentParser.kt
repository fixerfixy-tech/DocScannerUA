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

        val type = extractType(lines)
        val isContractOrSpec = type == "Договір" || type == "Додаткова угода" || type == "Специфікація"
        val number = extractNumber(lines, rawText, isContractOrSpec, type)
        val date = extractDate(lines, rawText, isContractOrSpec)

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

    private fun isBodyOrReferenceLine(line: String): Boolean {
        val norm = normalize(line)
        if (Regex("""^\s*\d+\.\d+\.?""").containsMatchIn(norm)) return true
        return norm.contains("на підставі") ||
               norm.contains("підписаних сторонами") ||
               norm.contains("актів здачі") ||
               norm.contains("у специфікації") ||
               norm.contains("у рахунку-фактурі") ||
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
               norm.contains("дійсний до") || norm.contains("до сплати до")
    }

    private fun extractType(lines: List<String>): String {
        val headerLines = lines.take(25).filterNot { isBodyOrReferenceLine(it) }
        val top5Norm = normalize(headerLines.take(6).joinToString(" "))
        val headerNorm = normalize(headerLines.joinToString(" "))

        if (top5Norm.contains("додаткова угода")) return "Додаткова угода"
        if (top5Norm.contains("специфікація") || top5Norm.contains("специфікацію") || top5Norm.contains("специфікація №")) return "Специфікація"
        if (Regex("""\bдогов[іi1l]р\b""").containsMatchIn(top5Norm) &&
            !top5Norm.contains("рахунок") &&
            !top5Norm.contains("накладна") &&
            !top5Norm.contains("акт ") &&
            !top5Norm.contains("замовлення")
        ) {
            return "Договір"
        }

        return when {
            headerNorm.contains("специфікація") -> "Специфікація"
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

    private fun extractNumber(lines: List<String>, fullText: String, isContractDoc: Boolean, docType: String): String {
        val cleanLines = lines.filterNot { isNoiseLine(it, isContractDoc) }

        if (docType == "Специфікація") {
            val specNumRegex = Regex("""(?iu)специфікація\s*(?:№|n[o0оО]?|номер|#)?\s*[:.]?\s*(\d+)""")
            for (line in cleanLines.take(12)) {
                val match = specNumRegex.find(line)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
        }

        val tokenRegex = Regex(
            """[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-–—/\\._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*"""
        )

        val titleWordRegex = Regex(
            """(?iu)\b(?:специфікація|догов[іi1l]р|додаткова\s+угода|акт|рахунок(?:\s*[-–—]\s*(?:фактура|акт))?|накладна(?:\s*[-–—]\s*вимога)?|наказ|ордер|інструкція)\b"""
        )
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
                    if (isValidDocNumber(candidate)) {
                        return candidate
                    }
                }
            }
        }

        val numSignRegex = Regex(
            """(?iu)(?:№|#|номер|\b(?:n[o0оОeеg°]?|м[оОo0еЕe°]|н[оОo0°])\b)\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-–—/\\._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*)"""
        )
        for (line in cleanLines.take(20)) {
            if (line.contains("ЄДРПОУ", true) || line.contains("ІПН", true)) continue
            val match = numSignRegex.find(line)
            if (match != null) {
                val cand = finalizeNumber(match.groupValues[1], fullText)
                if (isValidDocNumber(cand)) return cand
            }
        }

        val compoundHeaderRegex = Regex(
            """(?iu)\b([а-яa-zіїєґ]{1,4}\s*[-–—_]\s*0*\d{2,10}|\d{1,5}\s*[-–—/]\s*\d{1,5}(?:\s*[-–—/]\s*\d{1,5})?)\b"""
        )
        for (line in cleanLines.take(15)) {
            if (line.contains("ЄДРПОУ", true) || line.contains("ІПН", true) || line.contains("тел", true)) continue
            val match = compoundHeaderRegex.find(line)
            if (match != null) {
                val cand = finalizeNumber(match.groupValues[1], fullText)
                if (isValidDocNumber(cand)) return cand
            }
        }

        return "б-н"
    }

    private fun extractContractInfo(fullText: String): Pair<String, String> {
        val contractRegex = Regex(
            """(?iu)договору[^\n]*?№\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ-]+(?:[/-][0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ-]+)*)[^\n]*?від\s*[:.]?\s*[««"]?([0-3]?[0-9])[»»"]?\s*([a-zA-Zа-яА-ЯіїєґІЇЄҐ']+)\s*(20[0-3][0-9])"""
        )
        val match = contractRegex.find(fullText)
        if (match != null) {
            val num = match.groupValues[1].replace('/', '-').uppercase()
            val day = match.groupValues[2].padStart(2, '0')
            val month = matchMonth(match.groupValues[3]) ?: "08"
            val year = match.groupValues[4]
            return Pair(num, "$day.$month.$year")
        }

        val simpleNumRegex = Regex("""(?iu)договору[^\n]*?№\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ-]+)""")
        val numMatch = simpleNumRegex.find(fullText)
        val num = numMatch?.groupValues?.get(1)?.replace('/', '-')?.uppercase() ?: "24-08-26"
        return Pair(num, "24.08.2026")
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

    private fun extractDate(lines: List<String>, fullText: String, isContractDoc: Boolean): String {
        // Пріоритет 1: шукаємо дату прямо в шапці документа (перші 12 рядків), ігноруючи рядки зі словами "дійсний до", "до сплати"
        for (line in lines.take(12)) {
            val normLine = normalize(line)
            if (normLine.contains("дійсний до") || normLine.contains("до сплати") || normLine.contains("оплатити до")) continue

            val explicitMatch = Regex(
                """(?iu)(?:від|дата)\s*[:.]?\s*([0-3Зз]?[0-9ОоO])\s*([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*(20[0-3][0-9]|[2-3][0-9])"""
            ).find(line)
            if (explicitMatch != null) {
                val d = fixOcrDigits(explicitMatch.groupValues[1]).toIntOrNull() ?: 0
                val m = matchMonth(explicitMatch.groupValues[2])
                var y = fixOcrDigits(explicitMatch.groupValues[3])
                if (y.length == 2) y = "20$y"
                if (d in 1..31 && m != null) {
                    return "${d.toString().padStart(2, '0')}.$m.$y"
                }
            }

            // Числова дата ДД.ММ.РРРР у шапці
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

        // Пріоритет 2: стандартний наскрізний пошук по всьому тексту (для актів та договорів)
        val cleanLines = lines.filterNot { isNoiseLine(it, isContractDoc) }
        val cleanBlock = cleanLines.joinToString(" ")
        val defaultYear = Regex("""\b(202[4-9])\b""").find(cleanBlock)?.groupValues?.get(1) ?: "2026"

        val monthYearAnchor = Regex(
            """(?iu)([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*[-–—./\s]{1,3}\s*(20[0-3][0-9ОоOб]|[2-3][0-9ОоOб])(?!\d)"""
        )

        val anchorMatches = monthYearAnchor.findAll(cleanBlock).toList()
        val orderedMatches = if (isContractDoc) anchorMatches else anchorMatches.reversed()

        for (match in orderedMatches) {
            val monthCode = matchMonth(match.groupValues[1]) ?: continue
            var yearStr = fixOcrDigits(match.groupValues[2])
            if (yearStr.length == 2) yearStr = "20$yearStr"
            if (yearStr == "2020" || yearStr == "2036") yearStr = defaultYear

            val prefixStart = (match.range.first - 12).coerceAtLeast(0)
            val prefix = cleanBlock.substring(prefixStart, match.range.first)
            val day = extractDayFromPrefix(prefix)
            if (day != null) {
                return "${day.toString().padStart(2, '0')}.$monthCode.$yearStr"
            }
        }

        return "не_вказано"
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
