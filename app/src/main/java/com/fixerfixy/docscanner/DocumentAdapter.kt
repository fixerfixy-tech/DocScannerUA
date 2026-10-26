package com.fixerfixy.docscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocumentAdapter(
    private val items: MutableList<ScannedDocItem>,
    private val onOpenClick: (ScannedDocItem) -> Unit,
    private val onShareClick: (ScannedDocItem) -> Unit,
    private val onDeleteClick: (ScannedDocItem) -> Unit
) : RecyclerView.Adapter<DocumentAdapter.DocViewHolder>() {

    class DocViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDocName)
        val tvMeta: TextView = view.findViewById(R.id.tvDocMeta)
        val btnShare: MaterialButton = view.findViewById(R.id.btnShare)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_document, parent, false)
        return DocViewHolder(view)
    }

    override fun onBindViewHolder(holder: DocViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name

        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(item.dateModified))
        holder.tvMeta.text = "$dateStr • ${item.sizeKb} КБ"

        holder.itemView.setOnClickListener { onOpenClick(item) }
        holder.btnShare.setOnClickListener { onShareClick(item) }
        holder.btnDelete.setOnClickListener { onDeleteClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
