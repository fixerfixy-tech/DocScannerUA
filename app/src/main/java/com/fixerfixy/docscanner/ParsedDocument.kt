package com.fixerfixy.docscanner

data class ParsedDocument(
    var type: String,
    var number: String,
    var date: String,
    val rawOcrText: String
) {
    fun buildStandardFileName(extension: String = "pdf"): String {
        val safeNum = number.ifBlank { "б-н" }.replace('/', '-')
        val safeDate = date.ifBlank { "не_вказано" }
        val ext = extension.lowercase().removePrefix(".")
        return "${type}_No_${safeNum}_від_${safeDate}_р.$ext"
    }
}
