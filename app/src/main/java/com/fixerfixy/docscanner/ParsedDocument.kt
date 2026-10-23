package com.fixerfixy.docscanner

data class ParsedDocument(
    var type: String,
    var number: String,
    var date: String,
    val rawOcrText: String
) {
    fun buildFileName(template: String): String {
        val safeNum = number.ifBlank { "б-н" }.replace('/', '-')
        val safeDate = date.ifBlank { "не_вказано" }
        return template
            .replace("{type}", type)
            .replace("{number}", safeNum)
            .replace("{date}", safeDate)
    }
}
