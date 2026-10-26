package com.fixerfixy.docscanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
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
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private lateinit var batch: BatchController

    private var settingsDialogView: View? = null

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
                Toast.makeText(this, "Помилка доступу: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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

        batch = BatchController(this) { loadDocuments() }
        setupRecyclerView()
        loadDocuments()

        fabScan.setOnClickListener { launchScanner() }
        btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun updateHeaderInfo() {
        val format = settings.fileFormat
        val dpi = settings.dpi
        tvHeaderSub.text = "${settings.folderDisplayName} • $format • ${dpi}DPI"
    }

    private fun setupRecyclerView() {
        adapter = DocumentAdapter(
            docList,
            onOpenClick = { openDocument(it) },
            onSelectionChanged = { batch.onSelectionChanged(it) }
        )
        batch.attach(adapter)
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
                        if (f.isFile && (f.name?.endsWith(".pdf", true) == true || f.name?.endsWith(".jpg", true) == true || f.name?.endsWith(".jpeg", true) == true)) {
                            docList.add(
                                ScannedDocItem(
                                    name = f.name ?: "doc",
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
                val files = folder.listFiles { file ->
                    val ext = file.extension
                    ext.equals("pdf", true) || ext.equals("jpg", true) || ext.equals("jpeg", true)
                }
                if (files != null) {
                    for (f in files) {
                        val contentUri = try { FileProvider.getUriForFile(this, "${packageName}.fileprovider", f) } catch (e: Exception) { continue }
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
        if (pages.isNullOrEmpty()) return

        progressBar.visibility = View.VISIBLE

        thread {
            var extractedText = ""
            val rawBitmaps = mutableListOf<Bitmap>()

            for (page in pages) {
                try {
                    contentResolver.openInputStream(page.imageUri)?.use { input ->
                        val bmp = BitmapFactory.decodeStream(input)
                        if (bmp != null) rawBitmaps.add(bmp)
                    }
                } catch (ignored: Exception) {}
            }

            if (rawBitmaps.isNotEmpty()) {
                extractedText = OcrManager.extractText(applicationContext, rawBitmaps[0])
            }

            // Пост-процесинг під DPI та колірну схему
            val processedBitmaps = rawBitmaps.map {
                DocumentPostProcessor.processBitmap(it, settings.dpi, settings.colorMode)
            }

            val parsed = DocumentParser.parse(extractedText)

            runOnUiThread {
                progressBar.visibility = View.GONE
                val defaultExt = if (settings.fileFormat == "JPEG") "jpg" else "pdf"
                if (settings.autoSave) {
                    saveProcessedFiles(processedBitmaps, parsed.buildStandardFileName(defaultExt))
                } else {
                    showConfirmationSheet(processedBitmaps, parsed)
                }
            }
        }
    }

    private fun showConfirmationSheet(bitmaps: List<Bitmap>, parsed: ParsedDocument) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_review_document, null)
        val etType = view.findViewById<EditText>(R.id.etDocType)
        val etNumber = view.findViewById<EditText>(R.id.etDocNumber)
        val etDate = view.findViewById<EditText>(R.id.etDocDate)
        val etFinalName = view.findViewById<EditText>(R.id.etFinalFileName)
        val btnViewRawOcr = view.findViewById<MaterialButton>(R.id.btnViewRawOcr)

        val ext = if (settings.fileFormat == "JPEG") "jpg" else "pdf"

        etType.setText(parsed.type)
        etNumber.setText(parsed.number)
        etDate.setText(parsed.date)

        fun updatePreview() {
            parsed.type = etType.text.toString().trim()
            parsed.number = etNumber.text.toString().trim()
            parsed.date = etDate.text.toString().trim()
            etFinalName.setText(parsed.buildStandardFileName(ext))
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
                saveProcessedFiles(bitmaps, finalFileName)
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

        val rgFormat = view.findViewById<RadioGroup>(R.id.rgFormat)
        val rgDpi = view.findViewById<RadioGroup>(R.id.rgDpi)
        val rgColor = view.findViewById<RadioGroup>(R.id.rgColor)

        tvCurrentFolder.text = settings.folderDisplayName
        switchAutoSave.isChecked = settings.autoSave

        // Формат
        if (settings.fileFormat == "JPEG") {
            view.findViewById<RadioButton>(R.id.rbFormatJpeg).isChecked = true
        } else {
            view.findViewById<RadioButton>(R.id.rbFormatPdf).isChecked = true
        }

        // DPI
        when (settings.dpi) {
            100 -> view.findViewById<RadioButton>(R.id.rbDpi100).isChecked = true
            300 -> view.findViewById<RadioButton>(R.id.rbDpi300).isChecked = true
            else -> view.findViewById<RadioButton>(R.id.rbDpi200).isChecked = true
        }

        // Колір
        when (settings.colorMode) {
            "GRAYSCALE" -> view.findViewById<RadioButton>(R.id.rbColorGray).isChecked = true
            "BW" -> view.findViewById<RadioButton>(R.id.rbColorBw).isChecked = true
            else -> view.findViewById<RadioButton>(R.id.rbColorFull).isChecked = true
        }

        btnChooseFolder.setOnClickListener { folderPickerLauncher.launch(null) }

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

                settings.fileFormat = if (rgFormat.checkedRadioButtonId == R.id.rbFormatJpeg) "JPEG" else "PDF"

                settings.dpi = when (rgDpi.checkedRadioButtonId) {
                    R.id.rbDpi100 -> 100
                    R.id.rbDpi300 -> 300
                    else -> 200
                }

                settings.colorMode = when (rgColor.checkedRadioButtonId) {
                    R.id.rbColorGray -> "GRAYSCALE"
                    R.id.rbColorBw -> "BW"
                    else -> "COLOR"
                }

                updateHeaderInfo()
            }
            .setOnDismissListener { settingsDialogView = null }
            .show()
    }

    private fun saveProcessedFiles(bitmaps: List<Bitmap>, fileName: String) {
        try {
            val customUriStr = settings.folderUri
            val isJpeg = fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true)
            val mimeType = if (isJpeg) "image/jpeg" else "application/pdf"

            if (customUriStr != null) {
                val treeUri = Uri.parse(customUriStr)
                val dir = DocumentFile.fromTreeUri(this, treeUri)
                if (dir != null && dir.canWrite()) {
                    var finalName = fileName
                    var counter = 1
                    val ext = if (isJpeg) ".jpg" else ".pdf"
                    val baseName = fileName.removeSuffix(ext)
                    while (dir.findFile(finalName) != null) {
                        finalName = "${baseName}_$counter$ext"
                        counter++
                    }

                    val createdFile = dir.createFile(mimeType, finalName)
                    if (createdFile != null) {
                        contentResolver.openOutputStream(createdFile.uri)?.use { out ->
                            if (isJpeg) {
                                DocumentPostProcessor.writeJpeg(bitmaps[0], out)
                            } else {
                                DocumentPostProcessor.writePdf(bitmaps, out)
                            }
                        }
                        vibrate()
                        Toast.makeText(this, "Збережено: $finalName", Toast.LENGTH_SHORT).show()
                        loadDocuments()
                        return
                    }
                }
            }

            // Стандартне збереження в ScanDoc
            val docsFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ScanDoc")
            if (!docsFolder.exists()) docsFolder.mkdirs()

            val ext = if (isJpeg) ".jpg" else ".pdf"
            val baseName = fileName.removeSuffix(ext)
            var destFile = File(docsFolder, fileName)
            var counter = 1
            while (destFile.exists()) {
                destFile = File(docsFolder, "${baseName}_$counter$ext")
                counter++
            }

            FileOutputStream(destFile).use { out ->
                if (isJpeg) {
                    DocumentPostProcessor.writeJpeg(bitmaps[0], out)
                } else {
                    DocumentPostProcessor.writePdf(bitmaps, out)
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
        val mimeType = if (item.name.endsWith(".jpg", true) || item.name.endsWith(".jpeg", true)) "image/jpeg" else "application/pdf"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Немає програми для відкриття цього файлу", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDocument(item: ScannedDocItem) {
        val mimeType = if (item.name.endsWith(".jpg", true) || item.name.endsWith(".jpeg", true)) "image/jpeg" else "application/pdf"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
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
