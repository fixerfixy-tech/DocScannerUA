package com.fixerfixy.docscanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var rvDocuments: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var progressBar: ProgressBar
    private val docList = mutableListOf<File>()
    private lateinit var adapter: DocumentAdapter

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val gmsResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            gmsResult?.let { processScannedData(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rvDocuments = findViewById(R.id.rvDocuments)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        progressBar = findViewById(R.id.progressBar)
        val fabScan = findViewById<ExtendedFloatingActionButton>(R.id.fabScan)

        thread {
            OcrManager.init(applicationContext)
        }

        setupRecyclerView()
        loadDocuments()

        fabScan.setOnClickListener {
            launchScanner()
        }
    }

    private fun setupRecyclerView() {
        adapter = DocumentAdapter(
            docList,
            onOpenClick = { openFile(it) },
            onShareClick = { shareFile(it) },
            onDeleteClick = { confirmDelete(it) }
        )
        rvDocuments.layoutManager = LinearLayoutManager(this)
        rvDocuments.adapter = adapter
    }

    private fun loadDocuments() {
        val folder = getScanDocFolder()
        docList.clear()
        if (folder.exists()) {
            val files = folder.listFiles { file -> file.extension.equals("pdf", true) }
            if (files != null) {
                docList.addAll(files.sortedByDescending { it.lastModified() })
            }
        }
        adapter.notifyDataSetChanged()
        layoutEmpty.visibility = if (docList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun launchScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF)
            .setScannerMode(SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender: IntentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                Toast.makeText(this, "Помилка запуску: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun processScannedData(result: GmsDocumentScanningResult) {
        val pages = result.pages
        val pdfUri = result.pdf?.uri ?: return

        progressBar.visibility = View.VISIBLE

        thread {
            var extractedText = ""
            if (!pages.isNullOrEmpty()) {
                val firstPageUri = pages[0].imageUri
                try {
                    contentResolver.openInputStream(firstPageUri)?.use { input ->
                        val bitmap = BitmapFactory.decodeStream(input)
                        extractedText = OcrManager.extractText(bitmap)
                    }
                } catch (ignored: Exception) {}
            }

            val proposedName = DocumentParser.generateFileName(extractedText)

            runOnUiThread {
                progressBar.visibility = View.GONE
                showSaveConfirmationDialog(pdfUri, proposedName)
            }
        }
    }

    private fun showSaveConfirmationDialog(sourceUri: Uri, defaultName: String) {
        val input = EditText(this).apply {
            setText(defaultName.removeSuffix(".pdf"))
            setSelection(text.length)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Збереження документа")
            .setMessage("Перевірте розпізнану назву первинки:")
            .setView(container)
            .setPositiveButton("Зберегти") { _, _ ->
                val finalName = "${input.text.toString().trim()}.pdf"
                savePdf(sourceUri, finalName)
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun savePdf(sourceUri: Uri, fileName: String) {
        try {
            val docsFolder = getScanDocFolder()
            if (!docsFolder.exists()) docsFolder.mkdirs()

            var destFile = File(docsFolder, fileName)
            var counter = 1
            val baseName = fileName.removeSuffix(".pdf")
            while (destFile.exists()) {
                destFile = File(docsFolder, "${baseName}_$counter.pdf")
                counter++
            }

            contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            vibrate()
            Toast.makeText(this, "Збережено: ${destFile.name}", Toast.LENGTH_SHORT).show()
            loadDocuments()

        } catch (e: Exception) {
            Toast.makeText(this, "Помилка запису: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getScanDocFolder(): File {
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ScanDoc")
    }

    private fun openFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Встановіть переглядач PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Надіслати документ"))
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Видалити документ?")
            .setMessage("Файл ${file.name} буде видалено безповоротно.")
            .setPositiveButton("Видалити") { _, _ ->
                file.delete()
                loadDocuments()
            }
            .setNegativeButton("Ні", null)
            .show()
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(250)
        }
    }
}
