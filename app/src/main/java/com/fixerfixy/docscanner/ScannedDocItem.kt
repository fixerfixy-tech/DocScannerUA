package com.fixerfixy.docscanner

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class ScannedDocItem(
    val name: String,
    val uri: Uri,
    val sizeKb: Long,
    val dateModified: Long,
    val rawFile: File? = null,
    val docFile: DocumentFile? = null
)
