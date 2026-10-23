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

        return if (type == "Специфікація" && contractNumber.isNotBlank()) {
            val safeContNum = contractNumber.replace('/', '-')
            val safeContDate = contractDate.ifBlank { safeDate }
            "Специфікація_№${safeNum}_від_${safeDate}_до_Договору_№${safeContNum}_від_${safeContDate}_р.$ext"
        } else {
            val safeNumClean = safeNum.replace("№", "")
            "${type}_No_${safeNumClean}_від_${safeDate}_р.$ext"
        }
    }
}
