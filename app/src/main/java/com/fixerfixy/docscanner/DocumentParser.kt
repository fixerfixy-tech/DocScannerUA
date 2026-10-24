package com.fixerfixy.docscanner

object DocumentParser {

    private enum class LineRole {
        NOISE_STAMP,
        NOISE_CLAUSE,
        REF_CONTRACT,
        CLEAN
    }

    private data class TaggedLine(
        val index: Int,
        val raw: String,
        val norm: String,
        val role: LineRole
    )

    private data class DocMatch(
        val type: String,
        val anchorLineIndex: Int,
        val anchorKeyword: String
    )

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

        val rawLines = rawText.lines()
            .map { preCleanLine(it) }
            .filter { it.isNotBlank() }

        val taggedLines = rawLines.mapIndexed { idx, line ->
            val norm = normalizeText(line)
            val role = classifyLineRole(norm)
            TaggedLine(idx, line, norm, role)
        }

        val docMatch = detectDocumentType(taggedLines)
        val docType = docMatch.type
        val docNumber = extractDocumentNumber(taggedLines, docMatch, rawText)
        val docDate = extractDocumentDate(taggedLines, docMatch, docNumber)

        var parentContractNum = ""
        var parentContractDate = ""
        if (docType == "Специфікація" || docType == "Додаткова угода") {
            val parentInfo = extractParentContractInfo(taggedLines, rawText, docDate)
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

    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace('a', 'а').replace('e', 'е')
            .replace('i', 'і').replace('o', 'о')
            .replace('p', 'р').replace('c', 'с')
            .replace('x', 'х').replace('y', 'у')
            .replace('k', 'к').replace('m', 'м')
            .replace('h', 'н').replace('t', 'т')
            .replace('b', 'в')
    }

    private fun classifyLineRole(norm: String): LineRole {
        if (Regex("""^\d+\.\d+\.?""").containsMatchIn(norm)) return LineRole.NOISE_CLAUSE
        if (norm.contains("на підставі підписаних") ||
            norm.contains("актів здачі") ||
            norm.contains("у специфікації") ||
            norm.contains("у рахунку-фактурі") ||
            norm.contains("склали цей акт") ||
            norm.contains("уклали цей договір") ||
            norm.contains("уклали цю специфікацію") ||
            norm.contains("претензій одна до одної") ||
            norm.contains("претензій не мають") ||
            norm.contains("дійсний до") ||
            norm.contains("до сплати до") ||
            norm.contains("оплатити до")
        ) {
            return LineRole.NOISE_CLAUSE
        }

        if (norm.contains("по договору") ||
            norm.contains("до договору") ||
            norm.contains("згідно договору") ||
            norm.contains("згідно з договором") ||
            norm.contains("за договором")
        ) {
            return LineRole.REF_CONTRACT
        }

        if (norm.contains("товариство з обмеженою") ||
            norm.contains("ідентифікаційний код") ||
            norm.contains("номер свідоцтва") ||
            norm.contains("мфо ") ||
            norm.contains("єдрпоу")
        ) {
            return LineRole.NOISE_STAMP
        }

        return LineRole.CLEAN
    }

    private fun detectDocumentType(lines: List<TaggedLine>): DocMatch {
        val cleanAndRefLines = lines.filter { it.role == LineRole.CLEAN || it.role == LineRole.REF_CONTRACT }
        val cleanOnlyLines = lines.filter { it.role == LineRole.CLEAN }

        for (tl in cleanAndRefLines) {
            if (Regex("""\bспецифікаці[яїю]\b""").containsMatchIn(tl.norm)) {
                return DocMatch("Специфікація", tl.index, "специфікац")
            }
        }
        for (tl in cleanAndRefLines) {
            if (Regex("""\bдодаток\b""").containsMatchIn(tl.norm) &&
                lines.any { it.role == LineRole.REF_CONTRACT || it.norm.contains("специфікац") }
            ) {
                return DocMatch("Специфікація", tl.index, "додаток")
            }
        }

        for (tl in cleanOnlyLines.take(12)) {
            if (tl.norm.contains("додаткова угода")) {
                return DocMatch("Додаткова угода", tl.index, "додаткова угода")
            }
        }

        val hasPrimaryDocTitle = cleanOnlyLines.any {
            Regex("""\b(рахунок(?:-фактура)?|акт|видаткова накладна|товарно-транспортна|платіжна інструкція)\b""").containsMatchIn(it.norm)
        }
        if (!hasPrimaryDocTitle) {
            for (tl in cleanOnlyLines.take(8)) {
                if (Regex("""\bдогов[іi1l]р\b""").containsMatchIn(tl.norm)) {
                    return DocMatch("Договір", tl.index, "догов")
                }
            }
        }

        for (tl in cleanOnlyLines) {
            val n = tl.norm
            val windowNorm = lines.filter { it.index in (tl.index - 1)..(tl.index + 4) && it.role == LineRole.CLEAN }
                .joinToString(" ") { it.norm }

            when {
                n.contains("прибутковий касовий ордер") || n.contains("пко ") ->
                    return DocMatch("Прибутковий касовий ордер", tl.index, "ордер")
                n.contains("видатковий касовий ордер") || n.contains("вко ") ->
                    return DocMatch("Видатковий касовий ордер", tl.index, "ордер")
                n.contains("журнал реєстрації") && windowNorm.contains("касов") ->
                    return DocMatch("Журнал реєстрації касових документів", tl.index, "журнал")
                n.contains("касова книга") ->
                    return DocMatch("Касова книга", tl.index, "касова книга")
                n.contains("платіжна інструкція") ->
                    return DocMatch("Платіжна інструкція", tl.index, "платіжна інструкція")
                n.contains("платіжне доручення") ->
                    return DocMatch("Платіжне доручення", tl.index, "платіжне доручення")
                n.contains("виписка банку") || n.contains("банківська виписка") || n.contains("виписка по рахунку") ->
                    return DocMatch("Банківська виписка", tl.index, "виписка")

                n.contains("табель обліку") && windowNorm.contains("робочого часу") ->
                    return DocMatch("Табель обліку робочого часу", tl.index, "табель")
                n.contains("розрахунково-платіжна відомість") || n.contains("розрахункова відомість") ->
                    return DocMatch("Розрахунково-платіжна відомість", tl.index, "відомість")
                Regex("""\bнаказ\b""").containsMatchIn(n) && windowNorm.contains("прийняття") ->
                    return DocMatch("Наказ про прийняття на роботу", tl.index, "наказ")
                Regex("""\bнаказ\b""").containsMatchIn(n) && windowNorm.contains("звільнення") ->
                    return DocMatch("Наказ про звільнення", tl.index, "наказ")
                Regex("""\bнаказ\b""").containsMatchIn(n) && windowNorm.contains("відпустк") ->
                    return DocMatch("Наказ про надання відпустки", tl.index, "наказ")
                Regex("""\b(наказ|розпорядження)\b""").containsMatchIn(n) ->
                    return DocMatch("Наказ", tl.index, "наказ")

                n.contains("накладна-вимога") || (n.contains("накладна") && windowNorm.contains("внутрішнього переміщення")) ->
                    return DocMatch("Накладна-вимога на переміщення", tl.index, "накладна")
                n.contains("авансовий звіт") || n.contains("звіт про використання коштів") ->
                    return DocMatch("Авансовий звіт", tl.index, "звіт")
                n.contains("бухгалтерська довідка") ->
                    return DocMatch("Бухгалтерська довідка", tl.index, "довідка")
                n.contains("прибутковий ордер") ->
                    return DocMatch("Прибутковий ордер", tl.index, "ордер")

                n.contains("видаткова накладна") || (n.contains("видатк") && n.contains("накладна")) ->
                    return DocMatch("Видаткова накладна", tl.index, "накладна")
                n.contains("податкова накладна") ->
                    return DocMatch("Податкова накладна", tl.index, "накладна")
                n.contains("товарно-транспортна") || n.contains(" ттн ") ->
                    return DocMatch("Товарно-транспортна накладна", tl.index, "накладна")

                Regex("""рахунок\s*[-/]\s*фактура""").containsMatchIn(n) || n.contains("рахунок фактура") ->
                    return DocMatch("Рахунок-фактура", tl.index, "фактура")
                n.contains("інвойс") || n.contains("invoice") ->
                    return DocMatch("Рахунок-фактура (Інвойс)", tl.index, "інвойс")
                Regex("""рахунок\s*[-/]\s*акт""").containsMatchIn(n) || n.contains("рахунок акт") ->
                    return DocMatch("Рахунок-акт", tl.index, "рахунок")
                n.contains("рахунок на оплат") ->
                    return DocMatch("Рахунок на оплату", tl.index, "оплат")

                Regex("""\bакт\b""").containsMatchIn(n) && (windowNorm.contains("основних засобів") || windowNorm.contains("оз-1")) ->
                    return DocMatch("Акт приймання-передачі ОЗ", tl.index, "акт")
                Regex("""\bакт\b""").containsMatchIn(n) && windowNorm.contains("списання") ->
                    return DocMatch("Акт списання ТМЦ", tl.index, "акт")
                Regex("""\bакт\b""").containsMatchIn(n) && (windowNorm.contains("здач") || windowNorm.contains("прийм") || windowNorm.contains("прийом")) ->
                    return DocMatch("Акт здачі-прийняття робіт", tl.index, "акт")
                Regex("""\bакт\b""").containsMatchIn(n) && windowNorm.contains("викон") ->
                    return DocMatch("Акт виконаних робіт", tl.index, "акт")
                Regex("""\bакт\b""").containsMatchIn(n) && windowNorm.contains("послуг") ->
                    return DocMatch("Акт надання послуг", tl.index, "акт")
            }
        }

        for (tl in cleanOnlyLines) {
            val n = tl.norm
            when {
                Regex("""\bрахунок\b""").containsMatchIn(n) -> return DocMatch("Рахунок", tl.index, "рахунок")
                Regex("""\bакт\b""").containsMatchIn(n) -> return DocMatch("Акт", tl.index, "акт")
                Regex("""\bнакладна\b""").containsMatchIn(n) -> return DocMatch("Накладна", tl.index, "накладна")
                Regex("""\bдогов[іi1l]р\b""").containsMatchIn(n) -> return DocMatch("Договір", tl.index, "догов")
                n.contains("квитанці") -> return DocMatch("Квитанція", tl.index, "квитанц")
                n.contains("чек") -> return DocMatch("Чек", tl.index, "чек")
            }
        }

        return DocMatch("Документ", 0, "")
    }

    private fun extractDocumentNumber(lines: List<TaggedLine>, match: DocMatch, fullText: String): String {
        val docType = match.type
        val isContract = docType == "Договір" || docType == "Додаткова угода"

        if (docType == "Специфікація") {
            val specRegex = Regex("""(?iu)(?:специфікаці[яї]|додаток)\s*(?:№|#|номер|[nмн][o0оеe°.]*)?\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:[-/][0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*)""")
            for (tl in lines) {
                if (tl.role == LineRole.NOISE_CLAUSE) continue
                val specOnly = Regex("""(?iu)специфікація\s*(?:№|#|номер|[nмн][o0оеe°.]*)?\s*[:.]?\s*(\d+)""").find(tl.raw)
                if (specOnly != null) return specOnly.groupValues[1]
            }
            for (tl in lines) {
                if (tl.role == LineRole.NOISE_CLAUSE) continue
                val m = specRegex.find(tl.raw)
                if (m != null) {
                    val cand = finalizeNumber(m.groupValues[1], fullText)
                    if (isValidDocNumber(cand)) return cand
                }
            }
            return "1"
        }

        val tokenRegex = Regex("""[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-/\\._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*""")

        val anchorLine = lines.firstOrNull { it.index == match.anchorLineIndex }
        if (anchorLine != null && match.anchorKeyword.isNotEmpty()) {
            val kwIdx = anchorLine.norm.indexOf(match.anchorKeyword)
            if (kwIdx != -1) {
                var tail = anchorLine.raw.substring(kwIdx)
                val firstSpace = tail.indexOf(' ')
                if (firstSpace != -1) {
                    tail = tail.substring(firstSpace + 1)
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

        for (tl in lines) {
            if (tl.role != LineRole.CLEAN) continue
            if (!isContract && (tl.norm.contains("договор") || tl.norm.contains("договір"))) continue
            if (match.anchorKeyword.isNotEmpty() && tl.norm.contains(match.anchorKeyword)) {
                val kwIdx = tl.norm.indexOf(match.anchorKeyword)
                var tail = tl.raw.substring(kwIdx)
                val firstSpace = tail.indexOf(' ')
                if (firstSpace != -1) {
                    tail = tail.substring(firstSpace + 1)
                    val vidSplit = Regex("""(?iu)\s+[вВbB][іІiI1][дД]\s+""").find(tail)
                    if (vidSplit != null) tail = tail.substring(0, vidSplit.range.first)
                    for (tok in tokenRegex.findAll(tail)) {
                        val cand = finalizeNumber(tok.value, fullText)
                        if (isValidDocNumber(cand)) return cand
                    }
                }
            }
        }

        val compoundRegex = Regex("""(?iu)\b([а-яa-zіїєґ]{1,4}\s*[-_]\s*0*\d{3,10}|\d{1,5}\s*[-/]\s*\d{1,5}(?:\s*[-/]\s*\d{1,5})?)\b""")
        for (tl in lines) {
            if (tl.role != LineRole.CLEAN) continue
            if (!isContract && (tl.norm.contains("договор") || tl.norm.contains("договір"))) continue
            if (tl.norm.contains("тел") || tl.norm.contains("адрес") || tl.norm.contains("пдв")) continue
            val m = compoundRegex.find(tl.raw)
            if (m != null) {
                val cand = finalizeNumber(m.groupValues[1], fullText)
                if (isValidDocNumber(cand)) return cand
            }
        }

        return "б-н"
    }

    private fun extractDocumentDate(lines: List<TaggedLine>, match: DocMatch, docNumber: String): String {
        val docType = match.type
        val isContractOrSpec = docType == "Договір" || docType == "Додаткова угода" || docType == "Специфікація"
        val defaultYear = Regex("""\b(202[4-9])\b""").find(lines.joinToString(" ") { it.raw })?.groupValues?.get(1) ?: "2026"

        val anchorWindow = lines.filter {
            it.index in match.anchorLineIndex..(match.anchorLineIndex + 3) &&
            it.role != LineRole.NOISE_CLAUSE &&
            (isContractOrSpec || (it.role != LineRole.REF_CONTRACT && !it.norm.contains("догов")))
        }.joinToString(" ") { it.raw }

        val windowDate = findDatesInText(anchorWindow, defaultYear, docNumber).firstOrNull()
        if (windowDate != null) return windowDate

        val allowedLines = lines.filter {
            it.role == LineRole.CLEAN &&
            (isContractOrSpec || (!it.norm.contains("договор") && !it.norm.contains("договір")))
        }
        val cleanText = allowedLines.joinToString(" ") { it.raw }
        val allDates = findDatesInText(cleanText, defaultYear, docNumber)

        if (allDates.isNotEmpty()) {
            return if (docType.startsWith("Акт")) allDates.last() else allDates.first()
        }

        if (docType.startsWith("Акт")) {
            val monthYearRegex = Regex("""(?iu)([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*[-./\s]{1,3}\s*(202[4-9])""")
            for (m in monthYearRegex.findAll(cleanText)) {
                val monthCode = matchMonth(m.groupValues[1]) ?: continue
                val yearStr = m.groupValues[2]
                val day = LAST_DAY_OF_MONTH[monthCode] ?: "30"
                return "$day.$monthCode.$yearStr"
            }
        }

        return "не_вказано"
    }

    private fun findDatesInText(text: String, defaultYear: String, docNumber: String): List<String> {
        val results = mutableListOf<String>()

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

        val numericRegex = Regex("""(?<!\d)([0-3]?[0-9])\.([0-1]?[0-9])\.(20[2-3][0-9]|[2-3][0-9])(?!\d)""")
        for (match in numericRegex.findAll(text)) {
            val rawCandidate = match.value.replace('.', '-')
            if (rawCandidate == docNumber) continue
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

    private fun extractParentContractInfo(lines: List<TaggedLine>, fullText: String, specDate: String): Pair<String, String> {
        var cNum = ""
        var cDate = ""

        for (i in lines.indices) {
            val tl = lines[i]
            if (tl.role == LineRole.REF_CONTRACT || tl.norm.contains("договор") || tl.norm.contains("договір")) {
                val window = lines.filter { it.index in tl.index..(tl.index + 2) }.joinToString(" ") { it.raw }
                val numMatch = Regex("""(\d{1,5}\s*[-/]\s*\d{1,5}(?:\s*[-/]\s*\d{1,5})?|[а-яa-zіїєґ]{1,4}\s*[-]\s*\d{1,10})""", RegexOption.IGNORE_CASE).find(window)
                if (numMatch != null) {
                    cNum = finalizeNumber(numMatch.groupValues[1], fullText)
                }
                val foundDate = findDatesInText(window, "2026", cNum).firstOrNull()
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
        val norm = normalizeText(word)
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
