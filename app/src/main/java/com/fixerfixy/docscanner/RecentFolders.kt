package com.fixerfixy.docscanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** П'ять останніх папок, у які переміщували документи (для швидкого вибору). */
class RecentFolders(context: Context) {

    data class Entry(val uri: String, val name: String)

    private val prefs = context.getSharedPreferences("doc_scanner_recent_folders", Context.MODE_PRIVATE)

    fun list(): List<Entry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Entry(o.getString("uri"), o.optString("name", ""))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Додає папку на початок списку (дублікати прибираються). */
    fun add(uri: String, name: String) {
        val updated = listOf(Entry(uri, name)) + list().filter { it.uri != uri }
        save(updated.take(MAX))
    }

    fun remove(uri: String) {
        save(list().filter { it.uri != uri })
    }

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("uri", e.uri).put("name", e.name))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "recent"
        const val MAX = 5
    }
}
