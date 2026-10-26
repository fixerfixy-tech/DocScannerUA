package com.fixerfixy.docscanner

import kotlin.math.min

object DocumentParser {

    private data class ScoredCandidate(
        val value: String,
        val score: Int,
        val lineIndex: Int
    )

    private val MONTH_CANONICAL = listOf(
        listOf("січень", "січня", "січні") to 1,
        listOf("лютий", "лютого", "лютому") to 2,
        listOf("березень", "березня", "березні") to 3,
        listOf("квітень", "квітня", "квітні") to 4,
        listOf("травень", "травня", "травні") to 5,
        listOf("червень", "червня", "червні") to 6,
        listOf("липень", "липня", "липні") to 7,
        listOf("серпень", "серпня", "серпні") to 8,
        listOf("вересень", "вересня", "вересні") to 9,
        listOf("жовтень", "жовтня", "жовтні") to 10,
        listOf("листопад", "листопада", "листопаді") to 11,
        listOf("грудень", "грудня", "грудні") to 12
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

        // Спочатку шукаємо сильні пари "№ X від Y", щоб номер і дата підсилювали одне одного
        val docNumber = extractBestNumber(lines, rawText, docType, anchorIdx)
        val docDate = extractBestDate(lines, rawText, docType, anchorIdx, docNumber)

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

    // Нормалізація до української кирилиці (використовується лише для українських слів)
    private fun normalizeCyr(text: String): String {
        return text.lowercase()
            .replace('a', 'а').replace('e', 'е')
            .replace('i', 'і').replace('o', 'о')
            .replace('p', 'р').replace('c', 'с')
            .replace('x', 'х').replace('y', 'у')
            .replace('k', 'к').replace('m', 'м')
            .replace('h', 'н').replace('t', 'т')
            .replace('b', 'в')
    }

    // Контекстна перевірка на рядок круглої печатки або штампа затвердження
    private fun isStampOrSealLine(line: String): Boolean {
        val n = normalizeCyr(line)
        return n.contains("ідентифікаційний код") ||
               n.contains("товариство з обмеженою") ||
               n.contains("приватне акціонерне") ||
               n.contains("акціонерне товариство") ||
               n.contains("фінансових документів") ||
               n.contains("м.п.") ||
               n.contains("затверджую") ||
               Regex("""(?:^|\s)і\.?\s*к\.?\s*\d{6,10}""").containsMatchIn(n)
    }

    // Контекстна перевірка на банківські реквізити, ІПН, ЄДРПОУ, МФО (працює і до, і після normalizeCyr!)
    private fun isBankOrTaxLine(line: String): Boolean {
        val rawLower = line.lowercase()
        val n = normalizeCyr(line)
        if (rawLower.contains("iban") || n.contains("іван") || n.contains("іба")) return true
        // IBAN UA + 15..29 цифр (враховуючи як латинські u/a, так і кириличні у/а)
        if (Regex("""(?i)\b[uу][aа]\d{15,29}\b""").containsMatchIn(line)) return true
        // 14-19 значні банківські рахунки (2600..., 2605..., 2620..., 2650...)
        if (Regex("""\b26[025]\d{11,16}\b""").containsMatchIn(line)) return true

        return n.contains("рахунок отримувача") ||
               n.contains("рахунок постачальника") ||
               n.contains("поточний рахунок") ||
               n.contains("розрахунковий рахунок") ||
               n.contains("особовий рахунок") ||
               n.contains("р/р") ||
               n.contains("п/р") ||
               rawLower.contains("p/p") ||
               n.contains("мфо ") ||
               n.contains("номер свідоцтва") ||
               n.contains("інд. податковий номер")
    }

    // Контекстна перевірка на поштову адресу чи телефон
    private fun isAddressOrPhoneLine(line: String): Boolean {
        val n = normalizeCyr(line)
        return n.contains("вул.") ||
               n.contains("вулиця") ||
               n.contains("бульвар") ||
               n.contains("проспект") ||
               n.contains("ж/м ") ||
               n.contains("корпус") ||
               n.contains("будинок") ||
               n.contains("адреса:") ||
               n.contains("адреса ") ||
               n.contains("телефон") ||
               n.contains("тел.:") ||
               n.contains("тел. ")
    }

    // Пункти договорів (1.1., 2.4.), преамбули, застереження-оферти та граничні дати оплати
    private fun isBodyOrReferenceClause(line: String): Boolean {
        val n = normalizeCyr(line)
        if (Regex("""^\d+\.\d+\.?""").containsMatchIn(n)) return true
        return n.contains("на підставі підписаних") ||
               n.contains("на підставі наведених") ||
               n.contains("актів здачі") ||
               n.contains("у специфікації") ||
               n.contains("у рахунку-фактурі") ||
               n.contains("склали цей акт") ||
               n.contains("уклали цей договір") ||
               n.contains("уклали цю специфікацію") ||
               n.contains("одночасно актом") ||
               n.contains("є актом") ||
               n.contains("передавання-приймання") ||
               n.contains("складання рахунка-акта") ||
               n.contains("були виконані наступні") ||
               n.contains("були проведені такі") ||
               n.contains("претензій одна до одної") ||
               n.contains("претензій по об'єму") ||
               n.contains("претензій не мають") ||
               n.contains("дійсний до") ||
               n.contains("до сплати до") ||
               n.contains("оплатити до") ||
               n.contains("сплатити до") ||
               n.contains("залишок авансу на") ||
               n.contains("погашено борг на")
    }

    // Рядок-посилання на базовий договір у шапці/преамбулі іншого документа
    private fun isContractReferenceLine(line: String): Boolean {
        val n = normalizeCyr(line)
        return n.contains("договір") ||
               n.contains("договору") ||
               n.contains("договором") ||
               n.contains("замовлення")
    }

    private fun detectType(lines: List<String>): String {
        val nonClauseLines = lines.filterNot { isBodyOrReferenceClause(it) }
        val cleanLines = nonClauseLines.filterNot { isBankOrTaxLine(it) }
        val cleanNorm = normalizeCyr(cleanLines.joinToString("\n"))
        val rawLowerAll = lines.joinToString("\n").lowercase()
        val fullNorm = normalizeCyr(rawLowerAll)
        val top10Norm = normalizeCyr(cleanLines.take(10).joinToString("\n"))

        // 1. Специфікація
        val hasSpecHeader = cleanLines.any { line ->
            val n = normalizeCyr(line)
            Regex("""(?:^|\s)специфікаці[яїю](?:\s|№|$)""").containsMatchIn(n) ||
            n.split(' ').any { w -> w.length >= 10 && levenshtein(w.trim(',', ':', '.'), "специфікація") <= 2 }
        }
        if (hasSpecHeader) return "Специфікація"

        // 2. Рахунки та Накладні
        when {
            Regex("""рахун(?:ок|ка)\s*[-/.]?\s*акт""").containsMatchIn(fullNorm) ||
            (top10Norm.contains("рахунок") && fullNorm.contains("одночасно актом")) ->
                return "Рахунок-акт"
            Regex("""рахунок\s*[-/]\s*фактура""").containsMatchIn(cleanNorm) || cleanNorm.contains("рахунок фактура") ->
                return "Рахунок-фактура"
            cleanNorm.contains("інвойс") || rawLowerAll.contains("invoice") ->
                return "Рахунок-фактура (Інвойс)"
            cleanNorm.contains("рахунок на оплат") ->
                return "Рахунок на оплату"
            cleanNorm.contains("видаткова накладна") || (cleanNorm.contains("видатк") && cleanNorm.contains("накладна")) ->
                return "Видаткова накладна"
            cleanNorm.contains("податкова накладна") ->
                return "Податкова накладна"
            cleanNorm.contains("товарно-транспортна") || cleanNorm.contains(" ттн ") ->
                return "Товарно-транспортна накладна"
            cleanNorm.contains("накладна-вимога") || (cleanNorm.contains("накладна") && cleanNorm.contains("внутрішнього переміщення")) ->
                return "Накладна-вимога на переміщення"
            Regex("""(?:^|\s)накладна(?:\s|№|$)""").containsMatchIn(cleanNorm) ->
                return "Накладна"
        }

        // 3. Акти (стійка перевірка навіть при пошкодженні літери "А" у слові "АКТ" печаткою)
        val hasActHeaderLine = cleanLines.any { line ->
            val n = normalizeCyr(line)
            !n.contains("договор") && !n.contains("договір") && !n.contains("рахунок") && !n.contains("рахунка") &&
            (Regex("""(?:^|[^а-яіїєґ])акт(?:[^а-яіїєґ]|$)""").containsMatchIn(n) ||
             Regex("""[а-яіїєґ0-9]?кт\s+(?:надання|виконаних|здачі|приймання)""").containsMatchIn(n) ||
             n.contains("здачі-прийняття"))
        }
        val hasActBodyMarkers = !cleanNorm.contains("рахунок") && (
            fullNorm.contains("склали цей акт") ||
            (fullNorm.contains("від виконавця") && fullNorm.contains("від замовника")) ||
            fullNorm.contains("претензій по об'єму")
        )

        if (hasActHeaderLine || hasActBodyMarkers) {
            return when {
                cleanNorm.contains("основних засобів") || cleanNorm.contains("оз-1") -> "Акт приймання-передачі ОЗ"
                cleanNorm.contains("списання") -> "Акт списання ТМЦ"
                cleanNorm.contains("здач") || cleanNorm.contains("прийм") || cleanNorm.contains("прийом") -> "Акт здачі-прийняття робіт"
                cleanNorm.contains("надання послуг") || cleanNorm.contains("наданих послуг") -> "Акт надання послуг"
                cleanNorm.contains("виконаних робіт") || cleanNorm.contains("виконання робіт") -> "Акт виконаних робіт"
                cleanNorm.contains("послуг") -> "Акт надання послуг"
                cleanNorm.contains("викон") -> "Акт виконаних робіт"
                else -> "Акт"
            }
        }

        // 4. Касові, банківські та кадрові документи
        when {
            cleanNorm.contains("прибутковий касовий ордер") || cleanNorm.contains("пко ") -> return "Прибутковий касовий ордер"
            cleanNorm.contains("видатковий касовий ордер") || cleanNorm.contains("вко ") -> return "Видатковий касовий ордер"
            cleanNorm.contains("журнал реєстрації") && cleanNorm.contains("касов") -> return "Журнал реєстрації касових документів"
            cleanNorm.contains("касова книга") -> return "Касова книга"
            cleanNorm.contains("платіжна інструкція") -> return "Платіжна інструкція"
            cleanNorm.contains("платіжне доручення") -> return "Платіжне доручення"
            cleanNorm.contains("виписка банку") || cleanNorm.contains("банківська виписка") || cleanNorm.contains("виписка по рахунку") -> return "Банківська виписка"
            cleanNorm.contains("табель обліку") && cleanNorm.contains("робочого часу") -> return "Табель обліку робочого часу"
            cleanNorm.contains("розрахунково-платіжна відомість") || cleanNorm.contains("розрахункова відомість") -> return "Розрахунково-платіжна відомість"
            cleanNorm.contains("наказ") && cleanNorm.contains("прийняття") -> return "Наказ про прийняття на роботу"
            cleanNorm.contains("наказ") && cleanNorm.contains("звільнення") -> return "Наказ про звільнення"
            cleanNorm.contains("наказ") && cleanNorm.contains("відпустк") -> return "Наказ про надання відпустки"
            cleanNorm.contains("наказ") || cleanNorm.contains("розпорядження") -> return "Наказ"
            cleanNorm.contains("авансовий звіт") || cleanNorm.contains("звіт про використання коштів") -> return "Авансовий звіт"
            cleanNorm.contains("бухгалтерська довідка") -> return "Бухгалтерська довідка"
            cleanNorm.contains("прибутковий ордер") -> return "Прибутковий ордер"
        }

        // 5. Додаткова угода та Договір
        if (top10Norm.contains("додаткова угода")) return "Додаткова угода"
        val hasRealContractHeader = cleanLines.take(10).any { line ->
            val n = normalizeCyr(line)
            !n.contains("до договор") && !n.contains("по договор") && !n.contains("згідно") && !n.contains("договір:") &&
            Regex("""(?:^|[^а-яіїєґ])догов[іi1l]р(?:[^а-яіїєґ:]|$)""").containsMatchIn(n)
        }
        if (hasRealContractHeader) return "Договір"

        return when {
            cleanNorm.contains("накладна") -> "Накладна"
            cleanNorm.contains("квитанці") -> "Квитанція"
            cleanNorm.contains("чек") -> "Чек"
            cleanNorm.contains("рахунок") -> "Рахунок"
            cleanNorm.contains("договір") && !cleanNorm.contains("договір:") -> "Договір"
            else -> "Документ"
        }
    }

    private fun findTitleAnchorIndex(lines: List<String>, docType: String): Int {
        for (i in lines.indices) {
            if (isBodyOrReferenceClause(lines[i]) || isBankOrTaxLine(lines[i])) continue
            val n = normalizeCyr(lines[i])
            val matched = when {
                docType == "Специфікація" -> n.contains("специфікаці")
                docType == "Рахунок-акт" -> Regex("""рахунок\s*[-/.]?\s*акт""").containsMatchIn(n) ||
                        (n.contains("рахунок") && n.contains("№"))
                docType == "Рахунок-фактура" -> Regex("""рахунок\s*[-/]?\s*фактура""").containsMatchIn(n)
                docType.startsWith("Рахунок") -> n.contains("рахунок") && !n.contains("по рахунку")
                docType.startsWith("Акт") -> !n.contains("договор") && !n.contains("договір") && !n.contains("рахунок") &&
                        (Regex("""(?:^|[^а-яіїєґ])акт(?:[^а-яіїєґ]|$)""").containsMatchIn(n) ||
                         Regex("""[а-яіїєґ0-9]?кт\s+(?:надання|виконаних|здачі|приймання)""").containsMatchIn(n) ||
                         n.contains("здачі-прийняття") ||
                         n.contains("надання послуг"))
                docType == "Договір" -> !n.contains("до договор") && !n.contains("по договор") && !n.contains("згідно") && !n.contains("договір:") &&
                        Regex("""(?:^|[^а-яіїєґ])догов[іi1l]р(?:[^а-яіїєґ:]|$)""").containsMatchIn(n)
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

    // СИСТЕМА БАЛІВ (SCORING ENGINE) ДЛЯ ВИТЯГУВАННЯ НОМЕРА ДОКУМЕНТА
    private fun extractBestNumber(lines: List<String>, fullText: String, docType: String, anchorIdx: Int): String {
        val isContract = docType == "Договір" || docType == "Додаткова угода"

        // Окрема точна логіка для Специфікації (підтримує числа 1..10!)
        if (docType == "Специфікація") {
            for (line in lines) {
                if (isBodyOrReferenceClause(line)) continue
                val m = Regex("""(?iu)специфікаці[яїю]\s*(?:№|#|номер|[nмн][o0оеe°.]*)?\s*[:.]?\s*(\d+[a-zA-Zа-яА-ЯіїєґІЇЄҐ/-]*)""").find(line)
                if (m != null) return finalizeNumber(m.groupValues[1], fullText)
            }
            for (line in lines) {
                if (isBodyOrReferenceClause(line)) continue
                val m = Regex("""(?iu)додаток\s*(?:№|#|номер|[nмн][o0оеe°.]*)?\s*[:.]?\s*(\d+[a-zA-Zа-яА-ЯіїєґІЇЄҐ/-]*)""").find(line)
                if (m != null) return finalizeNumber(m.groupValues[1], fullText)
            }
            return "1"
        }

        val candidates = mutableListOf<ScoredCandidate>()
        val tokenRegex = Regex("""[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:(?:\s*[-/\\_]\s*|\.)[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*""")

        // Шаблон 1 (Найсильніший: +120 балів): Пара "№ [Номер] від [Дата]" в будь-якому чистому рядку документа
        val numVidPairRegex = Regex(
            """(?iu)(?:№|#|номер|\b[nмн][o0оеe°.]*\b)?\s*[:.]?\s*([0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:[-/.][0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*)\s+[вВbB][іІiI1][дД]\s+\S+"""
        )
        for (i in lines.indices) {
            val line = lines[i]
            if (isBodyOrReferenceClause(line) || isBankOrTaxLine(line) || isAddressOrPhoneLine(line)) continue
            if (!isContract && isContractReferenceLine(line)) continue

            for (m in numVidPairRegex.findAll(line)) {
                val cand = finalizeNumber(m.groupValues[1], fullText)
                if (isValidDocNumber(cand, line, isInTitleWindow = (i in anchorIdx..(anchorIdx + 2)))) {
                    var score = 120
                    if (i in anchorIdx..(anchorIdx + 2)) score += 40
                    if (i < 15) score += 15
                    candidates.add(ScoredCandidate(cand, score, i))
                }
            }
        }

        // Шаблон 2 (+100 балів): Токени в рядку заголовка (anchorIdx) та на 1-2 рядки нижче (для "НАКЛАДНА № \n 39743" і "АКТ надання послуг \n № 1349")
        if (anchorIdx in lines.indices) {
            for (offset in 0..2) {
                val idx = anchorIdx + offset
                if (idx !in lines.indices) continue
                val line = lines[idx]
                if (isBodyOrReferenceClause(line) || isBankOrTaxLine(line) || isAddressOrPhoneLine(line)) continue
                if (!isContract && isContractReferenceLine(line)) continue
                if (offset > 0 && isStampOrSealLine(line)) continue

                var tail = line
                if (offset == 0) {
                    val normAnchor = normalizeCyr(line)
                    val titleKey = when {
                        docType == "Рахунок-акт" -> if (normAnchor.contains("акт")) "акт" else "рахунок"
                        docType == "Рахунок-фактура" -> "фактура"
                        docType.startsWith("Рахунок") -> "рахунок"
                        docType.startsWith("Акт") -> when {
                            normAnchor.contains("послуг") -> "послуг"
                            normAnchor.contains("робіт") -> "робіт"
                            normAnchor.contains("акт") -> "акт"
                            else -> ""
                        }
                        docType == "Договір" -> "догов"
                        docType == "Додаткова угода" -> "угода"
                        docType.contains("накладна", true) -> "накладна"
                        else -> ""
                    }
                    val keyPos = if (titleKey.isNotEmpty()) normAnchor.indexOf(titleKey) else -1
                    if (keyPos != -1) {
                        val sub = line.substring(keyPos)
                        val sp = sub.indexOf(' ')
                        tail = if (sp != -1) sub.substring(sp + 1) else ""
                    }
                }

                // Відрізаємо хвіст після слів "від" або "за"
                val stopWordSplit = Regex("""(?iu)\s+(?:[вВbB][іІiI1][дД]|[зЗzZ][аАaA])\s+""").find(tail)
                if (stopWordSplit != null) tail = tail.substring(0, stopWordSplit.range.first)

                for (tok in tokenRegex.findAll(tail)) {
                    val cand = finalizeNumber(tok.value, fullText)
                    if (isValidDocNumber(cand, line, isInTitleWindow = true)) {
                        var score = if (offset == 0) 110 else (95 - offset * 10)
                        if (line.contains("№") || line.contains("#")) score += 20
                        if (cand.contains('-') || cand.length >= 3) score += 10
                        candidates.add(ScoredCandidate(cand, score, idx))
                    }
                }
            }
        }

        // Шаблон 3 (+70 балів): Складені або префіксні номери по чистих рядках документа
        val compoundRegex = Regex("""(?iu)\b([а-яa-zіїєґ]{1,4}\s*[-_]\s*0*\d{2,10}|\d{10,20}\.\d{1,2}\.\d{2,4}|\d{1,5}\s*[-/]\s*\d{1,5}(?:\s*[-/]\s*\d{1,5})?)\b""")
        for (i in lines.indices) {
            val line = lines[i]
            if (isBodyOrReferenceClause(line) || isBankOrTaxLine(line) || isAddressOrPhoneLine(line) || isStampOrSealLine(line)) continue
            if (!isContract && isContractReferenceLine(line)) continue

            for (m in compoundRegex.findAll(line)) {
                val cand = finalizeNumber(m.groupValues[1], fullText)
                if (isValidDocNumber(cand, line, isInTitleWindow = false)) {
                    var score = 70
                    if (i < 15) score += 15
                    candidates.add(ScoredCandidate(cand, score, i))
                }
            }
        }

        return candidates.maxByOrNull { it.score }?.value ?: "б-н"
    }

    // СИСТЕМА БАЛІВ (SCORING ENGINE) ДЛЯ ВИТЯГУВАННЯ ДАТИ ДОКУМЕНТА
    private fun extractBestDate(
        lines: List<String>,
        fullText: String,
        docType: String,
        anchorIdx: Int,
        docNumber: String
    ): String {
        val isContractOrSpec = docType == "Договір" || docType == "Додаткова угода" || docType == "Специфікація"
        val defaultYear = Regex("""\b(20[1-3]\d)\b""").find(fullText)?.groupValues?.get(1) ?: "2026"

        val rawCandidates = mutableListOf<ScoredCandidate>()

        for (i in lines.indices) {
            val line = lines[i]
            if (isBodyOrReferenceClause(line) || isBankOrTaxLine(line)) continue
            if (!isContractOrSpec && isContractReferenceLine(line)) continue

            val datesInLine = findAllDatesInText(line, defaultYear, docNumber)
            if (datesInLine.isEmpty()) continue

            val norm = normalizeCyr(line)
            for (d in datesInLine) {
                var score = 50

                // Бонус за близькість до заголовка документа (ЗОНА А)
                if (i in anchorIdx..(anchorIdx + 3)) {
                    score += 80
                } else if ((docType == "Специфікація" || docType.contains("накладна", true)) && i in (anchorIdx - 3)..anchorIdx) {
                    // У Специфікації та Накладній дата часто стоїть на 1-3 рядки ВИЩЕ від заголовка
                    score += 75
                }

                // Бонус за явні маркери дати ("від ...", "Дата: ...")
                if (Regex("""(?iu)\b(?:від|дата)\b""").containsMatchIn(norm)) {
                    score += 35
                }

                // Бонус для Актів за дату підписання у підвалі
                if (docType.startsWith("Акт") && i >= lines.size / 2) {
                    score += 40
                }

                rawCandidates.add(ScoredCandidate(d, score, i))
            }
        }

        if (rawCandidates.isNotEmpty()) {
            // Додаємо бонус +40 балів, якщо та сама дата зустрічається в документі кілька разів (напр. у шапці і в підвалі Акта)
            val freqMap = rawCandidates.groupingBy { it.value }.eachCount()
            val boosted = rawCandidates.map { cand ->
                val count = freqMap[cand.value] ?: 1
                val dupBonus = if (count > 1) 40 else 0
                cand.copy(score = cand.score + dupBonus)
            }
            return boosted.maxByOrNull { it.score }!!.value
        }

        // Резервна реконструкція для Актів, якщо мокра печатка перекрила число дня у підвалі (беремо останній день місяця послуг)
        if (docType.startsWith("Акт")) {
            val cleanText = lines.filterNot {
                isBodyOrReferenceClause(it) || isBankOrTaxLine(it) || isContractReferenceLine(it)
            }.joinToString(" ")

            val monthYearRegex = Regex("""(?iu)([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*[-./\s]{1,3}\s*(20[1-3]\d)""")
            for (m in monthYearRegex.findAll(cleanText)) {
                val monthNum = matchMonthFuzzy(m.groupValues[1]) ?: continue
                val yearNum = m.groupValues[2].toIntOrNull() ?: 2026
                val lastDay = getLastDayOfMonth(monthNum, yearNum)
                val mStr = monthNum.toString().padStart(2, '0')
                return "${lastDay.toString().padStart(2, '0')}.$mStr.$yearNum"
            }
        }

        return "не_вказано"
    }

    private fun findAllDatesInText(text: String, defaultYear: String, docNumber: String): List<String> {
        val results = mutableListOf<String>()

        // 1. Словесно-цифрові дати: «24» серпня 2026, 30-Вересня 2026 р., від 12 Жовтня 2026 р., від 31 серпня 2026 р.
        val monthYearAnchor = Regex(
            """(?iu)([a-zA-Zа-яА-ЯіїєґІЇЄҐ']{3,12})\s*[-./\s]{1,3}\s*(20[1-3][0-9ОоOб]|[1-3][0-9ОоOб])(?!\d)"""
        )
        for (match in monthYearAnchor.findAll(text)) {
            val monthNum = matchMonthFuzzy(match.groupValues[1]) ?: continue
            var yearStr = fixOcrDigits(match.groupValues[2])
            if (yearStr.length == 2) yearStr = "20$yearStr"
            val yearNum = yearStr.toIntOrNull() ?: defaultYear.toInt()

            val prefixStart = (match.range.first - 14).coerceAtLeast(0)
            val prefix = text.substring(prefixStart, match.range.first)
            val day = extractDayFromPrefix(prefix)
            val maxDay = getLastDayOfMonth(monthNum, yearNum)
            if (day != null && day in 1..maxDay) {
                val mStr = monthNum.toString().padStart(2, '0')
                results.add("${day.toString().padStart(2, '0')}.$mStr.$yearNum")
            }
        }

        // 2. Числові дати через крапку: ДД.ММ.РРРР або ДД.ММ.РР
        val numericRegex = Regex("""(?<!\d)([0-3]?[0-9])\.([0-1]?[0-9])\.(20[1-3][0-9]|[1-3][0-9])(?!\d)""")
        for (match in numericRegex.findAll(text)) {
            val rawMatch = match.value
            val asHyphen = rawMatch.replace('.', '-')
            // Перехресна перевірка: не беремо фрагмент довгого номера документа (напр. 1240000000989370.8.2026) за дату
            if (asHyphen == docNumber || (docNumber.isNotEmpty() && docNumber.contains(rawMatch))) continue

            val d = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            var yStr = match.groupValues[3]
            if (yStr.length == 2) yStr = "20$yStr"
            val y = yStr.toIntOrNull() ?: 2026

            if (m in 1..12) {
                val maxDay = getLastDayOfMonth(m, y)
                if (d in 1..maxDay) {
                    results.add("${d.toString().padStart(2, '0')}.${m.toString().padStart(2, '0')}.$y")
                }
            }
        }

        return results
    }

    private fun extractParentContractForSpec(lines: List<String>, fullText: String, specDate: String): Pair<String, String> {
        var cNum = ""
        var cDate = ""

        for (i in lines.indices) {
            val norm = normalizeCyr(lines[i])
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
            cDate = if (specDate.isNotBlank() && specDate != "не_вказано") specDate else "не_вказано"
        }
        return Pair(cNum, cDate)
    }

    // Контекстна перевірка кандидата в номери БЕЗ жодного хардкоду конкретних ЄДРПОУ чи індексів!
    private fun isValidDocNumber(cand: String, sourceLine: String, isInTitleWindow: Boolean): Boolean {
        if (cand.isBlank() || cand == "Б-Н") return false
        if (!cand.any { it.isDigit() }) return false

        // Відсікаємо чисті роки (2024..2029) та дати ДД.ММ.РРРР
        if (cand.matches(Regex("""^20[1-3]\d$"""))) return false
        if (cand.matches(Regex("""^\d{2}[.-]\d{2}[.-]20[1-3]\d$"""))) return false

        // Відсікаємо IBAN та 14-19 значні банківські рахунки (2600..., 2605..., 2620..., 2650...)
        if (cand.matches(Regex("""^[UУ][AА]\d{15,29}$"""))) return false
        if (cand.matches(Regex("""^26[025]\d{11,16}$"""))) return false

        val normLine = normalizeCyr(sourceLine)

        // Якщо це коротке число 1..10: дозволяємо його ТІЛЬКИ у вікні заголовка або після слів Акт/Договір/Наказ/Рахунок,
        // але блокуємо, якщо воно стоїть у рядку круглої печатки (як-от штамп "№8" на печатці Укртелекому)
        if (cand.matches(Regex("""^\d{1,2}$"""))) {
            if (isStampOrSealLine(sourceLine)) return false
            if (!isInTitleWindow && !Regex("""(?:акт|догов|наказ|рахунок|накладна|угода|специфікац)""").containsMatchIn(normLine)) {
                return false
            }
        }

        // Контекстне відсікання ЄДРПОУ (8 цифр), ІПН (10 або 12 цифр), МФО (6 цифр) у рядках реквізитів
        if (cand.matches(Regex("""^\d{6,12}$"""))) {
            if (normLine.contains("єдрпоу") || normLine.contains("іпн") || normLine.contains("мфо") ||
                normLine.contains("код ") || normLine.contains(" свідоцтва") || isStampOrSealLine(sourceLine)
            ) {
                return false
            }
        }

        // Контекстне відсікання 5-значних поштових індексів, якщо в рядку є місто/область/вулиця
        if (cand.matches(Regex("""^\d{5}$"""))) {
            if (normLine.contains("м.") || normLine.contains("місто") || normLine.contains("обл") ||
                normLine.contains("вул") || normLine.contains("буд") || normLine.contains("укрсиббанк") ||
                normLine.contains("банк")
            ) {
                return false
            }
        }

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

    // Нечітке розпізнавання місяця через основи та відстань Левенштейна (допуск 1-2 символи)
    private fun matchMonthFuzzy(word: String): Int? {
        val norm = normalizeCyr(word).trim('.', ',', '-', '«', '»', '"', '\'')
        if (norm.length < 3) return null

        // 1. Точний збіг або входження основи
        for ((forms, monthNum) in MONTH_CANONICAL) {
            val stem = forms[0].take(4)
            if (norm.startsWith(stem) || forms.any { norm == it }) return monthNum
        }

        // 2. Нечітке порівняння за відстанню Левенштейна (якщо OCR зробив 1-2 помилки всередині слова, напр. "српня", "всресня")
        if (norm.length >= 5) {
            var bestMonth: Int? = null
            var bestDist = 3
            for ((forms, monthNum) in MONTH_CANONICAL) {
                for (form in forms) {
                    val d = levenshtein(norm, form)
                    val maxAllowed = if (form.length >= 7) 2 else 1
                    if (d <= maxAllowed && d < bestDist) {
                        bestDist = d
                        bestMonth = monthNum
                    }
                }
            }
            if (bestMonth != null) return bestMonth
        }

        return null
    }

    private fun getLastDayOfMonth(month: Int, year: Int): Int {
        return when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> 31
        }
    }

    private fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLen = lhs.length
        val rhsLen = rhs.length
        var cost = IntArray(lhsLen + 1) { it }
        var newCost = IntArray(lhsLen + 1)

        for (i in 1..rhsLen) {
            newCost[0] = i
            for (j in 1..lhsLen) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = min(min(costInsert, costDelete), costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLen]
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
