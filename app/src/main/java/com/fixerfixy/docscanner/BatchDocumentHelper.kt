package com.fixerfixy.docscanner

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File

class BatchDocumentHelper(
    private val activity: ComponentActivity,
    private val onSelectionCleared: () -> Unit,
    private val onSelectAllRequested: () -> Unit,
    private val onFilesChanged: () -> Unit
) {
    private var pendingFiles: List<File> = emptyList()
    private var isMoveOperation: Boolean = false

    private var actionBarContainer: LinearLayout? = null
    private var tvSelectedCount: TextView? = null

    private val folderPickerLauncher: ActivityResultLauncher<Uri?> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null && pendingFiles.isNotEmpty()) {
                executeCopyOrMoveToUri(treeUri, pendingFiles, isMoveOperation)
            }
        }

    init {
        attachContextualBarToScreen()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            activity.resources.displayMetrics
        ).toInt()
    }

    private fun attachContextualBarToScreen() {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = dp(12).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E3A5F"))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        // Верхній рядок: Лічильник виділених + кнопки "Усі" та "Скасувати (✕)"
        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val countText = TextView(activity).apply {
            text = "Обрано: 1"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvSelectedCount = countText

        val btnSelectAll = TextView(activity).apply {
            text = "Усі"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { onSelectAllRequested() }
        }

        val btnCancel = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(10), dp(4), dp(6), dp(4))
            setOnClickListener { onSelectionCleared() }
        }

        topRow.addView(countText)
        topRow.addView(btnSelectAll)
        topRow.addView(btnCancel)

        // Нижній рядок: 4 дії контекстного меню (Надіслати, Перемістити, Копіювати, Видалити)
        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(10)
            layoutParams = params
        }

        actionsRow.addView(createActionButton("📤 Надіслати", "#FFFFFF") {
            shareFiles(currentSelectedFiles)
        })
        actionsRow.addView(createActionButton("📁 Перемістити", "#FFFFFF") {
            promptCopyOrMove(currentSelectedFiles, move = true)
        })
        actionsRow.addView(createActionButton("📋 Копіювати", "#FFFFFF") {
            promptCopyOrMove(currentSelectedFiles, move = false)
        })
        actionsRow.addView(createActionButton("🗑 Видалити", "#FCA5A5") {
            confirmDeleteFiles(currentSelectedFiles)
        })

        container.addView(topRow)
        container.addView(actionsRow)

        val frameParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            setMargins(dp(10), dp(8), dp(10), dp(8))
        }

        rootView.addView(container, frameParams)
        actionBarContainer = container
    }

    private var currentSelectedFiles: List<File> = emptyList()

    private fun createActionButton(
        label: String,
        textColorHex: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            text = label
            setTextColor(Color.parseColor(textColorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2B4C7E"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
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
            tvSelectedCount?.text = "Обрано документів: ${selectedFiles.size}"
            bar.visibility = View.VISIBLE
            bar.bringToFront()
        }
    }

    fun showPopupContextMenu(anchor: View, selectedFiles: List<File>) {
        if (selectedFiles.isEmpty()) return
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 1, 0, "Надіслати (${selectedFiles.size})")
        popup.menu.add(0, 2, 1, "Перемістити (${selectedFiles.size})")
        popup.menu.add(0, 3, 2, "Копіювати (${selectedFiles.size})")
        popup.menu.add(0, 4, 3, "Видалити (${selectedFiles.size})")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> shareFiles(selectedFiles)
                2 -> promptCopyOrMove(selectedFiles, move = true)
                3 -> promptCopyOrMove(selectedFiles, move = false)
                4 -> confirmDeleteFiles(selectedFiles)
            }
            true
        }
        popup.show()
    }

    fun shareFiles(files: List<File>) {
        if (files.isEmpty()) return
        try {
            val authority = "${activity.packageName}.provider"
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

    private fun promptCopyOrMove(files: List<File>, move: Boolean) {
        if (files.isEmpty()) return
        pendingFiles = files.toList()
        isMoveOperation = move
        val actionTitle = if (move) "Оберіть папку для переміщення" else "Оберіть папку для копіювання"
        Toast.makeText(activity, actionTitle, Toast.LENGTH_SHORT).show()
        folderPickerLauncher.launch(null)
    }

    private fun executeCopyOrMoveToUri(treeUri: Uri, files: List<File>, move: Boolean) {
        val targetDir = DocumentFile.fromTreeUri(activity, treeUri)
        if (targetDir == null || !targetDir.canWrite()) {
            Toast.makeText(activity, "Немає доступу для запису в обрану папку", Toast.LENGTH_LONG).show()
            return
        }

        var successCount = 0
        for (srcFile in files) {
            if (!srcFile.exists()) continue
            try {
                // Якщо файл із таким ім'ям вже існує в цільовій папці — видаляємо старий перед записом
                targetDir.findFile(srcFile.name)?.delete()
                val destDoc = targetDir.createFile("application/pdf", srcFile.name) ?: continue
                activity.contentResolver.openOutputStream(destDoc.uri)?.use { outStream ->
                    srcFile.inputStream().use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }
                if (move) {
                    srcFile.delete()
                }
                successCount++
            } catch (_: Exception) {
            }
        }

        val verb = if (move) "Переміщено" else "Скопійовано"
        Toast.makeText(activity, "$verb документів: $successCount із ${files.size}", Toast.LENGTH_SHORT).show()
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
