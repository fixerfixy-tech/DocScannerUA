package com.fixerfixy.docscanner

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("doc_scanner_prefs", Context.MODE_PRIVATE)

    var folderUri: String?
        get() = prefs.getString("folder_uri", null)
        set(value) = prefs.edit().putString("folder_uri", value).apply()

    var folderDisplayName: String
        get() = prefs.getString("folder_display_name", "Documents/ScanDoc (за замовчуванням)") ?: "Documents/ScanDoc"
        set(value) = prefs.edit().putString("folder_display_name", value).apply()

    var autoSave: Boolean
        get() = prefs.getBoolean("auto_save", false)
        set(value) = prefs.edit().putBoolean("auto_save", value).apply()

    // Зберігати сирий OCR-текст у .txt поруч зі скан-файлом (для аналізу помилок розпізнавання)
    var saveOcrText: Boolean
        get() = prefs.getBoolean("save_ocr_text", false)
        set(value) = prefs.edit().putBoolean("save_ocr_text", value).apply()

    // 1. Якість (100, 200, 300 DPI)
    var dpi: Int
        get() = prefs.getInt("scan_dpi", 200)
        set(value) = prefs.edit().putInt("scan_dpi", value).apply()

    // 2. Колірна схема: COLOR, GRAYSCALE, BW
    var colorMode: String
        get() = prefs.getString("color_mode", "COLOR") ?: "COLOR"
        set(value) = prefs.edit().putString("color_mode", value).apply()

    // 3. Формат файлу: PDF, JPEG
    var fileFormat: String
        get() = prefs.getString("file_format", "PDF") ?: "PDF"
        set(value) = prefs.edit().putString("file_format", value).apply()

    fun resetFolder() {
        prefs.edit()
            .remove("folder_uri")
            .putString("folder_display_name", "Documents/ScanDoc (за замовчуванням)")
            .apply()
    }
}
