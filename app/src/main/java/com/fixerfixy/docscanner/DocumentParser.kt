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
            .map { it.replace('\u00A0', ' ').replace('–', '-').replace('—', '-').trim() }
            .filter { it.isNotBlank() }

        val docType = extractType(lines, rawText)
        val docNumber = extractNumber(lines, rawText, docType)
        val docDate = extractDate(lines, rawText, docType)

        var parentContractNum = ""
        var parentContractDate = ""
        if (docType == "Специфікація" || docType == "Додаткова угода") {
            val parentInfo = extractParentContractInfo(lines, rawText, docDate)
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

    private fun extractType(lines: List<String>, fullText: String): String {
        val fullNorm = normalizeText(fullText)
        val headerNorm = normalizeText(lines.take(12).joinToString(" "))

        // Груба перевірка за ключовими словами у всьому тексті (щоб уникнути статусу "Документ")
        when {
            fullNorm.contains("специфікація") || fullNorm.contains("специфікацію") -> return "Специфікація"
            fullNorm.contains("додаткова угода") -> return "Додаткова угода"
            fullNorm.contains("рахунок-фактура") || fullNorm.contains("рахунок фактура") -> return "Рахунок-фактура"
            fullNorm.contains("інвойс") || fullNorm.contains("invoice") -> return "Рахунок-фактура (Інвойс)"
            fullNorm.contains("видаткова накладна") || fullNorm.contains("видатк") && fullNorm.contains("накладна") -> return "Видаткова накладна"
            fullNorm.contains("податкова накладна") -> return "Податкова накладна"
            fullNorm.contains("товарно-транспортна") || fullNorm.contains(" ттн ") -> return "Товарно-транспортна накладна"
        }

        if (fullNorm.contains("акт ") || fullNorm.contains("\nакт")) {
            return when {
                fullNorm.contains("основних засобів") || fullNorm.contains("оз-1") -> "Акт приймання-передачі ОЗ"
                fullNorm.contains("списання") -> "Акт списання ТМЦ"
                fullNorm.contains("викон") -> "Акт виконаних робіт"
                fullNorm.contains("послуг") -> "Акт надання послуг"
                else -> "Акт здачі-прийняття робіт"
            }
        }

        if (headerNorm.contains("договір") && !headerNorm.contains("по договору") && !headerNorm.contains("до договору")) {
            return "Договір"
        }

        return "Документ"
    }

    private fun extractNumber(lines: List<String>, fullText: String, docType: String): String {
        // 1. Специфікація
        if (docType == "Специфікація") {
            for (line in lines) {
                val m = Regex("""(?iu)специфікація\s*(?:№|#|номер|[nмн][o0оеe°.]*)?\s*[:.]?\s*(\d+)""").find(line)
                if (m != null) return m.groupValues[1]
            }
            return "1"
        }

        // 2. Прямий пошук за назвою документа (Акт № ..., Рахунок № ..., Договір № ...)
        val keyword = when {
            docType.startsWith("Рахунок") -> "рахунок"
            docType.startsWith("Акт") -> "акт"
            docType == "Договір" -> "договір"
            docType == "Додаткова угода" -> "додаткова угода"
            else -> ""
        }

        val tokenRegex = Regex("""[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-/\\._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*""")

        if (keyword.isNotEmpty()) {
            for (line in lines) {
                val norm = normalizeText(line)
                if (norm.contains("по договору") || norm.contains("до договору")) continue
                val idx = norm.indexOf(keyword)
                if (idx != -1) {
                    var tail = line.substring(idx + keyword.length)
                    val vidSplit = Regex("""(?iu)\s+[вВbB][іІiI1][дД]\s+""").find(tail)
                    if (vidSplit != null) tail = tail.substring(0, vidSplit.range.first)

                    for (tok in tokenRegex.findAll(tail)) {
                        val cand = finalizeNumber(tok.value, fullText)
                        if (isValidDocNumber(cand)) return cand
                    }
                }
            }
        }

        // 3. Універсальний пошук після знака №
        val numSignRegex = Regex("""(?iu)(?:№|#|номер)\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:\s*[-–—/\\._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*)""")
        for (line in lines) {
            val norm = normalizeText(line)
            if (norm.contains("єдрпоу") || norm.contains("іпн")) continue
            val m = numSignRegex.find(line)
            if (m != null) {
                val cand = finalizeNumber(m.groupValues[1], fullText)
                if (isValidDocNumber(cand)) return cand
            }
        }

        // 4. Пошук префіксних номерів на кшталт ЛТ-0000272
        val prefixRegex = Regex("""(?iu)\b([а-яa-zіїєґ]{1,4}\s*[-_]\s*0*\d{3,10})\b""")
        for (line in lines) {
            val m = prefixRegex.find(line)
            if (m != null) {
                val cand = finalizeNumber(m.groupValues[1], fullText)
                if (isValidDocNumber(cand)) return cand
            }
        }

        return "б-н"
    }

    private fun extractDate(lines: List<String>, fullText: String, docType: String): String {
        val defaultYear = Regex("""\b(202[4-9])\b""").find(fullText)?.groupValues?.get(1) ?: "2026"

        // Для актів перевіряємо спочатку підвал документа (останні 10 рядків), бо там дата підпису
        val searchLines = if (docType.startsWith("Акт")) {
            lines.takeLast(12) + lines.take(15) // спочатку підвал, потім шапка
        } else {
            lines
        }

        for (line in searchLines) {
            val norm = normalizeText(line)
            if (norm.contains("по договору") || norm.contains("до договору") || norm.contains("дійсний до")) continue

            // Текстова дата (30-Вересня 2026 р. або 12 Жовтня 2026 або «24» серпня 2026)
            val textMatch = Regex(
                """(?iu)([0-3]?[0-9])\s*[-–—./\s]*([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*[-–—./\s]*(20[0-3][0-9]|[2-3][0-9])"""
            ).find(line)
            if (textMatch != null) {
                val d = fixOcrDigits(textMatch.groupValues[1]).toIntOrNull() ?: 0
                val m = matchMonth(textMatch.groupValues[2])
                var y = fixOcrDigits(textMatch.groupValues[3])
                if (y.length == 2) y = "20$y"
                if (d in 1..31 && m != null) {
                    return "${d.toString().padStart(2, '0')}.$m.$y"
                }
            }

            // Числова дата ДД.ММ.РРРР
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

        // Резерв для Актів за місяцем у тексті
        if (docType.startsWith("Акт")) {
            val monthYearRegex = Regex("""(?iu)([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*(202[4-9])""")
            for (m in monthYearRegex.findAll(fullText)) {
                val monthCode = matchMonth(m.groupValues[1]) ?: continue
                val yearStr = m.groupValues[2]
                val day = LAST_DAY_OF_MONTH[monthCode] ?: "30"
                return "$day.$monthCode.$yearStr"
            }
        }

        return "не_вказано"
    }

    private fun extractParentContractInfo(lines: List<String>, fullText: String, specDate: String): Pair<String, String> {
        var cNum = ""
        var cDate = ""
        for (line in lines) {
            val norm = normalizeText(line)
            if (norm.contains("договор") || norm.contains("договір")) {
                val numMatch = Regex("""(\d{1,5}\s*[-/]\s*\d{1,5}(?:\s*[-/]\s*\d{1,5})?|[а-яa-zіїєґ]{1,4}\s*[-]\s*\d{1,10})""", RegexOption.IGNORE_CASE).find(line)
                if (numMatch != null) {
                    cNum = finalizeNumber(numMatch.groupValues[1], fullText)
                }
                val textMatch = Regex("""(?iu)([0-3]?[0-9])\s*([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*(20[0-3][0-9]|[2-3][0-9])""").find(line)
                if (textMatch != null) {
                    val d = fixOcrDigits(textMatch.groupValues[1]).padStart(2, '0')
                    val m = matchMonth(textMatch.groupValues[2]) ?: "08"
                    var y = fixOcrDigits(textMatch.groupValues[3])
                    if (y.length == 2) y = "20$y"
                    cDate = "$d.$m.$y"
                }
                if (cNum.isNotEmpty()) break
            }
        }
        if (cDate.isEmpty()) cDate = if (specDate != "не_вказано") specDate else "24.08.2026"
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
}
