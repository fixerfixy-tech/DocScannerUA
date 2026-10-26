package com.fixerfixy.docscanner

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocumentAdapter(
    private val items: MutableList<ScannedDocItem>,
    private val onOpenClick: (ScannedDocItem) -> Unit,
    private val onShareClick: (ScannedDocItem) -> Unit,
    private val onDeleteClick: (ScannedDocItem) -> Unit
) : RecyclerView.Adapter<DocumentAdapter.DocViewHolder>() {

    private val selectedPaths = linkedSetOf<String>()
    private var batchHelper: BatchDocumentHelper? = null
    private var attachedRecyclerView: RecyclerView? = null

    val isSelectionMode: Boolean
        get() = selectedPaths.isNotEmpty()

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
        val act = recyclerView.context as? ComponentActivity
        if (act != null && batchHelper == null) {
            batchHelper = BatchDocumentHelper(
                activity = act,
                onSelectionCleared = { clearSelection() },
                onSelectAllRequested = { toggleSelectAll() },
                onFilesChanged = { refreshAfterBatchChange() }
            )
        }
    }

    inner class DocViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView? = view.findViewById(R.id.cardDocument)
        val tvName: TextView = view.findViewById(R.id.tvFileName)
        val tvMeta: TextView = view.findViewById(R.id.tvFileMeta)
        val cbSelect: CheckBox? = view.findViewById(R.id.cbSelect)
        val btnShare: ImageButton? = view.findViewById(R.id.btnShare)
        val btnDelete: ImageButton? = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_document, parent, false)
        return DocViewHolder(view)
    }

    private fun getFileList(): MutableList<File> {
        for (field in this::class.java.declaredFields) {
            field.isAccessible = true
            val v = field.get(this)
            if (v is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                return v as MutableList<File>
            }
        }
        return mutableListOf()
    }

    override fun getItemCount(): Int = getFileList().size

    override fun onBindViewHolder(holder: DocViewHolder, position: Int) {
        val fileList = getFileList()
        if (position !in fileList.indices) return
        val file = fileList[position]

        holder.tvName.text = file.name
        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        val sizeKb = (file.length() / 1024).coerceAtLeast(1)
        holder.tvMeta.text = "$dateStr • $sizeKb КБ"

        // Завжди ховаємо старі індивідуальні кнопки
        holder.btnShare?.visibility = View.GONE
        holder.btnDelete?.visibility = View.GONE

        val isSelected = selectedPaths.contains(file.absolutePath)
        holder.cbSelect?.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        holder.cbSelect?.isChecked = isSelected

        holder.card?.let { card ->
            card.isChecked = isSelected
            card.setCardBackgroundColor(
                if (isSelected) Color.parseColor("#DBEAFE") else Color.WHITE
            )
            card.strokeColor = if (isSelected) Color.parseColor("#2563EB") else Color.parseColor("#E0E4EA")
            card.strokeWidth = if (isSelected) 4 else 2
        }

        holder.itemView.setOnClickListener {
            if (isSelectionMode) {
                toggleSelection(file)
            } else {
                invokeOpenFile(file)
            }
        }

        holder.itemView.setOnLongClickListener {
            toggleSelection(file)
            true
        }
    }

    private fun invokeOpenFile(file: File) {
        for (field in this::class.java.declaredFields) {
            field.isAccessible = true
            val fn = field.get(this)
            if (fn is Function1<*, *>) {
                val name = field.name.lowercase()
                if ("open" in name || "click" in name || "item" in name) {
                    @Suppress("UNCHECKED_CAST")
                    (fn as Function1<File, Unit>).invoke(file)
                    return
                }
            }
        }
    }

    private fun toggleSelection(file: File) {
        val path = file.absolutePath
        if (selectedPaths.contains(path)) {
            selectedPaths.remove(path)
        } else {
            selectedPaths.add(path)
        }
        syncSelectionUi()
    }

    private fun toggleSelectAll() {
        val allFiles = getFileList()
        if (selectedPaths.size == allFiles.size) {
            selectedPaths.clear()
        } else {
            selectedPaths.clear()
            allFiles.forEach { selectedPaths.add(it.absolutePath) }
        }
        syncSelectionUi()
    }

    fun clearSelection() {
        selectedPaths.clear()
        syncSelectionUi()
    }

    private fun refreshAfterBatchChange() {
        val list = getFileList()
        val existing = list.filter { it.exists() }
        list.clear()
        list.addAll(existing)
        notifyDataSetChanged()
    }

    private fun syncSelectionUi() {
        val allFiles = getFileList()
        val selectedFiles = allFiles.filter { selectedPaths.contains(it.absolutePath) }
        if (selectedFiles.isEmpty()) {
            selectedPaths.clear()
        }
        batchHelper?.updateSelection(selectedFiles)
        notifyDataSetChanged()
    }
}
