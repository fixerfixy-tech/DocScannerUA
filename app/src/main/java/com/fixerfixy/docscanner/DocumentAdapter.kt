package com.fixerfixy.docscanner

import android.app.AlertDialog
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocumentAdapter(
    private val items: MutableList<ScannedDocItem>,
    private val onOpenClick: (ScannedDocItem) -> Unit,
    private val onShareClick: (ScannedDocItem) -> Unit,
    private val onDeleteClick: (ScannedDocItem) -> Unit,
    private val onFilesChanged: () -> Unit = {}
) : RecyclerView.Adapter<DocumentAdapter.DocViewHolder>() {

    private val selectedKeys = linkedSetOf<String>()
    private var batchHelper: BatchDocumentHelper? = null

    val isSelectionMode: Boolean
        get() = selectedKeys.isNotEmpty()

    private fun keyOf(item: ScannedDocItem): String = item.uri.toString()

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val act = recyclerView.context as? ComponentActivity
        if (act != null && batchHelper == null) {
            batchHelper = BatchDocumentHelper(
                activity = act,
                onSelectionCleared = { clearSelection() },
                onSelectAllRequested = { toggleSelectAll() },
                onFilesChanged = {
                    selectedKeys.clear()
                    onFilesChanged()
                }
            )
        }
    }

    inner class DocViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView? = view.findViewById(R.id.cardDocument)
        val tvName: TextView = view.findViewById(R.id.tvFileName)
        val tvMeta: TextView = view.findViewById(R.id.tvFileMeta)
        val cbSelect: CheckBox? = view.findViewById(R.id.cbSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_document, parent, false)
        return DocViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: DocViewHolder, position: Int) {
        if (position !in items.indices) return
        val item = items[position]

        holder.tvName.text = item.name
        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(item.dateModified))
        val sizeKb = item.sizeKb.coerceAtLeast(1)
        holder.tvMeta.text = "$dateStr • $sizeKb КБ"

        val isSelected = selectedKeys.contains(keyOf(item))
        holder.cbSelect?.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        holder.cbSelect?.isChecked = isSelected

        holder.card?.let { card ->
            card.isChecked = isSelected
            card.setCardBackgroundColor(if (isSelected) Color.parseColor("#DBEAFE") else Color.WHITE)
            card.strokeColor = if (isSelected) Color.parseColor("#2563EB") else Color.parseColor("#E0E4EA")
            card.strokeWidth = if (isSelected) 4 else 2
        }

        holder.itemView.setOnClickListener {
            if (isSelectionMode) toggleSelection(item) else onOpenClick(item)
        }

        holder.itemView.setOnLongClickListener {
            if (item.rawFile != null) {
                toggleSelection(item)
            } else {
                showItemMenu(holder.itemView, item)
            }
            true
        }
    }

    private fun showItemMenu(anchor: View, item: ScannedDocItem) {
        AlertDialog.Builder(anchor.context)
            .setTitle(item.name)
            .setItems(arrayOf("Надіслати", "Видалити")) { _, which ->
                if (which == 0) onShareClick(item) else onDeleteClick(item)
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun toggleSelection(item: ScannedDocItem) {
        if (item.rawFile == null) return
        val key = keyOf(item)
        if (!selectedKeys.remove(key)) selectedKeys.add(key)
        syncSelectionUi()
    }

    private fun toggleSelectAll() {
        val selectable = items.filter { it.rawFile != null }
        if (selectedKeys.size == selectable.size) {
            selectedKeys.clear()
        } else {
            selectedKeys.clear()
            selectable.forEach { selectedKeys.add(keyOf(it)) }
        }
        syncSelectionUi()
    }

    fun clearSelection() {
        selectedKeys.clear()
        syncSelectionUi()
    }

    private fun syncSelectionUi() {
        val selectedFiles = items
            .filter { selectedKeys.contains(keyOf(it)) }
            .mapNotNull { it.rawFile }
        if (selectedFiles.isEmpty()) selectedKeys.clear()
        batchHelper?.updateSelection(selectedFiles)
        notifyDataSetChanged()
    }
}
