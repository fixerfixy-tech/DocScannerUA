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

    private const val TYPE_PAYOUT_STATEMENT = "Відомість розподілу виплат"
    private const val TYPE_PAYROLL_CALC = "Детальний розрахунок нарахувань"
    private const val TYPE_SALARY_LIST = "Список зарплати в банк"
    private const val TYPE_RECON = "Акт звірки"
    private const val TYPE_RECON_CONTRACT = "Акт звірки за договором"
    private const val TYPE_INVENTORY = "Опис документів"
    private const val TYPE_CHARTER = "Статут (затверджено протоколом)"
    private const val TYPE_PROTOCOL = "Протокол"
    private const val TYPE_PROTOCOL_GM = "Протокол загальних зборів"
    private const val TYPE_EXTRACT = "Виписка з ЄДР"
    private const val TYPE_TAX_NOTICE = "Податкове повідомлення-рішення"
    private const val TYPE_TAX_ACT = "Акт перевірки ДПС"

    /** Номер банківської відомості (ПриватБанк): ррммдд + 2 літери + 12 цифр, напр. 260107PB000052979023. */
    private val PAYOUT_NUMBER_REGEX = Regex(
        """(?<![0-9A-Za-zА-Яа-яІіЇїЄєҐґ])([0-9OОoоЗзбІіIl]{6})([A-Za-zА-Яа-я][A-Za-zА-Яа-я8])([0-9OОoоЗзбІіIl]{10,14})(?![0-9A-Za-zА-Яа-яІіЇїЄєҐґ])"""
    )

    fun parse(rawText: String): ParsedDocument {
        return parseWithConfidence(rawText).document
    }

    fun parseWithConfidence(rawText: String): ParseOutcome {
        if (rawText.isBlank() || rawText.startsWith("[")) {
            val emptyDoc = ParsedDocument(
                type = "Документ",
                number = "б-н",
                date = "не_вказано",
                rawOcrText = rawText
            )
            return ParseOutcome(
                document = emptyDoc,
                confidence = 0,
                typeScore = 0,
                numberScore = 0,
                dateScore = 0
            )
        }

        val lines = rawText.lines()
            .map { preCleanLine(it) }
            .filter { it.isNotBlank() }

        val docType = detectType(lines)
        val typeScore = if (docType != "Документ") 100 else 10
        val anchorIdx = findTitleAnchorIndex(lines, docType)

        val numberCand = extractBestNumberCandidate(lines, rawText, docType, anchorIdx)
        val docNumber = numberCand.value
        val numberScore = numberCand.score

        val dateCand = extractBestDateCandidate(lines, rawText, docType, anchorIdx, docNumber)
        val docDate = dateCand.value
        val dateScore = dateCand.score

        var parentContractNum = ""
        var parentContractDate = ""
        if (docType == "Специфікація" || docType == "Додаткова угода") {
            val parentInfo = extractParentContractForSpec(lines, rawText, docDate)
            parentContractNum = parentInfo.first
            parentContractDate = parentInfo.second
        }

        val parsedDoc = ParsedDocument(
            type = docType,
            number = docNumber,
            date = docDate,
            rawOcrText = rawText,
            contractNumber = parentContractNum,
            contractDate = parentContractDate
        )

        val totalConfidence = typeScore + numberScore + dateScore

        return ParseOutcome(
            document = parsedDoc,
            confidence = totalConfidence,
            typeScore = typeScore,
            numberScore = numberScore,
            dateScore = dateScore
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

    private fun isBankOrTaxLine(line: String): Boolean {
        val rawLower = line.lowercase()
        val n = normalizeCyr(line)
        if (rawLower.contains("iban") || n.contains("іван") || n.contains("іба")) return true
        if (Regex("""(?i)\b[uу][aа]\d{15,29}\b""").containsMatchIn(line)) return true
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

    private fun isBodyOrReferenceClause(line: String): Boolean {
        val n = normalizeCyr(line)
        // Нумерація пунктів "1.1." / "2.3.1 "; дата на початку рядка ("27.01.2022 № 02-к") пунктом не є
        if (Regex("""^\d{1,2}[./]\d{1,2}[./]\d{2}(?:\d{2})?\s*(?:р\.?|року)?$""").containsMatchIn(n)) return false // рядок із самою датою
        if (Regex("""^\d{1,2}(?:\.\d{1,2})+(?:\.|\s|$)(?!\d)""").containsMatchIn(n)) return true
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
               n.contains("згідно р/ф") ||
               n.contains("згідно рахунку") ||
               n.contains("залишок авансу на") ||
               n.contains("погашено борг на")
    }

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

        val hasSpecHeader = cleanLines.any { line ->
            val n = normalizeCyr(line)
            Regex("""(?:^|\s)специфікаці[яїю](?:\s|№|$)""").containsMatchIn(n) ||
            n.split(' ').any { w -> w.length >= 10 && levenshtein(w.trim(',', ':', '.'), "специфікація") <= 2 }
        }
        if (hasSpecHeader) return "Специфікація"

        // Зарплатні документи за заголовком; нечітке порівняння витримує помилки OCR
        val titleNorm = cleanLines.take(20).map { normalizeCyr(it) }
        when {
            titleNorm.any { fuzzyContains(it, "відомість розподілу виплат") } -> return TYPE_PAYOUT_STATEMENT
            titleNorm.any { fuzzyContains(it, "детальний розрахунок нарахувань") } -> return TYPE_PAYROLL_CALC
            titleNorm.any { fuzzyContains(it, "список зарплати") } -> return TYPE_SALARY_LIST
        }

        detectRegistryType(cleanLines, titleNorm)?.let { return it }

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

    private fun isWord(lineNorm: String, word: String): Boolean {
        val t = lineNorm.trim(' ', '.', ',', ':', ';', '"', '«', '»')
        return t.length in (word.length - 1)..(word.length + 1) && levenshtein(t, word) <= 1
    }

    /** Реєстраційні, звітні та податкові документи за заголовком (перші рядки скану). */
    private fun detectRegistryType(cleanLines: List<String>, titleNorm: List<String>): String? {
        val top8 = titleNorm.take(8)

        // Статут: окремий рядок "СТАТУТ" (раніше за протокол: у шапці статуту є "ЗАТВЕРДЖЕНО Протоколом")
        if (titleNorm.any { isWord(it, "статут") }) return TYPE_CHARTER

        if (titleNorm.any { fuzzyContains(it, "акт звірки") || fuzzyContains(it, "акт звіряння") || fuzzyContains(it, "акт звірення") }) {
            return if (findReconContract(cleanLines) != null) TYPE_RECON_CONTRACT else TYPE_RECON
        }

        if (titleNorm.any { fuzzyContains(it, "опис документів") }) return TYPE_INVENTORY

        if (titleNorm.any { fuzzyContains(it, "податкове повідомлення-рішення") || fuzzyContains(it, "податкове повідомлення рішення") }) {
            return TYPE_TAX_NOTICE
        }

        if (titleNorm.any { fuzzyContains(it, "виписка") } && titleNorm.any { fuzzyContains(it, "державного реєстру") }) {
            return TYPE_EXTRACT
        }

        val top25 = titleNorm.take(25)
        if (top25.any { val c = it.replace(" ", ""); c == "акт" || c.startsWith("акт№") } &&
            top25.any { it.contains("перевірк") } && top25.any { fuzzyContains(it, "про результати") }) {
            return TYPE_TAX_ACT
        }

        if (top8.any { Regex("""^\W*протокол(?:\s|№|$)""").containsMatchIn(it) }) {
            return if (top8.any { fuzzyContains(it, "загальних зборів") }) TYPE_PROTOCOL_GM else TYPE_PROTOCOL
        }

        val orderIdx = titleNorm.take(12).indexOfFirst { Regex("""^\W*наказ(?:\s|№|$)""").containsMatchIn(it) }
        if (orderIdx >= 0) {
            val head = titleNorm.subList(orderIdx, minOf(titleNorm.size, orderIdx + 2)).joinToString(" ")
            return when {
                head.contains("прийняття") -> "Наказ про прийняття на роботу"
                head.contains("звільнення") -> "Наказ про звільнення"
                head.contains("відпустк") -> "Наказ про надання відпустки"
                else -> "Наказ"
            }
        }
        return null
    }

    private fun findTitleAnchorIndex(lines: List<String>, docType: String): Int {
        for (i in lines.indices) {
            if (isBodyOrReferenceClause(lines[i]) || isBankOrTaxLine(lines[i])) continue
            val n = normalizeCyr(lines[i])
            val matched = when {
                docType == "Специфікація" -> n.contains("специфікаці")
                docType == TYPE_PAYOUT_STATEMENT -> fuzzyContains(n, "відомість розподілу виплат")
                docType == TYPE_PAYROLL_CALC -> fuzzyContains(n, "детальний розрахунок нарахувань")
                docType == TYPE_SALARY_LIST -> fuzzyContains(n, "список зарплати")
                docType.startsWith(TYPE_PROTOCOL) -> Regex("""^\W*протокол""").containsMatchIn(n)
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

    private fun extractBestNumberCandidate(
        lines: List<String>,
        fullText: String,
        docType: String,
        anchorIdx: Int
    ): ScoredCandidate {
        val isContract = docType == "Договір" || docType == "Додаткова угода"

        if (docType == "Специфікація") {
            for (i in lines.indices) {
                val line = lines[i]
                if (isBodyOrReferenceClause(line)) continue
                val m = Regex("""(?iu)специфікаці[яїю]\s*(?:№|#|номер|[nмн][o0оеe°.]*)?\s*[:.]?\s*(\d+[a-zA-Zа-яА-ЯіїєґІЇЄҐ/-]*)""").find(line)
                if (m != null) return ScoredCandidate(finalizeNumber(m.groupValues[1], fullText), 130, i)
            }
            for (i in lines.indices) {
                val line = lines[i]
                if (isBodyOrReferenceClause(line)) continue
                val m = Regex("""(?iu)додаток\s*(?:№|#|номер|[nмн][o0оеe°.]*)?\s*[:.]?\s*(\d+[a-zA-Zа-яА-ЯіїєґІЇЄҐ/-]*)""").find(line)
                if (m != null) return ScoredCandidate(finalizeNumber(m.groupValues[1], fullText), 110, i)
            }
            return ScoredCandidate("1", 50, anchorIdx)
        }

        val special = specialFields(docType, lines)
        if (special != null) {
            if (special.number != null) return ScoredCandidate(special.number, 140, -1)
            // у цих документів власного номера може не бути: "б-н" - чесний результат, не провал розпізнавання
            if (docType in NO_GENERIC_NUMBER) return ScoredCandidate("б-н", 60, -1)
        }

        if (docType == TYPE_PAYOUT_STATEMENT) {
            // Лише спеціальний номер; загальний пошук тут давав сміття (поштовий індекс, ІПН тощо)
            val found = findPayoutNumber(lines)
            return if (found != null) ScoredCandidate(found.first, 140, found.second) else ScoredCandidate("б-н", 0, -1)
        }

        val candidates = mutableListOf<ScoredCandidate>()
        val tokenRegex = Regex("""[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+(?:(?:\s*[-/\\_]\s*|\.)[0-9a-zA-Zа-яА-ЯіїєґІЇЄҐ]+)*""")

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
                        docType.startsWith(TYPE_PROTOCOL) -> "протокол"
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

        return candidates.maxByOrNull { it.score } ?: ScoredCandidate("б-н", 0, -1)
    }

    private fun extractBestDateCandidate(
        lines: List<String>,
        fullText: String,
        docType: String,
        anchorIdx: Int,
        docNumber: String
    ): ScoredCandidate {
        val isContractOrSpec = docType == "Договір" || docType == "Додаткова угода" || docType == "Специфікація"
        val defaultYear = Regex("""\b(20[1-3]\d)\b""").find(fullText)?.groupValues?.get(1) ?: "2026"

        specialFields(docType, lines)?.date?.let { return ScoredCandidate(it, 140, -1) }

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

                if (i in anchorIdx..(anchorIdx + 3)) {
                    score += 80
                } else if ((docType == "Специфікація" || docType.contains("накладна", true)) && i in (anchorIdx - 3)..anchorIdx) {
                    score += 75
                }

                if (Regex("""(?iu)\b(?:від|дата)\b""").containsMatchIn(norm)) {
                    score += 35
                }

                // Окремий рядок із самою датою в шапці ("30.09.26"): це дата складання документа
                if (i < 12 && line.length <= 20 && Regex("""^\D{0,3}\d""").containsMatchIn(line)) {
                    score += 40
                }

                if (docType.startsWith("Акт") && i >= lines.size / 2) {
                    score += 40
                }

                rawCandidates.add(ScoredCandidate(d, score, i))
            }
        }

        // Номер банківської відомості містить дату (ррммдд): запасний варіант, якщо в шапці дату не розпізнано
        if (docType == TYPE_PAYOUT_STATEMENT) {
            payoutDateFromNumber(docNumber)?.let { rawCandidates.add(ScoredCandidate(it, 100, -1)) }
        }

        if (rawCandidates.isNotEmpty()) {
            val freqMap = rawCandidates.groupingBy { it.value }.eachCount()
            val boosted = rawCandidates.map { cand ->
                val count = freqMap[cand.value] ?: 1
                val dupBonus = if (count > 1) 40 else 0
                cand.copy(score = cand.score + dupBonus)
            }
            return boosted.maxByOrNull { it.score }!!
        }

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
                return ScoredCandidate("${lastDay.toString().padStart(2, '0')}.$mStr.$yearNum", 45, anchorIdx)
            }
        }

        return ScoredCandidate("не_вказано", 0, -1)
    }

    private fun findAllDatesInText(text: String, defaultYear: String, docNumber: String): List<String> {
        val results = mutableListOf<String>()

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
            if (isHalfMonthPrefix(prefix)) continue
            val day = extractDayFromPrefix(prefix)
            val maxDay = getLastDayOfMonth(monthNum, yearNum)
            if (day != null && day in 1..maxDay) {
                val mStr = monthNum.toString().padStart(2, '0')
                results.add("${day.toString().padStart(2, '0')}.$mStr.$yearNum")
            }
        }

        // Роздільник дати: крапка або коса риска, в усій даті однаковий (група 2 повторюється як \2)
        val numericRegex = Regex("""(?<!\d)([0-3]?[0-9])([./])([0-1]?[0-9])\2(20[1-3][0-9]|[1-3][0-9])(?!\d)""")
        for (match in numericRegex.findAll(text)) {
            val rawMatch = match.value
            val asHyphen = rawMatch.replace('.', '-').replace('/', '-')
            if (asHyphen == docNumber || (docNumber.isNotEmpty() && docNumber.contains(rawMatch))) continue

            val d = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[3].toIntOrNull() ?: 0
            var yStr = match.groupValues[4]
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

    private fun isValidDocNumber(cand: String, sourceLine: String, isInTitleWindow: Boolean): Boolean {
        if (cand.isBlank() || cand == "Б-Н") return false
        if (!cand.any { it.isDigit() }) return false

        if (cand.matches(Regex("""^20[1-3]\d$"""))) return false
        if (cand.matches(Regex("""^\d{2}[.-]\d{2}[.-]20[1-3]\d$"""))) return false

        if (cand.matches(Regex("""^[UУ][AА]\d{15,29}$"""))) return false
        if (cand.matches(Regex("""^26[025]\d{11,16}$"""))) return false

        val normLine = normalizeCyr(sourceLine)

        if (cand.matches(Regex("""^\d{1,2}$"""))) {
            if (isStampOrSealLine(sourceLine)) return false
            if (!isInTitleWindow && !Regex("""(?:акт|догов|наказ|рахунок|накладна|угода|специфікац)""").containsMatchIn(normLine)) {
                return false
            }
        }

        if (cand.matches(Regex("""^\d{6,12}$"""))) {
            if (normLine.contains("єдрпоу") || normLine.contains("іпн") || normLine.contains("мфо") ||
                normLine.contains("код ") || normLine.contains(" свідоцтва") || isStampOrSealLine(sourceLine)
            ) {
                return false
            }
        }

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

    private val NO_GENERIC_NUMBER = setOf(TYPE_RECON, TYPE_RECON_CONTRACT, TYPE_INVENTORY, TYPE_CHARTER, TYPE_EXTRACT)

    private class SpecialFields(val number: String?, val date: String?)

    /** Номер і дата за правилами конкретного типу; null в полі = не знайдено (далі загальний пошук або "б-н"). */
    private fun specialFields(docType: String, lines: List<String>): SpecialFields? {
        val joined = lines.joinToString(" ")
        val jn = normalizeCyr(joined)
        return when (docType) {
            TYPE_RECON, TYPE_RECON_CONTRACT -> SpecialFields(findReconContract(lines), parsePeriod(jn))
            TYPE_INVENTORY -> SpecialFields(
                findInventoryNumber(jn),
                Regex("""дата\s+формуванн\S*\s+опису\D{0,6}(.{0,40})""").find(jn)
                    ?.let { findAllDatesInText(it.groupValues[1], "2026", "").firstOrNull() }
            )
            TYPE_CHARTER -> findCharterApproval(joined, jn)
            TYPE_TAX_NOTICE -> {
                val m = Regex("""від\s+(\d{1,2}[./]\d{1,2}[./]\d{4})\s*(?:р\.?|року)?\s*(?:№|n[о0]?\.?|#)\s*([0-9оОo]{6,12})(?![0-9])""").find(jn)
                if (m == null) null else SpecialFields(
                    digitsFromOcr(m.groupValues[2]),
                    findAllDatesInText(m.groupValues[1], "2026", "").firstOrNull()
                )
            }
            TYPE_TAX_ACT -> {
                val m = Regex("""(\d{3,5}\s*/\s*\d{2}\s*-\s*\d{2}\s*-\s*\d{2}\s*-\s*\d{2})(?:\s*/\s*\d+)?(?:\s+від\s+(\d{1,2}[./]\d{1,2}[./]\d{4}))?""").find(jn)
                if (m == null) null else SpecialFields(
                    finalizeNumber(m.groupValues[1], joined),
                    m.groupValues[2].takeIf { it.isNotEmpty() }?.let { findAllDatesInText(it, "2026", "").firstOrNull() }
                )
            }
            TYPE_EXTRACT -> SpecialFields(
                null,
                Regex("""дата\s+та\s+номер\s+запису(.{0,120})""").find(jn)
                    ?.let { findAllDatesInText(it.groupValues[1], "2026", "").firstOrNull() }
            )
            else -> null
        }
    }

    /** Номер договору з "за договором № ..." (довіреності та інші номери ігноруються). */
    private fun findReconContract(lines: List<String>): String? {
        val joined = lines.joinToString(" ")
        val jn = normalizeCyr(joined)
        val m = Regex("""договор[а-яіїєґ]*\s*(?:№|n[о0]?\.?|#)\s*([0-9a-zа-яіїєґ]+(?:\s*[-/.]\s*[0-9a-zа-яіїєґ]+)*)""").find(jn) ?: return null
        val range = m.groups[1]!!.range
        val num = finalizeNumber(joined.substring(range), joined)
        return if (num.any { it.isDigit() }) num else null
    }

    /** "Реєстраційний номер справи: 1224 0839 9379" -> цифри без пробілів (мінімум 8). */
    private fun findInventoryNumber(jn: String): String? {
        val m = Regex("""реєстраційн\S*\s+номер\s+справи\D{0,6}([0-9оОoзЗбіІlI][0-9оОoзЗбіІlI ]{6,22})""").find(jn) ?: return null
        val digits = digitsFromOcr(m.groupValues[1].replace(" ", ""))
        return digits.takeIf { it.length >= 8 && it.all { c -> c.isDigit() } }
    }

    /** "затверджено протоколом №3 від 28.09.2021" -> (номер протоколу, дата). */
    private fun findCharterApproval(joined: String, jn: String): SpecialFields? {
        for (pm in Regex("""протокол[а-яіїєґ]*""").findAll(jn)) {
            val before = jn.substring((pm.range.first - 60).coerceAtLeast(0), pm.range.first)
            if (!before.contains("твердж")) continue
            val window = jn.substring(pm.range.last + 1, (pm.range.last + 90).coerceAtMost(jn.length))
            val nm = Regex("""(?:№|n[о0]?\.?|#)\s*(\d{1,6}(?:[-/][0-9a-zа-яіїєґ]+)?)""").find(window)
            val number = nm?.let { finalizeNumber(window.substring(it.groups[1]!!.range), joined) }
            val date = findAllDatesInText(window, "2026", "").firstOrNull()
            if (number != null || date != null) return SpecialFields(number, date)
        }
        return null
    }

    /** Період звірки "з 01.04.2020 по 30.06.2020" або "Січень 2020 р. - Травень 2020 р." -> "з_дд.мм.рррр_по_дд.мм.рррр". */
    private fun parsePeriod(jn: String): String? {
        val tokenRegex = Regex("""(\d{1,2})[./](\d{1,2})[./](20\d\d)|([a-zа-яіїєґ']{3,12})\.?\s*(20\d\d)""")
        for (pm in Regex("""період""").findAll(jn)) {
            val window = jn.substring(pm.range.last + 1, (pm.range.last + 90).coerceAtMost(jn.length))
            val dates = ArrayList<Triple<Int, Int, Int>>() // день (0 = не вказано), місяць, рік
            for (t in tokenRegex.findAll(window)) {
                if (t.groupValues[1].isNotEmpty()) {
                    val d = t.groupValues[1].toInt()
                    val mo = t.groupValues[2].toInt()
                    val y = t.groupValues[3].toInt()
                    if (mo in 1..12 && d in 1..getLastDayOfMonth(mo, y)) dates.add(Triple(d, mo, y))
                } else {
                    val mo = matchMonthFuzzy(t.groupValues[4]) ?: continue
                    dates.add(Triple(0, mo, t.groupValues[5].toInt()))
                }
                if (dates.size == 2) break
            }
            if (dates.size < 2) continue
            val a = dates[0]
            val b = dates[1]
            val from = if (a.first == 0) Triple(1, a.second, a.third) else a
            val to = if (b.first == 0) Triple(getLastDayOfMonth(b.second, b.third), b.second, b.third) else b
            if (from.third * 10000 + from.second * 100 + from.first > to.third * 10000 + to.second * 100 + to.first) continue
            fun fmt(t: Triple<Int, Int, Int>) = "%02d.%02d.%d".format(t.first, t.second, t.third)
            return "з_${fmt(from)}_по_${fmt(to)}"
        }
        return null
    }

    /** Чи схожий рядок на фразу (допускає помилки OCR): порівнює вікна з такою самою кількістю слів. */
    private fun fuzzyContains(lineNorm: String, phrase: String): Boolean {
        if (lineNorm.contains(phrase)) return true
        val words = lineNorm.split(' ').filter { it.isNotEmpty() }
        val n = phrase.split(' ').size
        if (words.size < n) return false
        val maxDist = maxOf(2, phrase.length / 8)
        for (i in 0..words.size - n) {
            val window = words.subList(i, i + n).joinToString(" ").trim(',', '.', ':', ';')
            if (levenshtein(window, phrase) <= maxDist) return true
        }
        return false
    }

    /** "2-гу пол. грудня 2025р": цифра перед "пол." (половина місяця) - це не день. */
    private fun isHalfMonthPrefix(prefix: String): Boolean {
        val n = normalizeCyr(prefix).trimEnd()
        return n.endsWith("пол.") || n.endsWith("пол") || n.endsWith("половина") || n.endsWith("половини")
    }

    private fun findPayoutNumber(lines: List<String>): Pair<String, Int>? {
        for (i in lines.indices) {
            parsePayoutNumber(PAYOUT_NUMBER_REGEX.find(lines[i]))?.let { return Pair(it, i) }
        }
        // Запасний варіант: пробіли всередині номера після "#" або "№"
        for (i in lines.indices) {
            val line = lines[i]
            val at = line.indexOfFirst { it == '#' || it == '№' }
            if (at < 0) continue
            val tail = line.substring(at + 1).replace(" ", "")
            parsePayoutNumber(PAYOUT_NUMBER_REGEX.find(tail))?.let { return Pair(it, i) }
        }
        return null
    }

    private fun parsePayoutNumber(m: MatchResult?): String? {
        if (m == null) return null
        val head = digitsFromOcr(m.groupValues[1])
        val tail = digitsFromOcr(m.groupValues[3])
        val mid = lettersToLatin(m.groupValues[2])
        if (mid.length != 2 || !mid.all { it in 'A'..'Z' }) return null
        if (!head.all { it.isDigit() } || !tail.all { it.isDigit() }) return null
        if (yyMmDdToDate(head) == null) return null
        return head + mid + tail
    }

    private fun digitsFromOcr(s: String): String = fixOcrDigits(s).replace('o', '0')

    private fun lettersToLatin(s: String): String {
        val sb = StringBuilder()
        for (ch in s.uppercase()) {
            sb.append(
                when (ch) {
                    '8' -> 'B'
                    'А' -> 'A'
                    'В' -> 'B'
                    'Е' -> 'E'
                    'К' -> 'K'
                    'М' -> 'M'
                    'Н' -> 'H'
                    'О' -> 'O'
                    'Р' -> 'P'
                    'С' -> 'C'
                    'Т' -> 'T'
                    'Х' -> 'X'
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** ррммдд -> дд.мм.20рр (або null, якщо це не коректна дата). */
    private fun yyMmDdToDate(s: String): String? {
        if (s.length != 6 || !s.all { it.isDigit() }) return null
        val year = 2000 + s.substring(0, 2).toInt()
        val month = s.substring(2, 4).toInt()
        val day = s.substring(4, 6).toInt()
        if (month !in 1..12 || day !in 1..getLastDayOfMonth(month, year)) return null
        return "${day.toString().padStart(2, '0')}.${month.toString().padStart(2, '0')}.$year"
    }

    private fun payoutDateFromNumber(number: String): String? {
        if (number.length != 20) return null
        if (!number.substring(6, 8).all { it in 'A'..'Z' }) return null
        return yyMmDdToDate(number.substring(0, 6))
    }

    private fun matchMonthFuzzy(word: String): Int? {
        val norm = normalizeCyr(word).trim('.', ',', '-', '«', '»', '"', '\'')
        if (norm.length < 3) return null

        for ((forms, monthNum) in MONTH_CANONICAL) {
            val stem = forms[0].take(4)
            if (norm.startsWith(stem) || forms.any { norm == it }) return monthNum
        }

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
