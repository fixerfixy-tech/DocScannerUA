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
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
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
    private val docList = mutableListOf<ScannedDocItem>()
    private lateinit var adapter: DocumentAdapter

    private var settingsDialogView: View? = null

    // Лаунчер системного вибору папки (SAF)
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)

                val docDir = DocumentFile.fromTreeUri(this, uri)
                val folderName = docDir?.name ?: "Обрана папка"

                settings.folderUri = uri.toString()
                settings.folderDisplayName = folderName

                updateHeaderInfo()
                settingsDialogView?.findViewById<TextView>(R.id.tvCurrentFolder)?.text = folderName
                loadDocuments()

                Toast.makeText(this, "Папку встановлено: $folderName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Помилка надання доступу: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

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
        val btnSettings = findViewById<View>(R.id.btnSettings)

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
        tvHeaderSub.text = "Папка: ${settings.folderDisplayName}"
    }

    private fun setupRecyclerView() {
        adapter = DocumentAdapter(
            docList,
            onOpenClick = { openDocument(it) },
            onShareClick = { shareDocument(it) },
            onDeleteClick = { confirmDelete(it) }
        )
        rvDocuments.layoutManager = LinearLayoutManager(this)
        rvDocuments.adapter = adapter
    }

    private fun loadDocuments() {
        docList.clear()
        val customUriStr = settings.folderUri

        if (customUriStr != null) {
            try {
                val treeUri = Uri.parse(customUriStr)
                val dir = DocumentFile.fromTreeUri(this, treeUri)
                if (dir != null && dir.exists()) {
                    val files = dir.listFiles()
                    for (f in files) {
                        if (f.isFile && f.name?.endsWith(".pdf", true) == true) {
                            docList.add(
                                ScannedDocItem(
                                    name = f.name ?: "document.pdf",
                                    uri = f.uri,
                                    sizeKb = f.length() / 1024,
                                    dateModified = f.lastModified(),
                                    docFile = f
                                )
                            )
                        }
                    }
                }
            } catch (ignored: Exception) {}
        } else {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ScanDoc")
            if (folder.exists()) {
                val files = folder.listFiles { file -> file.extension.equals("pdf", true) }
                if (files != null) {
                    for (f in files) {
                        val contentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)
                        docList.add(
                            ScannedDocItem(
                                name = f.name,
                                uri = contentUri,
                                sizeKb = f.length() / 1024,
                                dateModified = f.lastModified(),
                                rawFile = f
                            )
                        )
                    }
                }
            }
        }

        docList.sortByDescending { it.dateModified }
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
                if (settings.autoSave) {
                    savePdf(pdfUri, parsed.buildStandardFileName())
                } else {
                    showConfirmationSheet(pdfUri, parsed)
                }
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
            etFinalName.setText(parsed.buildStandardFileName())
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
        settingsDialogView = view

        val tvCurrentFolder = view.findViewById<TextView>(R.id.tvCurrentFolder)
        val btnChooseFolder = view.findViewById<MaterialButton>(R.id.btnChooseFolder)
        val btnResetFolder = view.findViewById<MaterialButton>(R.id.btnResetFolder)
        val switchAutoSave = view.findViewById<SwitchMaterial>(R.id.switchAutoSave)

        tvCurrentFolder.text = settings.folderDisplayName
        switchAutoSave.isChecked = settings.autoSave

        btnChooseFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        btnResetFolder.setOnClickListener {
            settings.resetFolder()
            tvCurrentFolder.text = settings.folderDisplayName
            updateHeaderInfo()
            loadDocuments()
            Toast.makeText(this, "Скинуто до стандартної папки", Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Готово") { _, _ ->
                settings.autoSave = switchAutoSave.isChecked
            }
            .setOnDismissListener {
                settingsDialogView = null
            }
            .show()
    }

    private fun savePdf(sourceUri: Uri, fileName: String) {
        try {
            val customUriStr = settings.folderUri

            if (customUriStr != null) {
                val treeUri = Uri.parse(customUriStr)
                val dir = DocumentFile.fromTreeUri(this, treeUri)
                if (dir != null && dir.canWrite()) {
                    var finalName = fileName
                    var counter = 1
                    val baseName = fileName.removeSuffix(".pdf")
                    while (dir.findFile(finalName) != null) {
                        finalName = "${baseName}_$counter.pdf"
                        counter++
                    }

                    val createdFile = dir.createFile("application/pdf", finalName)
                    if (createdFile != null) {
                        contentResolver.openInputStream(sourceUri)?.use { input ->
                            contentResolver.openOutputStream(createdFile.uri)?.use { output ->
                                input.copyTo(output)
                            }
                        }
                        vibrate()
                        Toast.makeText(this, "Збережено: $finalName", Toast.LENGTH_SHORT).show()
                        loadDocuments()
                        return
                    }
                }
            }

            // Збереження за замовчуванням у Documents/ScanDoc
            val docsFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ScanDoc")
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

    private fun openDocument(item: ScannedDocItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Встановіть переглядач PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDocument(item: ScannedDocItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Надіслати документ"))
    }

    private fun confirmDelete(item: ScannedDocItem) {
        AlertDialog.Builder(this)
            .setTitle("Видалити документ?")
            .setMessage("Файл ${item.name} буде видалено безповоротно.")
            .setPositiveButton("Видалити") { _, _ ->
                val deleted = item.docFile?.delete() ?: item.rawFile?.delete() ?: false
                if (deleted) {
                    Toast.makeText(this, "Файл видалено", Toast.LENGTH_SHORT).show()
                }
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
