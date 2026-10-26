package com.fixerfixy.docscanner

import android.content.Context
import android.content.SharedPreferences

class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

/** Отримати налаштування з Context: AppSettingsProvider.get(context) */
object AppSettingsProvider {
    fun get(context: Context): AppSettings =
        AppSettings(SharedPrefsStore(context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)))
}
