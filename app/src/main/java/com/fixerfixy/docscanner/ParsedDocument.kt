package com.fixerfixy.docscanner

data class ParsedDocument(
    var type: String,
    var number: String,
    var date: String,
    val rawOcrText: String
) {
    fun buildStandardFileName(): String {
        val safeNum = number.ifBlank { "б-н" }.replace('/', '-')
        val safeDate = date.ifBlank { "не_вказано" }
        return "${type}_No_${safeNum}_від_${safeDate}_р.pdf"
    }
}
