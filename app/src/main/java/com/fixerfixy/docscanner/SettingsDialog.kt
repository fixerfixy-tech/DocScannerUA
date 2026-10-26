package com.fixerfixy.docscanner

import android.app.AlertDialog
import android.content.Context
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

/** Діалог налаштувань розпізнавання без XML-розмітки (щоб не чіпати ваші layout). */
object SettingsDialog {

    fun show(context: Context, onChanged: () -> Unit = {}) {
        val settings = AppSettingsProvider.get(context)
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val cbTxt = CheckBox(context).apply {
            text = "Зберігати текст OCR у .txt поруч із PDF"
            isChecked = settings.saveOcrText
        }
        val cbMulti = CheckBox(context).apply {
            text = "Покращене розпізнавання при печатках (кілька проходів)"
            isChecked = settings.multiChannelOcr
        }
        val hint = TextView(context).apply {
            text = "Покращене розпізнавання: якщо перший прохід не впевнений, скан додатково обробляється " +
                "без синього/червоного чорнила печаток. Це може збільшити час розпізнавання."
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        }
        box.addView(cbTxt)
        box.addView(cbMulti)
        box.addView(hint)

        AlertDialog.Builder(context)
            .setTitle("Налаштування розпізнавання")
            .setView(box)
            .setPositiveButton("Зберегти") { _, _ ->
                settings.saveOcrText = cbTxt.isChecked
                settings.multiChannelOcr = cbMulti.isChecked
                onChanged()
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }
}
