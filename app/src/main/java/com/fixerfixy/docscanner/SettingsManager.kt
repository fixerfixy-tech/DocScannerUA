package com.fixerfixy.docscanner

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("doc_scanner_prefs", Context.MODE_PRIVATE)

    var folderUri: String?
        get() = prefs.getString("folder_uri", null)
        set(value) {
            prefs.edit().putString("folder_uri", value).apply()
        }

    var folderDisplayName: String
        get() = prefs.getString("folder_display_name", "Documents/ScanDoc (за замовчуванням)") ?: "Documents/ScanDoc"
        set(value) {
            prefs.edit().putString("folder_display_name", value).apply()
        }

    var autoSave: Boolean
        get() = prefs.getBoolean("auto_save", false)
        set(value) {
            prefs.edit().putBoolean("auto_save", value).apply()
        }

    fun resetFolder() {
        prefs.edit()
            .remove("folder_uri")
            .putString("folder_display_name", "Documents/ScanDoc (за замовчуванням)")
            .apply()
    }
}
