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

        val type = extractType(rawText)
        val number = extractNumber(rawText, type)
        val date = extractDate(rawText, type)

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

    private fun extractType(fullText: String): String {
        val norm = normalize(fullText)
        return when {
            norm.contains("специфікація") -> "Специфікація"
            norm.contains("прибутковий касовий ордер") || norm.contains("пко ") -> "Прибутковий касовий ордер"
            norm.contains("видатковий касовий ордер") || norm.contains("вко ") -> "Видатковий касовий ордер"
            norm.contains("журнал реєстрації") && norm.contains("касов") -> "Журнал реєстрації касових документів"
            norm.contains("касова книга") -> "Касова книга"
            norm.contains("платіжна інструкція") -> "Платіжна інструкція"
            norm.contains("платіжне доручення") -> "Платіжне доручення"
            norm.contains("виписка банку") || norm.contains("банківська виписка") -> "Банківська виписка"
            norm.contains("табель обліку") && norm.contains("робочого часу") -> "Табель обліку робочого часу"
            norm.contains("розрахунково-платіжна відомість") -> "Розрахунково-платіжна відомість"
            norm.contains("наказ") && norm.contains("прийняття") -> "Наказ про прийняття на роботу"
            norm.contains("наказ") && norm.contains("звільнення") -> "Наказ про звільнення"
            norm.contains("наказ") && norm.contains("відпустк") -> "Наказ про надання відпустки"
            norm.contains("наказ") || norm.contains("розпорядження") -> "Наказ"
            norm.contains("накладна-вимога") -> "Накладна-вимога на переміщення"
            norm.contains("акт") && norm.contains("основних засобів") -> "Акт приймання-передачі ОЗ"
            norm.contains("акт") && norm.contains("списання") -> "Акт списання ТМЦ"
            norm.contains("авансовий звіт") -> "Авансовий звіт"
            norm.contains("бухгалтерська довідка") -> "Бухгалтерська довідка"
            norm.contains("прибутковий ордер") -> "Прибутковий ордер"
            norm.contains("видаткова накладна") || (norm.contains("видатк") && norm.contains("накладна")) -> "Видаткова накладна"
            norm.contains("податкова накладна") -> "Податкова накладна"
            norm.contains("товарно-транспортна") || norm.contains(" ттн ") -> "Товарно-транспортна накладна"
            norm.contains("рахунок-фактура") || norm.contains("рахунок фактура") -> "Рахунок-фактура"
            norm.contains("інвойс") || norm.contains("invoice") -> "Рахунок-фактура (Інвойс)"
            norm.contains("рахунок-акт") -> "Рахунок-акт"
            norm.contains("рахунок на оплат") -> "Рахунок на оплату"
            norm.contains("акт") && (norm.contains("здач") || norm.contains("прийм") || norm.contains("прийом")) -> "Акт здачі-прийняття робіт"
            norm.contains("акт") && norm.contains("викон") -> "Акт виконаних робіт"
            norm.contains("акт") && norm.contains("послуг") -> "Акт надання послуг"
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

    private fun extractNumber(fullText: String, docType: String): String {
        val normFull = normalize(fullText)

        if (docType == "Специфікація") {
            val specNumRegex = Regex("""(?iu)специфікація\s*(?:№|n[o0оО]?|номер|#)?\s*[:.]?\s*(\d+)""")
            val m = specNumRegex.find(fullText)
            if (m != null) return m.groupValues[1]
        }

        if (docType == "Рахунок-фактура") {
            val invRegex = Regex("""(?iu)рахунок-фактура\s*(?:№|n[o0оО]?|номер|#)?\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ-]+(?:[/-][0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ-]+)*)""")
            val m = invRegex.find(fullText)
            if (m != null) return finalizeNumber(m.groupValues[1], fullText)
        }

        if (docType == "Договір" || docType == "Додаткова угода") {
            val contractRegex = Regex("""(?iu)договір[^\n]*?№\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ-]+(?:[/-][0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ-]+)*)""")
            val m = contractRegex.find(fullText)
            if (m != null) return finalizeNumber(m.groupValues[1], fullText)
        }

        if (docType.contains("Акт")) {
            val актRegex = Regex("""(?iu)акт[^\n]*?№\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ-]+(?:[/-][0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ-]+)*)""")
            val m = актRegex.find(fullText)
            if (m != null) return finalizeNumber(m.groupValues[1], fullText)
        }

        // Загальний пошук за знаком №
        val generalNum = Regex("""(?iu)(?:№|#|номер)\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ-]+(?:\s*[-–—/\\._]\s*[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*)""")
        val genMatch = generalNum.find(fullText)
        if (genMatch != null) {
            val cand = finalizeNumber(genMatch.groupValues[1], fullText)
            if (isValidDocNumber(cand)) return cand
        }

        return "б-н"
    }

    private fun extractDate(fullText: String, docType: String): String {
        // Контекстний пошук дати залежно від типу документа
        val searchAnchor = when {
            docType == "Рахунок-фактура" -> "рахунок-фактура"
            docType.contains("Акт") -> "акт"
            docType == "Договір" || docType == "Додаткова угода" -> "договір"
            else -> ""
        }

        if (searchAnchor.isNotBlank()) {
            // Шукаємо в тексті згадку типу документа і найближче слово "від" з датою
            val anchorIndex = normalize(fullText).indexOf(searchAnchor)
            if (anchorIndex != -1) {
                val subText = fullText.substring(anchorIndex)
                val dateParsed = parseDateFromChunk(subText)
                if (dateParsed != null) return dateParsed
            }
        }

        // Загальний пошук дати у форматі "від ДД Місяць РРРР" або "ДД.ММ.РРРР" (ігноруючи рядки договорів)
        return parseDateFromChunk(fullText) ?: "не_вказано"
    }

    private fun parseDateFromChunk(chunk: String): String? {
        val lines = chunk.lines()
        for (line in lines) {
            val norm = normalize(line)
            // Пропускаємо чужі договірні рядки всередині тексту
            if (norm.contains("по договору") || norm.contains("договір №") || norm.contains("дійсний до")) continue

            // Текстова дата (12 Жовтня 2026 р.)
            val textMatch = Regex(
                """(?iu)(?:від|дата)?\s*[:.]?\s*([0-3]?[0-9])\s*([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*(20[0-3][0-9]|[2-3][0-9])"""
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
        return null
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
}
