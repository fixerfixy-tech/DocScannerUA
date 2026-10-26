package com.fixerfixy.docscanner

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.DocumentsContract
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import kotlin.concurrent.thread

/**
 * Панель групових дій (Надіслати / Перемістити / Видалити) та вся логіка навколо неї.
 * Створюється в MainActivity.onCreate після setContentView.
 */
class BatchController(
    private val activity: ComponentActivity,
    private val onDataChanged: () -> Unit
) {
    private var adapter: DocumentAdapter? = null
    private var selected: List<ScannedDocItem> = emptyList()
    private var pendingMove: List<ScannedDocItem> = emptyList()
    private val recent = RecentFolders(activity)

    private var bar: LinearLayout? = null
    private var tvCount: TextView? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            adapter?.clearSelection()
        }
    }

    private val folderPicker = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> onFolderPicked(uri) }

    init {
        buildBar()
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
    }

    fun attach(a: DocumentAdapter) {
        adapter = a
    }

    /** Викликається адаптером при кожній зміні вибору. */
    fun onSelectionChanged(items: List<ScannedDocItem>) {
        selected = items
        val has = items.isNotEmpty()
        backCallback.isEnabled = has
        bar?.visibility = if (has) View.VISIBLE else View.GONE
        if (has) {
            tvCount?.text = "Обрано: ${items.size}"
            bar?.bringToFront()
        }
        activity.findViewById<View>(R.id.fabScan)?.visibility = if (has) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------ Надіслати

    private fun share() {
        val items = selected
        if (items.isEmpty()) return
        try {
            val intent = BatchActions.buildShareIntent(items)
            activity.startActivity(Intent.createChooser(intent, "Надіслати документи (${items.size})"))
            adapter?.clearSelection()
        } catch (e: Exception) {
            toast("Помилка відправки: ${e.localizedMessage}")
        }
    }

    // ------------------------------------------------------------ Видалити

    private fun confirmDelete() {
        val items = selected
        if (items.isEmpty()) return
        AlertDialog.Builder(activity)
            .setTitle("Видалити документи?")
            .setMessage("Буде безповоротно видалено документів: ${items.size}.")
            .setPositiveButton("Видалити") { _, _ ->
                runInBackground("Видалення документів…") {
                    BatchActions.delete(items).message("Видалено", items.size)
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    // ------------------------------------------------------------ Перемістити

    private fun startMove() {
        val items = selected
        if (items.isEmpty()) return

        // Лише доступні папки; недоступні прибираємо зі списку
        val valid = recent.list().filter { e ->
            val ok = try {
                val d = DocumentFile.fromTreeUri(activity, Uri.parse(e.uri))
                d != null && d.exists() && d.canWrite()
            } catch (ex: Exception) {
                false
            }
            if (!ok) recent.remove(e.uri)
            ok
        }

        if (valid.isEmpty()) {
            pickFolder(items)
            return
        }

        val labels = valid.map { "📁 " + folderLabel(it) } + "📂 Обрати іншу папку…"
        AlertDialog.Builder(activity)
            .setTitle("Перемістити (${items.size}) у папку:")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == valid.size) {
                    pickFolder(items)
                } else {
                    val e = valid[which]
                    recent.add(e.uri, e.name)
                    runMove(items, Uri.parse(e.uri))
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun folderLabel(e: RecentFolders.Entry): String {
        val name = e.name.ifBlank { "Папка" }
        val path = try {
            DocumentsContract.getTreeDocumentId(Uri.parse(e.uri)).substringAfter(':', "")
        } catch (ex: Exception) {
            ""
        }
        return if (path.isBlank() || path == name) name else "$name\n$path"
    }

    private fun pickFolder(items: List<ScannedDocItem>) {
        pendingMove = items
        folderPicker.launch(null)
    }

    private fun onFolderPicked(uri: Uri?) {
        val items = pendingMove
        pendingMove = emptyList()
        if (uri == null || items.isEmpty()) return

        try {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // працюємо з тимчасовим доступом на цей сеанс
        }

        val name = (try {
            DocumentFile.fromTreeUri(activity, uri)?.name
        } catch (e: Exception) {
            null
        }) ?: "Папка"

        recent.add(uri.toString(), name)
        runMove(items, uri)
    }

    private fun runMove(items: List<ScannedDocItem>, targetTree: Uri) {
        runInBackground("Переміщення документів…") {
            BatchActions.move(activity, items, targetTree).message("Переміщено", items.size)
        }
    }

    // ------------------------------------------------------------ Допоміжне

    private fun runInBackground(progressText: String, work: () -> String) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            addView(ProgressBar(activity).apply { isIndeterminate = true })
            addView(TextView(activity).apply {
                text = progressText
                setPadding(dp(16), 0, 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(activity).setView(box).setCancelable(false).create()
        dialog.show()

        thread {
            val msg = try {
                work()
            } catch (e: Exception) {
                "Помилка: ${e.localizedMessage}"
            }
            activity.runOnUiThread {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                    // вікно вже закрито
                }
                toast(msg)
                adapter?.clearSelection()
                onDataChanged()
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            activity.resources.displayMetrics
        ).toInt()
    }

    // ------------------------------------------------------------ Панель знизу

    private fun buildBar() {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            isClickable = true
            elevation = dp(16).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E3A5F"))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val countText = TextView(activity).apply {
            text = "Обрано: 0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvCount = countText

        val btnAll = TextView(activity).apply {
            text = "Усі"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { adapter?.toggleSelectAll() }
        }

        val btnCancel = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(10), dp(4), dp(6), dp(4))
            setOnClickListener { adapter?.clearSelection() }
        }

        topRow.addView(countText)
        topRow.addView(btnAll)
        topRow.addView(btnCancel)

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        actionsRow.addView(actionButton("Надіслати", "#FFFFFF") { share() })
        actionsRow.addView(actionButton("Перемістити", "#FFFFFF") { startMove() })
        actionsRow.addView(actionButton("Видалити", "#FCA5A5") { confirmDelete() })

        container.addView(topRow)
        container.addView(actionsRow)

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply { setMargins(dp(10), dp(8), dp(10), dp(12)) }

        root.addView(container, lp)
        bar = container
    }

    private fun actionButton(label: String, textColorHex: String, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            text = label
            setTextColor(Color.parseColor(textColorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2B4C7E"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
            setOnClickListener { onClick() }
        }
    }
}
