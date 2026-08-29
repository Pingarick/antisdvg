package com.antisdvg.ui.activities

import android.app.Application
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.antisdvg.R
import com.antisdvg.databinding.ActivityBarcodeScanBinding
import com.antisdvg.ui.AppViewModelFactory
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch

/**
 * Full-screen camera scanner that reads an ISBN barcode (EAN-13) off a book
 * cover, looks it up on Google Books, and returns the metadata to the caller.
 *
 * Result extras: [EXTRA_ISBN] always; [EXTRA_TITLE] / [EXTRA_AUTHOR] /
 * [EXTRA_PUBLISHER] / [EXTRA_PAGES] when the Google Books lookup succeeded.
 */
class BarcodeScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBarcodeScanBinding
    private var scanner: BarcodeScanner? = null

    private val bookEditorViewModel: BookEditorViewModel by viewModels {
        AppViewModelFactory(application as Application)
    }

    private var lastScannedIsbn: String = ""
    private var delivered = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            bindCamera()
        } else {
            Toast.makeText(this, R.string.barcode_permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBarcodeScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scanner = BarcodeScanning.getClient()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            bindCamera()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bookEditorViewModel.lookupState.collect { state ->
                    when (state) {
                        IsbnLookupState.FOUND -> {
                            val r = bookEditorViewModel.consumeLookupResult()
                            deliver(r)
                        }
                        IsbnLookupState.ERROR -> {
                            Toast.makeText(
                                this@BarcodeScanActivity, R.string.isbn_error, Toast.LENGTH_SHORT
                            ).show()
                            deliver(null)
                        }
                        IsbnLookupState.NOT_FOUND -> deliver(null)
                        else -> Unit
                    }
                }
            }
        }
    }

    /** Binds CameraX preview + ML Kit analysis to the preview view. */
    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                processBarcode(imageProxy)
            }

            provider.unbindAll()
            try {
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.barcode_scan_failed, Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs ML Kit on the current image; returns on the first decoded 13-digit ISBN. */
    private fun processBarcode(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage =
                InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner?.process(inputImage)
                ?.addOnSuccessListener { barcodes ->
                    val isbn = firstIsbn(barcodes)
                    if (isbn != null && !delivered) {
                        onIsbnScanned(isbn)
                    }
                }
                ?.addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    private fun firstIsbn(barcodes: List<Barcode>): String? =
        barcodes.firstNotNullOfOrNull { b ->
            b.rawValue?.takeIf { it.length == 13 && it.all(Char::isDigit) }
        }

    /** Looks the scanned ISBN up so the form can auto-fill. */
    private fun onIsbnScanned(isbn: String) {
        delivered = true
        lastScannedIsbn = isbn
        runOnUiThread {
            Toast.makeText(this, R.string.barcode_scanning, Toast.LENGTH_SHORT).show()
            bookEditorViewModel.lookupIsbn(isbn)
        }
    }

    /** Sends the result back to the book-editor form. */
    private fun deliver(r: IsbnLookupResult?) {
        val data = Intent()
            .putExtra(EXTRA_ISBN, lastScannedIsbn)
            .putExtra(EXTRA_TITLE, r?.title ?: "")
            .putExtra(EXTRA_AUTHOR, r?.author ?: "")
            .putExtra(EXTRA_PUBLISHER, r?.publisher ?: "")
            .putExtra(EXTRA_PAGES, r?.totalPages ?: 0)
            .putExtra(EXTRA_COVER, r?.coverUrl ?: "")
        setResult(RESULT_OK, data)
        finish()
    }

    override fun onDestroy() {
        scanner?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ISBN = "isbn"
        const val EXTRA_TITLE = "title"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_PUBLISHER = "publisher"
        const val EXTRA_PAGES = "pages"
        const val EXTRA_COVER = "cover"
    }
}
