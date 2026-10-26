package com.fixerfixy.docscanner

/** Мінімальне сховище «ключ-значення» (для тестів без Android). */
interface KeyValueStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/**
 * Налаштування розпізнавання.
 * Типово .txt вимкнено (не засмічуємо папку), а багатоканальний OCR увімкнено (точніше при печатках).
 */
class AppSettings(private val store: KeyValueStore) {

    var saveOcrText: Boolean
        get() = store.getBoolean(KEY_SAVE_OCR_TEXT, false)
        set(v) = store.putBoolean(KEY_SAVE_OCR_TEXT, v)

    var multiChannelOcr: Boolean
        get() = store.getBoolean(KEY_MULTI_CHANNEL, true)
        set(v) = store.putBoolean(KEY_MULTI_CHANNEL, v)

    /** Які режими зображення запускати. */
    fun channelModes(): List<ChannelMode> =
        if (multiChannelOcr) ChannelOrder.ALL else listOf(ChannelMode.ORIGINAL)

    companion object {
        const val PREFS_NAME = "docscanner_settings"
        const val KEY_SAVE_OCR_TEXT = "save_ocr_text"
        const val KEY_MULTI_CHANNEL = "multi_channel_ocr"

    }
}
