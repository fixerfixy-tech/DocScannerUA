package com.fixerfixy.docscanner

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("doc_scanner_prefs", Context.MODE_PRIVATE)

    var folderName: String
        get() = prefs.getString("folder_name", "ScanDoc") ?: "ScanDoc"
        set(value) {
            prefs.edit().putString("folder_name", value.trim()).apply()
        }

    var namingTemplate: String
        get() = prefs.getString("naming_template", "{type}_No_{number}_від_{date}_р.pdf") ?: "{type}_No_{number}_від_{date}_р.pdf"
        set(value) {
            prefs.edit().putString("naming_template", value.trim()).apply()
        }
}
