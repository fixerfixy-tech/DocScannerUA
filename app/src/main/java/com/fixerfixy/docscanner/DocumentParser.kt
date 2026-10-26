package com.fixerfixy.docscanner

import java.util.Calendar

/**
 * Результат розбору з оцінками впевненості (для злиття результатів кількох варіантів OCR).
 */
class ParseOutcome(
    val doc: ParsedDocument,
    val numberScore: Int,
    val dateScore: Int,
    val typeSpecific: Boolean
) {
    /** Достатньо надійно, щоб не запускати додаткові варіанти обробки зображення. */
    val confident: Boolean
        get() = typeSpecific && numberScore >= 80 && dateScore >= 70
}

object DocumentParser {

    private const val NO_DATE = "не_вказано"
    private const val NO_NUMBER = "б-н"
    private const val MIN_DATE_SCORE = 35
    private const val MIN_NUMBER_SCORE = 30

    // ---------- Базові шматки регулярних виразів ----------
    private const val W = "0-9A-Za-zА-Яа-яІіЇїЄєҐґ"
    private const val BEFORE = "(?<![$W])"
    private const val AFTER = "(?![$W])"
    private const val TOKEN = """[$W]+(?:(?:\s?[-/\\_]\s?|\.)[$W]+)*"""

    private val MONTH_STEMS = listOf(
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

    // Повні назви місяців для нечіткого порівняння (Левенштейн)
    private val MONTH_FUZZY = listOf(
        "січня" to "01", "січень" to "01",
        "лютого" to "02", "лютий" to "02",
        "березня" to "03", "березень" to "03",
        "квітня" to "04", "квітень" to "04",
        "травня" to "05", "травень" to "05",
        "червня" to "06", "червень" to "06",
        "липня" to "07", "липень" to "07",
        "серпня" to "08", "серпень" to "08",
        "вересня" to "09", "вересень" to "09",
        "жовтня" to "10", "жовтень" to "10",
        "листопада" to "11", "листопад" to "11",
        "грудня" to "12", "грудень" to "12"
    )

    // ---------- Регулярні вирази ----------
    private val NUM_MARKER = Regex(
        """(?iu)(?:№|#|номер|${BEFORE}n[oо0°ºeе]$AFTER|${BEFORE}м[оеeo0]$AFTER|${BEFORE}н[оo0°]$AFTER)\s*[:.]?\s*($TOKEN)"""
    )
    private val VID_AHEAD = Regex("""(?iu)^\s*[вb][іi1l][дd]$AFTER""")
    private val STOP_WORD = Regex("""(?iu)$BEFORE(?:в[іi1l][дd]|за)$AFTER""")
    private val TOKEN_RE = Regex(TOKEN)
    private val LONG_DIGITS = Regex("""(?<![0-9])[0-9]{6,12}(?![0-9])""")
    private val COMPOUND = Regex(
        """(?iu)$BEFORE([а-яa-zіїєґ]{1,4}\s*[-_]\s*0*[0-9]{3,10}|[0-9]{10,20}\.[0-9]{1,2}\.[0-9]{2,4}|[0-9]{1,5}\s*[-/]\s*[0-9]{1,5}(?:\s*[-/]\s*[0-9]{1,5})?)$AFTER"""
    )
    private val PARENT_NUM = Regex(
        """(\d{1,5}\s*[-/]\s*\d{1,5}(?:\s*[-/]\s*\d{1,5})?|[а-яa-zіїєґ]{1,4}\s*[-]\s*\d{1,10})""",
        RegexOption.IGNORE_CASE
    )
    private val SPEC_NUM = Regex("""(?iu)специфікація\s*(?:№|#|номер|[nмн][o0оеe°.]*)?\s*[:.]?\s*(\d+)""")
    private val SPEC_ADD_NUM = Regex("""(?iu)додаток\s*(?:№|#|номер|[nмн][o0оеe°.]*)?\s*[:.]?\s*(\d+)""")

    private val SHORT_NUM = Regex("""^(?:[1-9]|10)$""")
    private val YEAR_LIKE = Regex("""^20[2-3][0-9]$""")
    private val DDMM_DIGITS = Regex("""^(?:0[1-9]|[12][0-9]|3[01])(?:0[1-9]|1[0-2])$""")
    private val DDMM_PAIR = Regex("""^(?:0?[1-9]|[12][0-9]|3[01])[-/](?:0?[1-9]|1[0-2])$""")
    private val DATE_LIKE = Regex("""^(?:0?[1-9]|[12][0-9]|3[01])[-.](?:0?[1-9]|1[0-2])[-.](?:20)?[0-9]{2}$""")
    private val FULL_DATE = Regex("""^(\d{2})\.(\d{2})\.(\d{4})$""")

    private val NUMERIC_DATE = Regex("""(?<![0-9])([0-3]?[0-9])[./,]([01]?[0-9])[./,]([0-9]{4}|[0-9]{2})(?![0-9])""")
    private val DASH_DATE = Regex("""(?<![0-9])([0-3]?[0-9])-([01]?[0-9])-(20[0-9]{2})(?![0-9])""")
    private val SPACE_DATE = Regex("""(?<![0-9])(0?[1-9]|[12][0-9]|3[01])\s(0[1-9]|1[0-2])\s(20[0-9]{2})(?![0-9])""")
    private val WORD_RE = Regex("""[A-Za-zА-Яа-яІіЇїЄєҐґ']{3,12}""")
    private val YEAR_RE = Regex("""(?<![0-9])(2[0ОоO][2-3][0-9ОоOЗзб])(?![0-9])""")
    private val NEXT_DATE_START = Regex("""(?<![0-9])[0-9]{1,2}[./][0-9]{1,2}[./]""")
    private val MONTH_YEAR_RE = Regex("""([A-Za-zА-Яа-яІіЇїЄєҐґ']{3,12})\s*[-./\s]{1,3}\s*(20[0-9ОоO]{2})(?![0-9])""")
    private val DAY_TAIL = Regex("""(?<![A-Za-zА-Яа-яІіЇїЄєҐґ])[0-9ЗзОоOбІіlI]{1,3}$""")

    private val VID_TAIL = Regex("""в[і1l][дd]\s*[«"“']?\s*[0-9озб]{0,3}\s*[»"”']?\s*$""")
    private val DEADLINE_TAIL = Regex("""(?:^|[^а-яіїєґ])до\s*[«"“']?\s*[0-9озб]{0,3}\s*[»"”']?\s*$""")
    private val DEADLINE_WORDS = Regex("""оплат|сплат|термін|дійсн|пізніше""")
    private val CONTRACT_WORDS = Regex("""договор|договір|замовленн""")

    private val CLAUSE_NUM = Regex("""^\d{1,2}(?:\.\d{1,2}){1,2}\.?\s+[а-яіїєґa-z]""")
    private val IPN_WORD = Regex("""(?:^|[^а-яіїєґ])іпн(?:[^а-яіїєґ]|$)""")

    // «Акт», але не «Акт підготував», «Акт підписано у двох примірниках…»
    private val ACT_WORD = Regex(
        """(?:^|[^а-яіїєґ])акт(?![а-яіїєґ])(?!\s+(?:підготув|підпис|склад|прийня|виконав|надав|затверд))"""
    )
    private val ACT_PREFIXED = Regex("""[а-яіїєґ0-9]?кт\s+(?:надання|виконаних|здачі|приймання)""")
    private val ACT_SHORT = Regex("""(?:^|[^а-яіїєґ])ак[гі]?(?![а-яіїєґ])""")
    private val ACT_FUZZY_NUM = Regex("""(?:^|[^а-яіїєґ])[аял]к[тг](?=\s*(?:№|#|\d|н[оo0]\b|м[еe]\b))""")
    private val ACT_KEYWORDS = Regex("""надан|виконан|здач|прийм|передач|послуг|робіт""")
    private val RAHUNOK_AKT = Regex("""рахун(?:ок|ка)\s*[-/.]?\s*акт""")
    private val RAHUNOK_FAKTURA = Regex("""рахунок\s*[-/]?\s*фактура""")
    private val DOGOVIR_WORD = Regex("""(?:^|[^а-яіїєґ])догов[іi1l]р(?:[^а-яіїєґ:]|$)""")

    // ==================================================================
    //  ГОЛОВНА ТОЧКА ВХОДУ
    // ==================================================================
    fun parse(rawText: String): ParsedDocument = parseWithConfidence(rawText).doc

    fun parseWithConfidence(rawText: String): ParseOutcome {
        val empty = ParseOutcome(
            ParsedDocument(type = "Документ", number = NO_NUMBER, date = NO_DATE, rawOcrText = rawText),
            0, 0, false
        )
        if (rawText.isBlank() || rawText.startsWith("[")) return empty
        return try {
            parseInternal(rawText)
        } catch (e: Exception) {
            empty
        }
    }

    private fun parseInternal(rawText: String): ParseOutcome {
        val lines = rawText.lines()
            .map { preCleanLine(it) }
            .filter { it.isNotBlank() }

        val docType = detectType(lines)

        // «Чисті» рядки: службові/банківські/реквізитні замінені порожніми (індекси зберігаються)
        val usable = lines.map { if (isNoiseLine(it)) "" else it }

        val anchorIdx = findTitleAnchorIndex(usable, docType)
        val date = extractDate(usable, rawText, docType, anchorIdx)
        val number = if (docType == "Специфікація") {
            extractSpecNumber(usable)
        } else {
            extractNumber(usable, rawText, docType, anchorIdx, date.value)
        }

        var parentContractNum = ""
        var parentContractDate = ""
        if (docType == "Специфікація" || docType == "Додаткова угода") {
            val parentInfo = extractParentContractForSpec(lines, rawText, date.value)
            parentContractNum = parentInfo.first
            parentContractDate = parentInfo.second
        }

        val doc = ParsedDocument(
            type = docType,
            number = number.value,
            date = date.value,
            rawOcrText = rawText,
            contractNumber = parentContractNum,
            contractDate = parentContractDate
        )
        return ParseOutcome(doc, number.score, date.score, isSpecificType(docType))
    }

    fun isSpecificType(type: String): Boolean = type != "Документ" && type != "Акт" && type != "Рахунок"

    // ==================================================================
    //  ДОПОМІЖНЕ: нормалізація, шум, рядки
    // ==================================================================
    private fun preCleanLine(line: String): String {
        return line.replace('\u00A0', ' ')
            .replace('–', '-')
            .replace('—', '-')
            .replace('−', '-')
            .replace("""\s+""".toRegex(), " ")
            .trim()
    }

    // Латинські двійники -> кирилиця (тільки для пошуку українських слів!)
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

    private fun isNoiseLine(line: String): Boolean {
        return isBodyOrReferenceClause(line) || isBankOrAccountLine(line) || isRequisiteLine(line)
    }

    private fun isBodyOrReferenceClause(line: String): Boolean {
        val norm = normalize(line)
        // Нумерація пунктів «1.1. Текст», але НЕ дата «30.09.2026»
        if (CLAUSE_NUM.containsMatchIn(norm)) return true
        return norm.contains("на підставі підписаних") ||
               norm.contains("на підставі наведених") ||
               norm.contains("актів здачі") ||
               norm.contains("у специфікації") ||
               norm.contains("у рахунку-фактурі") ||
               norm.contains("склали цей акт") ||
               norm.contains("склали даний акт") ||
               norm.contains("уклали цей договір") ||
               norm.contains("уклали цю специфікацію") ||
               norm.contains("одночасно актом") ||
               norm.contains("є актом") ||
               norm.contains("передавання-приймання") ||
               norm.contains("складання рахунка-акта") ||
               norm.contains("були виконані наступні") ||
               norm.contains("були проведені такі") ||
               norm.contains("претензій одна до одної") ||
               norm.contains("претензій по об'єму") ||
               norm.contains("претензій не мають") ||
               norm.contains("дійсний до") ||
               norm.contains("до сплати до") ||
               norm.contains("оплатити до") ||
               norm.contains("сплатити до") ||
               norm.contains("залишок авансу на") ||
               norm.contains("погашено борг на")
    }

    private fun isBankOrAccountLine(line: String): Boolean {
        val norm = normalize(line)
        val low = line.lowercase()   // без заміни латиниці — для IBAN
        return norm.contains("рахунок отримувача") ||
               norm.contains("рахунок постачальника") ||
               norm.contains("поточний рахунок") ||
               norm.contains("розрахунковий рахунок") ||
               norm.contains("особовий рахунок") ||
               norm.contains("р/р") ||
               norm.contains("п/р") ||
               low.contains("iban") ||
               Regex("""ua\s?\d{2}[\d\s]{13,30}""").containsMatchIn(low) ||
               Regex("""(?<![0-9])26[0-5][0-9]{11,16}(?![0-9])""").containsMatchIn(low)
    }

    // Рядки з реквізитами сторін (ЄДРПОУ, ІПН, адреси, телефони) — тут немає номера/дати документа
    private fun isRequisiteLine(line: String): Boolean {
        val n = normalize(line)
        return n.contains("єдрпоу") || n.contains("едрпоу") || n.contains("єдрпо") ||
               n.contains("єдр/") || n.contains("рнокпп") ||
               IPN_WORD.containsMatchIn(n) ||
               n.contains("тел.") || n.contains("тел:") || n.contains("телефон") ||
               n.contains("вул.") || n.contains("вулиця") ||
               n.contains("будинок") || n.contains("кімната") || n.contains("корпус") ||
               n.contains("область") || n.contains("обл.") || n.contains("код за") || n.contains("мфо")
    }

    // ==================================================================
    //  ТИП ДОКУМЕНТА
    // ==================================================================
    private fun isActHeaderLine(n: String, loose: Boolean): Boolean {
        if (n.contains("договор") || n.contains("договір") || n.contains("рахунок") || n.contains("рахунка")) return false
        if (ACT_WORD.containsMatchIn(n)) return true
        if (ACT_PREFIXED.containsMatchIn(n)) return true
        if (n.contains("здачі-прийняття")) return true
        if (ACT_FUZZY_NUM.containsMatchIn(n)) return true
        // «Ак надання...» — слово «акт» зіпсоване печаткою
        if (ACT_SHORT.containsMatchIn(n) && ACT_KEYWORDS.containsMatchIn(n)) return true
        if (loose && (n.contains("надання послуг") || n.contains("виконаних робіт") ||
                    n.contains("приймання-передачі") || n.contains("здачі-прийняття"))) return true
        return false
    }

    /**
     * strict=true — для пошуку по всьому тексту (лише чіткі словосполучення),
     * strict=false — для заголовка (3 рядки від слова «Акт»).
     */
    private fun actSubtype(norm: String, strict: Boolean): String? {
        if (norm.contains("основних засобів") || norm.contains("оз-1")) return "Акт приймання-передачі ОЗ"
        if (norm.contains("списання")) return "Акт списання ТМЦ"
        if (strict) {
            return when {
                norm.contains("здачі-прийняття") || norm.contains("приймання-передач") ||
                    norm.contains("прийманя-передач") -> "Акт здачі-прийняття робіт"
                norm.contains("надання послуг") || norm.contains("наданих послуг") -> "Акт надання послуг"
                norm.contains("виконаних робіт") || norm.contains("виконання робіт") -> "Акт виконаних робіт"
                else -> null
            }
        }
        return when {
            norm.contains("здач") || norm.contains("прийм") || norm.contains("прийом") || norm.contains("передач") ->
                "Акт здачі-прийняття робіт"
            norm.contains("надан") || norm.contains("послуг") -> "Акт надання послуг"
            norm.contains("виконан") || norm.contains("робіт") || norm.contains("викон") -> "Акт виконаних робіт"
            else -> null
        }
    }

    private fun detectType(lines: List<String>): String {
        val nonClauseLines = lines.filterNot { isBodyOrReferenceClause(it) }
        val nonBankLines = nonClauseLines.filterNot { isBankOrAccountLine(it) }
        val nonClauseNorm = normalize(nonBankLines.joinToString("\n"))
        val fullNorm = normalize(lines.joinToString("\n"))
        val top10Norm = normalize(nonBankLines.take(10).joinToString("\n"))

        val hasSpecHeader = nonBankLines.any { line ->
            val n = normalize(line)
            Regex("""(?:^|\s)специфікаці[яїю](?:\s|№|$)""").containsMatchIn(n)
        }
        if (hasSpecHeader) return "Специфікація"

        when {
            RAHUNOK_AKT.containsMatchIn(fullNorm) ||
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

        val hasActHeaderLine = nonBankLines.any { isActHeaderLine(normalize(it), false) }
        val hasActBodyMarkers = !fullNorm.contains("рахунок") && (
            fullNorm.contains("склали цей акт") ||
            fullNorm.contains("склали даний акт") ||
            (fullNorm.contains("від виконавця") && fullNorm.contains("від замовника")) ||
            fullNorm.contains("претензій по об'єму") ||
            fullNorm.contains("претензій одна до одної")
        )

        if (hasActHeaderLine || hasActBodyMarkers) {
            // Підтип: спершу заголовок (3 рядки від слова «Акт»), потім чіткі фрази по всьому тексту
            val idx = nonBankLines.indexOfFirst { isActHeaderLine(normalize(it), false) }
            val titleNorm = if (idx >= 0) normalize(nonBankLines.drop(idx).take(3).joinToString(" ")) else ""
            return actSubtype(titleNorm, false)
                ?: actSubtype(nonClauseNorm, true)
                ?: actSubtype(nonClauseNorm, false)
                ?: "Акт"
        }

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

        if (top10Norm.contains("додаткова угода")) return "Додаткова угода"
        val hasRealContractHeader = nonBankLines.take(10).any { line ->
            val n = normalize(line)
            !n.contains("до договор") && !n.contains("по договор") && !n.contains("згідно") && !n.contains("договір:") &&
            DOGOVIR_WORD.containsMatchIn(n)
        }
        if (hasRealContractHeader) return "Договір"

        return when {
            nonClauseNorm.contains("накладна") -> "Накладна"
            nonClauseNorm.contains("квитанці") -> "Квитанція"
            nonClauseNorm.contains("чек") -> "Чек"
            nonClauseNorm.contains("рахунок") -> "Рахунок"
            nonClauseNorm.contains("договір") && !nonClauseNorm.contains("договір:") -> "Договір"
            else -> "Документ"
        }
    }

    // Індекс рядка-заголовка. -1, якщо не знайдено.
    private fun findTitleAnchorIndex(lines: List<String>, docType: String): Int {
        for (i in lines.indices) {
            if (lines[i].isEmpty()) continue
            val n = normalize(lines[i])
            val matched = when {
                docType == "Специфікація" -> n.contains("специфікаці")
                docType == "Рахунок-акт" -> RAHUNOK_AKT.containsMatchIn(n) || (n.contains("рахунок") && n.contains("№"))
                docType == "Рахунок-фактура" -> RAHUNOK_FAKTURA.containsMatchIn(n)
                docType.startsWith("Рахунок") -> n.contains("рахунок") && !n.contains("по рахунку")
                docType.startsWith("Акт") -> isActHeaderLine(n, true)
                docType == "Договір" -> !n.contains("до договор") && !n.contains("по договор") &&
                        !n.contains("згідно") && !n.contains("договір:") && DOGOVIR_WORD.containsMatchIn(n)
                docType == "Додаткова угода" -> n.contains("додаткова угода")
                docType.contains("накладна", true) -> n.contains("накладна")
                docType.startsWith("Наказ") -> n.contains("наказ") || n.contains("розпорядження")
                docType.contains("ордер", true) -> n.contains("ордер")
                docType.contains("інструкція", true) -> n.contains("інструкція")
                else -> false
            }
            if (matched) return i
        }
        return -1
    }

    // ==================================================================
    //  ОЦІНКА КАНДИДАТІВ: спільні утиліти
    // ==================================================================
    private fun refWordsFor(docType: String): List<String> = when {
        docType == "Договір" -> emptyList()
        docType == "Додаткова угода" -> emptyList()
        docType.startsWith("Акт") -> listOf("договор", "договір", "замовленн", "рахунк", "накладн")
        else -> listOf("договор", "договір", "замовленн")
    }

    private fun positionBonus(line: Int, anchorIdx: Int, total: Int): Int {
        if (anchorIdx >= 0 && line >= anchorIdx - 1 && line <= anchorIdx + 5) return 35
        if (line < 15) return 15
        if (total > 0 && line >= total * 2 / 3) return 10
        return 0
    }

    private fun lineAt(starts: IntArray, offset: Int): Int {
        var idx = 0
        for (i in starts.indices) {
            if (starts[i] <= offset) idx = i else break
        }
        return idx
    }

    // Контекст перед датою в межах її рядка
    private fun contextScore(joined: String, lineStart: Int, start: Int, refWords: List<String>): Int {
        val from = maxOf(lineStart, start - 60)
        val prefix = normalize(joined.substring(from, start))
        var s = 0
        if (VID_TAIL.containsMatchIn(prefix)) s += 25
        if (prefix.takeLast(20).contains("дата")) s += 15
        if (refWords.isNotEmpty()) {
            val near = prefix.takeLast(45)
            // Дата договору/замовлення — не дата документа
            if (refWords.any { near.contains(it) }) s -= 90
        }
        if (DEADLINE_TAIL.containsMatchIn(prefix) || DEADLINE_WORDS.containsMatchIn(prefix.takeLast(40))) s -= 40
        return s
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    private fun matchMonth(word: String): String? {
        val w = normalize(word).filter { it.isLetter() }
        if (w.length < 3) return null
        for ((stems, code) in MONTH_STEMS) {
            if (stems.any { w.startsWith(it) || (it.length >= 4 && w.contains(it)) }) return code
        }
        if (w.length >= 5) {
            var bestCode: String? = null
            var bestD = 99
            for ((name, code) in MONTH_FUZZY) {
                val d = minOf(levenshtein(w, name), levenshtein(w, name.take(w.length)))
                if (d < bestD) {
                    bestD = d
                    bestCode = code
                }
            }
            val limit = if (w.length <= 5) 1 else 2
            if (bestD <= limit) return bestCode
        }
        return null
    }

    private fun fixOcrDigits(s: String): String {
        return s.replace('З', '3').replace('з', '3')
            .replace('О', '0').replace('о', '0').replace('O', '0')
            .replace('б', '6')
            .replace('І', '1').replace('і', '1').replace('l', '1').replace('I', '1')
    }

    private fun daysInMonth(month: Int, year: Int): Int = when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private fun guessYear(fullText: String, curYear: Int): String {
        val counts = Regex("""(?<![0-9])(20[0-9]{2})(?![0-9])""").findAll(fullText)
            .map { it.groupValues[1].toInt() }
            .filter { it >= curYear - 3 && it <= curYear }
            .groupingBy { it }
            .eachCount()
        val best = counts.maxByOrNull { it.value }?.key ?: curYear
        return best.toString()
    }

    private fun extractDayFromPrefix(prefix: String): Int? {
        val m = DAY_TAIL.find(prefix.trimEnd()) ?: return null
        val raw = fixOcrDigits(m.value)
        val direct = raw.toIntOrNull() ?: return null
        if (direct in 1..31) return direct
        if (raw.length == 3) {
            val lastTwo = raw.takeLast(2).toIntOrNull()
            if (lastTwo != null && lastTwo in 1..31) return lastTwo
            val firstTwo = raw.take(2).toIntOrNull()
            if (firstTwo != null && firstTwo in 1..31) return firstTwo
        }
        return null
    }

    // ==================================================================
    //  ДАТА
    // ==================================================================
    private class RawDate(val value: String, val start: Int, val base: Int, val ymd: Int)
    private class ScoredDate(val value: String, val score: Int, val line: Int)
    private class DateResult(val value: String, val score: Int)

    private fun scanDates(text: String, defaultYear: String, minYear: Int, maxYear: Int): List<RawDate> {
        val out = ArrayList<RawDate>()

        fun add(day: Int, month: Int, year: Int, base: Int, start: Int) {
            if (year < minYear || year > maxYear) return
            if (month < 1 || month > 12 || day < 1 || day > daysInMonth(month, year)) return
            val v = day.toString().padStart(2, '0') + "." + month.toString().padStart(2, '0') + "." + year
            out.add(RawDate(v, start, base, year * 10000 + month * 100 + day))
        }

        for (m in NUMERIC_DATE.findAll(text)) {
            var y = m.groupValues[3]
            if (y.length == 2) y = "20$y"
            add(m.groupValues[1].toInt(), m.groupValues[2].toInt(), y.toInt(), 40, m.range.first)
        }
        for (m in DASH_DATE.findAll(text)) {
            add(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), 35, m.range.first)
        }
        for (m in SPACE_DATE.findAll(text)) {
            add(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), 30, m.range.first)
        }

        // Текстові дати: «30 вересня 2026 р.», з допуском на сміття від печаток
        for (m in WORD_RE.findAll(text)) {
            val code = matchMonth(m.value) ?: continue
            val start = m.range.first
            val prefix = text.substring((start - 12).coerceAtLeast(0), start)
                .trimEnd(' ', '»', '"', '”', '\'', '«', '“', '.', ',')
            val day = extractDayFromPrefix(prefix) ?: continue

            val endIdx = m.range.last + 1
            val after = text.substring(endIdx, (endIdx + 80).coerceAtMost(text.length))
            val cut = after.split(NEXT_DATE_START).first()

            var year = defaultYear.toIntOrNull() ?: maxYear
            var adj = -15
            val ym = YEAR_RE.find(cut)
            if (ym != null) {
                val y = fixOcrDigits(ym.groupValues[1]).toIntOrNull()
                if (y != null && y >= minYear && y <= maxYear) {
                    year = y
                    adj = if (ym.range.first <= 14) 15 else if (ym.range.first <= 40) 5 else -5
                }
            }
            add(day, code.toInt(), year, 40 + adj, start)
        }
        return out
    }

    private fun extractDate(usable: List<String>, fullText: String, docType: String, anchorIdx: Int): DateResult {
        val cal = Calendar.getInstance()
        val curYear = cal.get(Calendar.YEAR)
        val today = curYear * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
        val defaultYear = guessYear(fullText, curYear)
        val minYear = curYear - 6
        val maxYear = curYear + 1

        val total = usable.size
        val starts = IntArray(total)
        val sb = StringBuilder()
        for (i in 0 until total) {
            starts[i] = sb.length
            sb.append(usable[i]).append(' ')
        }
        val joined = sb.toString()
        val refWords = refWordsFor(docType)

        val scored = ArrayList<ScoredDate>()
        for (r in scanDates(joined, defaultYear, minYear, maxYear)) {
            val line = lineAt(starts, r.start)
            var score = r.base + positionBonus(line, anchorIdx, total) +
                    contextScore(joined, starts[line], r.start, refWords)
            if (r.ymd > today + 200) score -= 40
            scored.add(ScoredDate(r.value, score, line))
        }

        var bestValue = ""
        var bestScore = Int.MIN_VALUE
        var bestLine = Int.MAX_VALUE
        for (group in scored.groupBy { it.value }.values) {
            var topScore = Int.MIN_VALUE
            var topLine = Int.MAX_VALUE
            for (g in group) {
                if (g.score > topScore || (g.score == topScore && g.line < topLine)) {
                    topScore = g.score
                    topLine = g.line
                }
            }
            // Одна й та сама дата в кількох місцях (заголовок + підпис) = вища довіра
            val s = topScore + ((group.size - 1) * 15).coerceAtMost(30)
            if (s > bestScore || (s == bestScore && topLine < bestLine)) {
                bestScore = s
                bestValue = group[0].value
                bestLine = topLine
            }
        }
        if (bestValue.isNotEmpty() && bestScore >= MIN_DATE_SCORE) return DateResult(bestValue, bestScore)

        // Останній шанс для актів: «за вересень 2026» -> останній день місяця
        if (docType.startsWith("Акт") || docType == "Рахунок-акт") {
            for (m in MONTH_YEAR_RE.findAll(joined)) {
                val code = matchMonth(m.groupValues[1]) ?: continue
                val y = fixOcrDigits(m.groupValues[2]).toIntOrNull() ?: continue
                if (y < minYear || y > maxYear) continue
                val last = daysInMonth(code.toInt(), y)
                return DateResult(last.toString().padStart(2, '0') + "." + code + "." + y, 40)
            }
        }
        return DateResult(NO_DATE, 0)
    }

    // ==================================================================
    //  НОМЕР
    // ==================================================================
    private class NumCand(val value: String, val score: Int, val strong: Boolean)
    private class NumberResult(val value: String, val score: Int)

    private fun extractSpecNumber(usable: List<String>): NumberResult {
        for (line in usable) {
            if (line.isEmpty()) continue
            val m = SPEC_NUM.find(line)
            if (m != null) return NumberResult(m.groupValues[1], 90)
        }
        for (line in usable) {
            if (line.isEmpty()) continue
            val m = SPEC_ADD_NUM.find(line)
            if (m != null) return NumberResult(m.groupValues[1], 70)
        }
        return NumberResult("1", 20)
    }

    private fun extractNumber(
        usable: List<String>,
        fullText: String,
        docType: String,
        anchorIdx: Int,
        docDate: String
    ): NumberResult {
        val refWords = refWordsFor(docType)
        val total = usable.size
        val cands = ArrayList<NumCand>()

        fun isNear(i: Int): Boolean =
            if (anchorIdx >= 0) (i >= anchorIdx - 1 && i <= anchorIdx + 5) else i < 8

        // 1) Явний маркер «№ / No / ме / Мо» + число. Найсильніший сигнал.
        for (i in 0 until total) {
            val line = usable[i]
            if (line.isEmpty()) continue
            val near = isNear(i)
            for (m in NUM_MARKER.findAll(line)) {
                // Номер мусить містити справжню цифру (інакше «Номер з\п» з шапки таблиці стане «3-П»)
                if (m.groupValues[1].none { it in '0'..'9' }) continue
                val value = finalizeNumber(m.groupValues[1], fullText)
                if (!isValidDocNumber(value)) continue
                val before = normalize(line.substring(0, m.range.first)).takeLast(45)
                // «до договору № 300334481», «замовлення № …» — це НЕ номер цього документа.
                // Відкидаємо повністю, щоб при пошкодженому заголовку не підставлявся номер договору.
                if (refWords.isNotEmpty() && CONTRACT_WORDS.containsMatchIn(before.takeLast(32))) continue
                val hasVid = VID_AHEAD.containsMatchIn(line.substring(m.range.last + 1))
                val short = SHORT_NUM.matches(value)
                // Числа 1..10 (штампи печаток типу «№8») приймаємо лише за сильного контексту
                if (short && !hasVid && !near) continue
                var score = 50
                if (hasVid) score += 25
                score += if (near) 30 else if (i < 15) 10 else 0
                if (refWords.any { before.contains(it) }) score -= 60
                if (short) score -= 10
                cands.add(NumCand(value, score, hasVid || near))
            }
        }

        // 2) Токени в заголовку та двох наступних рядках (до слова «від»/«за»)
        if (anchorIdx >= 0) {
            for (off in 0..2) {
                val idx = anchorIdx + off
                if (idx >= total || usable[idx].isEmpty()) continue
                var tail = usable[idx]
                if (refWords.isNotEmpty() && CONTRACT_WORDS.containsMatchIn(normalize(tail))) continue
                val cut = STOP_WORD.find(tail)
                if (cut != null) tail = tail.substring(0, cut.range.first)
                for (tok in TOKEN_RE.findAll(tail)) {
                    if (tok.value.none { it in '0'..'9' }) continue
                    val value = finalizeNumber(tok.value, fullText)
                    if (!isValidDocNumber(value) || SHORT_NUM.matches(value)) continue
                    val before = normalize(tail.substring(0, tok.range.first)).takeLast(45)
                    var score = if (off == 0) 40 else 28
                    if (YEAR_LIKE.matches(value)) score -= 15
                    if (refWords.any { before.contains(it) }) score -= 60
                    cands.add(NumCand(value, score, false))
                }
            }

            // 2б) Довгі цифрові номери без маркера «№» (маркер закритий печаткою): «АКТ 2100032124»
            for (off in 0..1) {
                val idx = anchorIdx + off
                if (idx >= total || usable[idx].isEmpty()) continue
                if (refWords.isNotEmpty() && CONTRACT_WORDS.containsMatchIn(normalize(usable[idx]))) continue
                for (m in LONG_DIGITS.findAll(usable[idx])) {
                    val value = finalizeNumber(m.value, fullText)
                    if (!isValidDocNumber(value)) continue
                    cands.add(NumCand(value, if (off == 0) 45 else 32, false))
                }
            }
        }

        // 3) Складені коди без маркера: ЛТ-000123, 12/34, 12-34-56
        for (i in 0 until minOf(total, 24)) {
            val line = usable[i]
            if (line.isEmpty()) continue
            for (m in COMPOUND.findAll(line)) {
                val value = finalizeNumber(m.groupValues[1], fullText)
                if (!isValidDocNumber(value)) continue
                val before = normalize(line.substring(0, m.range.first)).takeLast(45)
                var score = if (isNear(i)) 30 else 22
                if (refWords.any { before.contains(it) }) score -= 60
                if (DDMM_PAIR.matches(value)) score -= 12
                cands.add(NumCand(value, score, false))
            }
        }

        if (cands.isEmpty()) return NumberResult(NO_NUMBER, 0)

        // Перехресна перевірка з датою: «3009» = 30.09 — це не номер
        val dateKeys = HashSet<String>()
        val dm = FULL_DATE.find(docDate)
        if (dm != null) {
            val dd = dm.groupValues[1]
            val mm = dm.groupValues[2]
            val yyyy = dm.groupValues[3]
            dateKeys.add(dd + mm)
            dateKeys.add(dd + mm + yyyy)
            dateKeys.add(dd + mm + yyyy.substring(2))
        }

        var bestValue = NO_NUMBER
        var bestScore = Int.MIN_VALUE
        for (group in cands.groupBy { it.value }.values) {
            var s = group.maxOf { it.score } + ((group.size - 1) * 5).coerceAtMost(15)
            if (group.none { it.strong }) {
                val v = group[0].value
                if (v.all { it.isDigit() } && (DDMM_DIGITS.matches(v) || v in dateKeys)) s -= 35
            }
            if (s > bestScore) {
                bestScore = s
                bestValue = group[0].value
            }
        }
        return if (bestScore >= MIN_NUMBER_SCORE) NumberResult(bestValue, bestScore) else NumberResult(NO_NUMBER, 0)
    }

    private fun isValidDocNumber(cand: String): Boolean {
        if (cand.isBlank() || cand == "Б-Н") return false
        if (!cand.any { it.isDigit() }) return false
        if (cand.matches(Regex("""^\d{2}\.\d{2}\.20\d{2}$"""))) return false
        if (DATE_LIKE.matches(cand)) return false
        if (cand.startsWith("UA") && cand.length >= 20) return false
        if (cand.matches(Regex("""^26[0-5]\d{11,16}$"""))) return false
        if (cand.matches(Regex("""^\d{15,}$"""))) return false
        if (cand.matches(Regex("""^0\d{9}$"""))) return false
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

    // ==================================================================
    //  ДОГОВІР-ОСНОВА ДЛЯ СПЕЦИФІКАЦІЙ ТА ДОДАТКОВИХ УГОД
    // ==================================================================
    private fun extractParentContractForSpec(
        lines: List<String>,
        fullText: String,
        specDate: String
    ): Pair<String, String> {
        val curYear = Calendar.getInstance().get(Calendar.YEAR)
        val defaultYear = guessYear(fullText, curYear)
        var cNum = ""
        var cDate = ""

        for (i in lines.indices) {
            val norm = normalize(lines[i])
            if (!(norm.contains("договор") || norm.contains("договір"))) continue

            val endIdx = (i + 2).coerceAtMost(lines.size - 1)
            val window = (i..endIdx).joinToString(" ") { lines[it] }
            val wNorm = normalize(window)
            var pos = wNorm.indexOf("договір")
            if (pos < 0) pos = wNorm.indexOf("договор")
            if (pos < 0) pos = 0
            if (pos > window.length) pos = 0

            val mk = NUM_MARKER.find(window, pos)
            if (mk != null) {
                val v = finalizeNumber(mk.groupValues[1], fullText)
                if (isValidDocNumber(v)) cNum = v
            }
            if (cNum.isEmpty()) {
                val nm = PARENT_NUM.find(window, pos)
                if (nm != null) cNum = finalizeNumber(nm.groupValues[1], fullText)
            }
            if (cNum.isNotEmpty()) {
                val d = scanDates(window.substring(pos), defaultYear, 2000, curYear + 1).firstOrNull()
                if (d != null) cDate = d.value
                break
            }
        }

        if (cDate.isEmpty()) cDate = specDate
        return Pair(cNum, cDate)
    }
}
