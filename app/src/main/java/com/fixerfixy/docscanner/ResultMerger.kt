package com.fixerfixy.docscanner

/** Злиття результатів розбору кількох варіантів OCR одного скану. */
object ResultMerger {

    private const val NO_NUMBER = "б-н"
    private const val NO_DATE = "не_вказано"

    fun merge(outcomes: List<ParseOutcome>, labels: List<String> = emptyList()): ParsedDocument {
        require(outcomes.isNotEmpty())
        if (outcomes.size == 1) return outcomes[0].doc

        // Тип: специфічний тип важить більше за загальний
        val typeVotes = LinkedHashMap<String, Int>()
        for (o in outcomes) {
            val w = if (o.typeSpecific) 3 else 1
            typeVotes[o.doc.type] = (typeVotes[o.doc.type] ?: 0) + w
        }
        val type = typeVotes.maxByOrNull { it.value }!!.key

        val number = pickBest(outcomes.filter { it.doc.number != NO_NUMBER }.map { it.doc.number to it.numberScore })
            ?: NO_NUMBER
        val date = pickBest(outcomes.filter { it.doc.date != NO_DATE }.map { it.doc.date to it.dateScore })
            ?: NO_DATE

        val withContract = outcomes.firstOrNull { it.doc.type == type && it.doc.contractNumber.isNotBlank() }
            ?: outcomes.firstOrNull { it.doc.contractNumber.isNotBlank() }

        val combinedText = combineTexts(outcomes.map { it.doc.rawOcrText }, labels)

        return ParsedDocument(
            type = type,
            number = number,
            date = date,
            rawOcrText = combinedText,
            contractNumber = withContract?.doc?.contractNumber ?: "",
            contractDate = withContract?.doc?.contractDate ?: ""
        )
    }

    /** Значення з найбільшою сумою балів; кожен додатковий збіг дає +15. */
    private fun pickBest(items: List<Pair<String, Int>>): String? {
        if (items.isEmpty()) return null
        val sums = LinkedHashMap<String, Int>()
        val counts = HashMap<String, Int>()
        for ((v, s) in items) {
            sums[v] = (sums[v] ?: 0) + s
            counts[v] = (counts[v] ?: 0) + 1
        }
        return sums.maxByOrNull { (v, s) -> s + 15 * ((counts[v] ?: 1) - 1) }?.key
    }

    fun combineTexts(texts: List<String>, labels: List<String>): String {
        val distinct = texts.withIndex().filter { it.value.isNotBlank() }
        if (distinct.size <= 1) return distinct.firstOrNull()?.value ?: (texts.firstOrNull() ?: "")
        val sb = StringBuilder()
        for ((i, t) in distinct) {
            val label = labels.getOrNull(i) ?: "варіант ${i + 1}"
            sb.append("===== ").append(label).append(" =====\n").append(t.trim()).append("\n\n")
        }
        return sb.toString().trimEnd()
    }
}
