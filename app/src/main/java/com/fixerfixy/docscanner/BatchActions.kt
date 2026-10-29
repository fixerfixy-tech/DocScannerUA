package com.fixerfixy.docscanner

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/** Групові операції над списком ScannedDocItem (працюють і для SAF-папки, і для стандартної). */
object BatchActions {

    class Result(
        val done: Int,
        val skipped: Int,
        val failed: Int,
        val notDeleted: Int = 0
    ) {
        fun message(verb: String, total: Int): String {
            val sb = StringBuilder("$verb: $done з $total")
            if (skipped > 0) sb.append("; уже в цій папці: $skipped")
            if (failed > 0) sb.append("; помилок: $failed")
            if (notDeleted > 0) sb.append("; оригінали не видалено: $notDeleted")
            return sb.toString()
        }
    }

    fun mimeOf(name: String): String =
        if (name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)) "image/jpeg" else "application/pdf"

    fun buildShareIntent(items: List<ScannedDocItem>): Intent {
        val uris = ArrayList<Uri>(items.map { it.uri })
        val types = items.map { mimeOf(it.name) }.distinct()
        val mime = if (types.size == 1) types[0] else "*/*"

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }

        val clip = ClipData.newRawUri("documents", uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    fun delete(items: List<ScannedDocItem>): Result {
        var ok = 0
        var failed = 0
        for (item in items) {
            if (deleteSource(item)) {
                deleteCompanion(item)
                ok++
            } else {
                failed++
            }
        }
        return Result(ok, 0, failed)
    }

    /**
     * Копіює кожен файл у вибрану папку, перевіряє розмір і лише потім видаляє оригінал.
     * При помилці оригінал залишається, а недокопійований файл видаляється.
     */
    fun move(context: Context, items: List<ScannedDocItem>, targetTree: Uri): Result {
        val target = DocumentFile.fromTreeUri(context, targetTree)
        if (target == null || !target.isDirectory || !target.canWrite()) {
            return Result(0, 0, items.size)
        }

        val existing = HashSet<String>()
        for (f in target.listFiles()) {
            f.name?.let { existing.add(it.lowercase()) }
        }

        var done = 0
        var skipped = 0
        var failed = 0
        var notDeleted = 0

        for (item in items) {
            if (isSameFolder(item, target)) {
                skipped++
                continue
            }
            var dest: DocumentFile? = null
            try {
                val name = uniqueName(existing, item.name)
                dest = target.createFile(mimeOf(item.name), name) ?: throw IOException("createFile")
                copy(context, item.uri, dest.uri)

                val srcLen = sourceLength(item)
                if (srcLen > 0 && dest.length() != srcLen) throw IOException("size mismatch")

                existing.add((dest.name ?: name).lowercase())
                copyCompanion(context, item, target, dest.name ?: name, existing)
                if (!deleteSource(item)) notDeleted++ else deleteCompanion(item)
                done++
            } catch (e: Exception) {
                try {
                    dest?.delete()
                } catch (e2: Exception) {
                    // ігноруємо
                }
                failed++
            }
        }
        return Result(done, skipped, failed, notDeleted)
    }

    /** .txt з розпізнаним текстом поряд зі скан-файлом (якщо є): іде разом із ним. */
    private fun companionDoc(item: ScannedDocItem): DocumentFile? {
        return try {
            item.docFile?.parentFile?.findFile(OcrTextExporter.txtNameFor(item.name))
        } catch (e: Exception) {
            null
        }
    }

    private fun companionRaw(item: ScannedDocItem): File? {
        val parent = item.rawFile?.parentFile ?: return null
        val f = File(parent, OcrTextExporter.txtNameFor(item.name))
        return if (f.exists()) f else null
    }

    private fun deleteCompanion(item: ScannedDocItem) {
        try {
            companionDoc(item)?.delete()
            companionRaw(item)?.delete()
        } catch (e: Exception) {
            // .txt - допоміжний файл: його помилка не впливає на результат
        }
    }

    private fun copyCompanion(context: Context, item: ScannedDocItem, target: DocumentFile, destName: String, existing: MutableSet<String>) {
        var txtDest: DocumentFile? = null
        try {
            val srcUri = companionDoc(item)?.uri
                ?: companionRaw(item)?.let { Uri.fromFile(it) }
                ?: return
            val txtName = uniqueName(existing, OcrTextExporter.txtNameFor(destName))
            txtDest = target.createFile("text/plain", txtName) ?: return
            copy(context, srcUri, txtDest.uri)
            existing.add((txtDest.name ?: txtName).lowercase())
        } catch (e: Exception) {
            try {
                txtDest?.delete()
            } catch (e2: Exception) {
                // ігноруємо
            }
        }
    }

    private fun deleteSource(item: ScannedDocItem): Boolean {
        return try {
            item.docFile?.delete() ?: item.rawFile?.delete() ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun sourceLength(item: ScannedDocItem): Long {
        return try {
            item.docFile?.length() ?: item.rawFile?.length() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun uniqueName(existing: Set<String>, name: String): String {
        if (!existing.contains(name.lowercase())) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "${base}_$i$ext"
            if (!existing.contains(candidate.lowercase())) return candidate
            i++
        }
    }

    private fun copy(context: Context, src: Uri, dst: Uri) {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(src) ?: throw IOException("open source")
        input.use { ins ->
            val output = resolver.openOutputStream(dst) ?: throw IOException("open target")
            output.use { outs -> ins.copyTo(outs) }
        }
    }

    /** Чи лежить файл уже в цільовій папці (тоді його не чіпаємо). */
    private fun isSameFolder(item: ScannedDocItem, target: DocumentFile): Boolean {
        return try {
            val targetId = DocumentsContract.getDocumentId(target.uri)
            val parent = item.docFile?.parentFile
            if (parent != null) {
                DocumentsContract.getDocumentId(parent.uri) == targetId
            } else {
                val rawParent = item.rawFile?.parentFile ?: return false
                if (!targetId.startsWith("primary:")) return false
                val p = File(Environment.getExternalStorageDirectory(), targetId.removePrefix("primary:"))
                p.canonicalPath == rawParent.canonicalPath
            }
        } catch (e: Exception) {
            false
        }
    }
}
