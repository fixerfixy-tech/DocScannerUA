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
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
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

    private lateinit var settings: SettingsManager
    private lateinit var rvDocuments: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvHeaderSub: TextView
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

        settings = SettingsManager(this)

        rvDocuments = findViewById(R.id.rvDocuments)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        progressBar = findViewById(R.id.progressBar)
        tvHeaderSub = findViewById(R.id.tvHeaderSub)

        val fabScan = findViewById<ExtendedFloatingActionButton>(R.id.fabScan)
        val btnSettings = findViewById<android.view.View>(R.id.btnSettings)

        updateHeaderInfo()

        thread {
            OcrManager.init(applicationContext)
        }

        setupRecyclerView()
        loadDocuments()

        fabScan.setOnClickListener { launchScanner() }
        btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun updateHeaderInfo() {
        tvHeaderSub.text = "Папка: Documents/${settings.folderName}"
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

            val parsed = DocumentParser.parse(extractedText)

            runOnUiThread {
                progressBar.visibility = View.GONE
                showConfirmationSheet(pdfUri, parsed)
            }
        }
    }

    private fun showConfirmationSheet(sourceUri: Uri, parsed: ParsedDocument) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_review_document, null)
        val etType = view.findViewById<EditText>(R.id.etDocType)
        val etNumber = view.findViewById<EditText>(R.id.etDocNumber)
        val etDate = view.findViewById<EditText>(R.id.etDocDate)
        val etFinalName = view.findViewById<EditText>(R.id.etFinalFileName)
        val btnViewRawOcr = view.findViewById<MaterialButton>(R.id.btnViewRawOcr)

        etType.setText(parsed.type)
        etNumber.setText(parsed.number)
        etDate.setText(parsed.date)

        fun updatePreview() {
            parsed.type = etType.text.toString().trim()
            parsed.number = etNumber.text.toString().trim()
            parsed.date = etDate.text.toString().trim()
            etFinalName.setText(parsed.buildFileName(settings.namingTemplate))
        }

        updatePreview()

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() }
            override fun afterTextChanged(s: Editable?) {}
        }

        etType.addTextChangedListener(watcher)
        etNumber.addTextChangedListener(watcher)
        etDate.addTextChangedListener(watcher)

        btnViewRawOcr.setOnClickListener {
            showRawOcrDialog(parsed.rawOcrText)
        }

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Зберегти") { _, _ ->
                val finalFileName = etFinalName.text.toString().trim()
                savePdf(sourceUri, finalFileName)
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun showRawOcrDialog(rawText: String) {
        val tv = TextView(this).apply {
            text = if (rawText.isBlank()) "Текст не розпізнано движком OCR" else rawText
            setPadding(40, 30, 40, 30)
            setTextIsSelectable(true)
            textSize = 12f
            setTextColor(0xFF333333.toInt())
        }
        val scroll = ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle("Розпізнаний текст (Tesseract OCR)")
            .setView(scroll)
            .setPositiveButton("Зрозуміло", null)
            .show()
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etFolder = view.findViewById<EditText>(R.id.etSettingFolder)
        val etTemplate = view.findViewById<EditText>(R.id.etSettingTemplate)

        etFolder.setText(settings.folderName)
        etTemplate.setText(settings.namingTemplate)

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Зберегти") { _, _ ->
                settings.folderName = etFolder.text.toString()
                settings.namingTemplate = etTemplate.text.toString()
                updateHeaderInfo()
                loadDocuments()
                Toast.makeText(this, "Налаштування збережено", Toast.LENGTH_SHORT).show()
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
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), settings.folderName)
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
                VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(200)
        }
    }
}
