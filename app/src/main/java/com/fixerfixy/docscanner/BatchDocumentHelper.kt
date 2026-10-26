package com.fixerfixy.docscanner

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File

class BatchDocumentHelper(
    private val activity: Activity,
    private val onSelectionCleared: () -> Unit,
    private val onSelectAllRequested: () -> Unit,
    private val onFilesChanged: () -> Unit
) {
    private var actionBarContainer: LinearLayout? = null
    private var tvSelectedCount: TextView? = null
    private var currentSelectedFiles: List<File> = emptyList()

    init {
        // Відкладаємо ініціалізацію панелі на один цикл подій, щоб гарантувати готовність UI активності
        Handler(Looper.getMainLooper()).post {
            try {
                attachContextualBarToScreen()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun dp(value: Int): Int {
        return try {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                activity.resources.displayMetrics
            ).toInt()
        } catch (_: Exception) {
            value * 2
        }
    }

    private fun attachContextualBarToScreen() {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val existing = rootView.findViewWithTag<View>("CTX_ACTION_BAR")
        if (existing != null) {
            try {
                rootView.removeView(existing)
            } catch (_: Exception) {}
        }

        val container = LinearLayout(activity).apply {
            tag = "CTX_ACTION_BAR"
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
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
            text = "Обрано: 1"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvSelectedCount = countText

        val btnSelectAll = TextView(activity).apply {
            text = "Усі"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { 
                try { onSelectAllRequested() } catch (_: Exception) {}
            }
        }

        val btnCancel = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(10), dp(4), dp(6), dp(4))
            setOnClickListener { 
                try { onSelectionCleared() } catch (_: Exception) {}
            }
        }

        topRow.addView(countText)
        topRow.addView(btnSelectAll)
        topRow.addView(btnCancel)

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        actionsRow.addView(createActionButton("Надіслати", "#FFFFFF") {
            try { shareFiles(currentSelectedFiles) } catch (_: Exception) {}
        })
        actionsRow.addView(createActionButton("Перемістити", "#FFFFFF") {
            try { showFolderSelectDialog(currentSelectedFiles, move = true) } catch (_: Exception) {}
        })
        actionsRow.addView(createActionButton("Копіювати", "#FFFFFF") {
            try { showFolderSelectDialog(currentSelectedFiles, move = false) } catch (_: Exception) {}
        })
        actionsRow.addView(createActionButton("Видалити", "#FCA5A5") {
            try { confirmDeleteFiles(currentSelectedFiles) } catch (_: Exception) {}
        })

        container.addView(topRow)
        container.addView(actionsRow)

        val frameParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(10), dp(8), dp(10), dp(8))
        }

        rootView.addView(container, frameParams)
        actionBarContainer = container
    }

    private fun createActionButton(
        label: String,
        textColorHex: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            text = label
            setTextColor(Color.parseColor(textColorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
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

    fun updateSelection(selectedFiles: List<File>) {
        currentSelectedFiles = selectedFiles
        val bar = actionBarContainer ?: return
        if (selectedFiles.isEmpty()) {
            bar.visibility = View.GONE
        } else {
            tvSelectedCount?.text = "Обрано: ${selectedFiles.size}"
            bar.visibility = View.VISIBLE
            bar.bringToFront()
        }
    }

    fun shareFiles(files: List<File>) {
        if (files.isEmpty()) return
        try {
            val authority = "${activity.packageName}.fileprovider"
            val uris = ArrayList<Uri>(
                files.map { FileProvider.getUriForFile(activity, authority, it) }
            )

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/pdf"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            activity.startActivity(Intent.createChooser(intent, "Надіслати документи (${files.size})"))
            onSelectionCleared()
        } catch (e: Exception) {
            Toast.makeText(activity, "Помилка відправки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFolderSelectDialog(files: List<File>, move: Boolean) {
        if (files.isEmpty()) return
        val baseDir = files.first().parentFile ?: activity.getExternalFilesDir(null) ?: activity.filesDir
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        val folderCandidates = linkedSetOf<File>()
        baseDir.parentFile?.let { folderCandidates.add(it) }
        baseDir.listFiles()?.filter { it.isDirectory }?.forEach { folderCandidates.add(it) }
        if (docsDir != null) {
            folderCandidates.add(File(docsDir, "ScanDoc"))
            folderCandidates.add(docsDir)
        }
        if (downloadsDir != null) {
            folderCandidates.add(downloadsDir)
        }

        val dirList = folderCandidates.toList()
        val labels = dirList.map { "📁 ${it.name} (${it.absolutePath})" }.toMutableList()
        labels.add("➕ Створити нову підпапку...")

        val title = if (move) "Перемістити (${files.size}) у папку:" else "Копіювати (${files.size}) у папку:"

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == dirList.size) {
                    promptNewSubfolder(baseDir, files, move)
                } else {
                    executeCopyOrMove(dirList[which], files, move)
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun promptNewSubfolder(baseDir: File, files: List<File>, move: Boolean) {
        val input = EditText(activity).apply {
            hint = "Назва нової папки"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(activity)
            .setTitle("Нова папка")
            .setView(input)
            .setPositiveButton("ОК") { _, _ ->
                val folderName = input.text.toString().trim()
                if (folderName.isNotEmpty()) {
                    val target = File(baseDir, folderName)
                    executeCopyOrMove(target, files, move)
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun executeCopyOrMove(targetDir: File, files: List<File>, move: Boolean) {
        if (!targetDir.exists()) targetDir.mkdirs()
        var count = 0
        for (src in files) {
            if (!src.exists()) continue
            try {
                val dest = File(targetDir, src.name)
                if (dest.absolutePath == src.absolutePath) continue
                src.copyTo(dest, overwrite = true)
                if (move) src.delete()
                count++
            } catch (_: Exception) {
            }
        }
        val verb = if (move) "Переміщено" else "Скопійовано"
        Toast.makeText(activity, "$verb документів: $count", Toast.LENGTH_SHORT).show()
        onSelectionCleared()
        onFilesChanged()
    }

    private fun confirmDeleteFiles(files: List<File>) {
        if (files.isEmpty()) return
        AlertDialog.Builder(activity)
            .setTitle("Видалити документи?")
            .setMessage("Ви дійсно бажаєте видалити обрані документи (${files.size} шт.)?")
            .setPositiveButton("Видалити") { _, _ ->
                var deleted = 0
                for (f in files) {
                    if (f.exists() && f.delete()) deleted++
                }
                Toast.makeText(activity, "Видалено документів: $deleted", Toast.LENGTH_SHORT).show()
                onSelectionCleared()
                onFilesChanged()
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }
}
