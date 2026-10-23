package com.fixerfixy.docscanner

import com.fixerfixy.docscanner.R

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val gmsResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            gmsResult?.let { handleScanResult(it) }
        } else {
            tvStatus.text = "Сканування скасовано"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        val btnScan = findViewById<MaterialButton>(R.id.btnScan)

        btnScan.setOnClickListener {
            startDocumentScanner()
        }
    }

    private fun startDocumentScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(15)
            .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF)
            .setScannerMode(SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender: IntentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                tvStatus.text = "Помилка запуску сканера: ${e.localizedMessage}"
            }
    }

    private fun handleScanResult(result: GmsDocumentScanningResult) {
        tvStatus.text = "Аналіз тексту документа..."
        val pages = result.pages
        val pdfUri = result.pdf?.uri

        if (pages.isNullOrEmpty() || pdfUri == null) {
            tvStatus.text = "Помилка: не отримано скан"
            return
        }

        val firstPageUri = pages[0].imageUri
        val image = InputImage.fromFilePath(this, firstPageUri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val generatedName = DocumentParser.generateFileName(visionText.text)
                savePdfToScanDoc(pdfUri, generatedName)
            }
            .addOnFailureListener {
                val fallbackName = "Документ_${System.currentTimeMillis()}.pdf"
                savePdfToScanDoc(pdfUri, fallbackName)
            }
    }

    private fun savePdfToScanDoc(sourceUri: Uri, fileName: String) {
        try {
            val docsFolder = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "ScanDoc"
            )
            if (!docsFolder.exists()) {
                docsFolder.mkdirs()
            }

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

            triggerVibration()
            val msg = "Збережено: ${destFile.name}"
            tvStatus.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            tvStatus.text = "Помилка збереження: ${e.localizedMessage}"
        }
    }

    private fun triggerVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(300)
        }
    }
}
