package com.fixerfixy.docscanner

import android.graphics.Color
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Довге натискання вмикає режим вибору, далі короткі натискання додають/знімають документи.
 * Про зміну вибору повідомляє через onSelectionChanged (панель дій у BatchController).
 */
class DocumentAdapter(
    private val items: MutableList<ScannedDocItem>,
    private val onOpenClick: (ScannedDocItem) -> Unit,
    private val onSelectionChanged: (List<ScannedDocItem>) -> Unit = {}
) : RecyclerView.Adapter<DocumentAdapter.DocViewHolder>() {

    private val selectedKeys = linkedSetOf<String>()

    val isSelectionMode: Boolean
        get() = selectedKeys.isNotEmpty()

    init {
        // Після перезавантаження списку прибираємо з вибору файли, яких більше немає
        registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                val present = items.map { keyOf(it) }.toHashSet()
                if (selectedKeys.retainAll(present)) publishSelection()
            }
        })
    }

    private fun keyOf(item: ScannedDocItem): String = item.uri.toString()

    fun selectedItems(): List<ScannedDocItem> = items.filter { selectedKeys.contains(keyOf(it)) }

    private fun publishSelection() {
        onSelectionChanged(selectedItems())
    }

    inner class DocViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView? = view.findViewById(R.id.cardDocument)
        val tvName: TextView = view.findViewById(R.id.tvFileName)
        val tvNumber: TextView = view.findViewById(R.id.tvFileNumber)
        val tvMeta: TextView = view.findViewById(R.id.tvFileMeta)
        val cbSelect: CheckBox? = view.findViewById(R.id.cbSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_document, parent, false)
        // Прибираємо круглу галочку Material: вибір показує лише квадратний чекбокс
        (view as? MaterialCardView)?.checkedIcon = null
        return DocViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: DocViewHolder, position: Int) {
        if (position !in items.indices) return
        val item = items[position]

        // "Акт_No_123_від_01.01.2026_р.pdf" -> "Акт" + "№123.pdf" (номер і формат завжди видно)
        val m = NAME_PATTERN.find(item.name)
        if (m != null) {
            holder.tvName.text = m.groupValues[1]
            holder.tvName.ellipsize = TextUtils.TruncateAt.END
            holder.tvNumber.text = "№" + m.groupValues[2] + m.groupValues[3]
            holder.tvNumber.visibility = View.VISIBLE
        } else {
            holder.tvName.text = item.name
            holder.tvName.ellipsize = TextUtils.TruncateAt.MIDDLE
            holder.tvNumber.visibility = View.GONE
        }

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
            toggleSelection(item)
            true
        }
    }

    private fun toggleSelection(item: ScannedDocItem) {
        val key = keyOf(item)
        if (!selectedKeys.remove(key)) selectedKeys.add(key)
        publishSelection()
        notifyDataSetChanged()
    }

    fun toggleSelectAll() {
        if (selectedKeys.size == items.size) {
            selectedKeys.clear()
        } else {
            selectedKeys.clear()
            items.forEach { selectedKeys.add(keyOf(it)) }
        }
        publishSelection()
        notifyDataSetChanged()
    }

    fun clearSelection() {
        if (selectedKeys.isEmpty()) return
        selectedKeys.clear()
        publishSelection()
        notifyDataSetChanged()
    }

    companion object {
        /** тип _No_/№ номер _від_ ... .розширення */
        private val NAME_PATTERN = Regex("""^(.*?)_(?:No|№)_?(.+?)_від_.*?(\.[A-Za-z0-9]{2,5})$""")
    }
}
