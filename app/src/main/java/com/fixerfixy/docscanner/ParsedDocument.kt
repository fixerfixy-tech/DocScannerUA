package com.fixerfixy.docscanner

data class ParsedDocument(
    var type: String,
    var number: String,
    var date: String,
    val rawOcrText: String,
    val contractNumber: String = "",
    val contractDate: String = ""
) {
    fun buildStandardFileName(extension: String = "pdf"): String {
        val safeNum = number.ifBlank { "б-н" }.replace('/', '-')
        val safeDate = date.ifBlank { "не_вказано" }
        val ext = extension.lowercase().removePrefix(".")

        return if ((type == "Специфікація" || type == "Додаткова угода") && contractNumber.isNotBlank()) {
            val safeContNum = contractNumber.replace('/', '-')
            val safeContDate = contractDate.ifBlank { safeDate }
            "${type}_№${safeNum}_від_${safeDate}_до_Договору_№${safeContNum}_від_${safeContDate}_р.$ext"
        } else {
            val safeNumClean = safeNum.replace("№", "")
            "${type}_No_${safeNumClean}_від_${safeDate}_р.$ext"
        }
    }
}

data class ParseOutcome(
    val document: ParsedDocument,
    val confidence: Int = 0,
    val typeScore: Int = 0,
    val numberScore: Int = 0,
    val dateScore: Int = 0
) {
    val doc: ParsedDocument get() = document
    val confident: Boolean get() = confidence >= 150
    val typeSpecific: Boolean get() = document.type != "Документ"

    val type: String get() = document.type
    val number: String get() = document.number
    val date: String get() = document.date
    val rawOcrText: String get() = document.rawOcrText
    val score: Int get() = confidence
    val totalScore: Int get() = confidence
}
