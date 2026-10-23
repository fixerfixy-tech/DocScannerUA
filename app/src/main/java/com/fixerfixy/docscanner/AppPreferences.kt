package com.fixerfixy.docscanner

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import java.io.File

object AppPreferences {
    private const val PREFS_NAME = "doc_scanner_settings"
    private const val KEY_FORMAT = "pref_format"
    private const val KEY_COLOR = "pref_color"
    private const val KEY_DPI = "pref_dpi"
    private const val KEY_CUSTOM_URI = "pref_custom_uri"
    private const val KEY_FOLDER_NAME = "pref_folder_name"

    fun getFormat(context: Context): String =
        getPrefs(context).getString(KEY_FORMAT, "pdf") ?: "pdf"

    fun setFormat(context: Context, format: String) {
        getPrefs(context).edit().putString(KEY_FORMAT, format).apply()
    }

    fun getColorMode(context: Context): String =
        getPrefs(context).getString(KEY_COLOR, "color") ?: "color"

    fun setColorMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_COLOR, mode).apply()
    }

    fun getDpi(context: Context): Int =
        getPrefs(context).getInt(KEY_DPI, 200)

    fun setDpi(context: Context, dpi: Int) {
        getPrefs(context).edit().putInt(KEY_DPI, dpi).apply()
    }

    fun getCustomTreeUri(context: Context): Uri? {
        val uriStr = getPrefs(context).getString(KEY_CUSTOM_URI, null)
        return uriStr?.let { Uri.parse(it) }
    }

    fun setCustomTreeUri(context: Context, uri: Uri?, displayName: String?) {
        getPrefs(context).edit()
            .putString(KEY_CUSTOM_URI, uri?.toString())
            .putString(KEY_FOLDER_NAME, displayName)
            .apply()
    }

    fun getFolderDisplayName(context: Context): String {
        return getPrefs(context).getString(KEY_FOLDER_NAME, null)
            ?: "Внутрішня пам'ять / Documents / ScanDoc"
    }

    fun getDefaultFolder(): File {
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ScanDoc")
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
