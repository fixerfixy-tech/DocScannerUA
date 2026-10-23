package com.fixerfixy.docscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocumentAdapter(
    private val items: MutableList<File>,
    private val onOpenClick: (File) -> Unit,
    private val onShareClick: (File) -> Unit,
    private val onDeleteClick: (File) -> Unit
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
        val file = items[position]
        holder.tvName.text = file.name

        val sizeKb = file.length() / 1024
        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        holder.tvMeta.text = "$dateStr • $sizeKb КБ"

        holder.itemView.setOnClickListener { onOpenClick(file) }
        holder.btnShare.setOnClickListener { onShareClick(file) }
        holder.btnDelete.setOnClickListener { onDeleteClick(file) }
    }

    override fun getItemCount(): Int = items.size
}
