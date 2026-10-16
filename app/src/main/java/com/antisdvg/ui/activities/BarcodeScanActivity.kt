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
import java.util.concurrent.Executors

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

    // A failed lookup (e.g. Google blocked, Open Library has no RU edition) must
    // not re-trigger on every frame: remember the ISBN and hold it in cooldown.
    private var lastFailedIsbn: String = ""
    private var retryAfter = 0L
    private val retryCooldownMs = 5000L

    /** Barcode analysis must not share the main thread with rendering: on a weak
     * phone the main-thread analyzer starves and ML Kit stops getting frames. */
    private val analysisExecutor = Executors.newSingleThreadExecutor()

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
                            // Allow retry instead of closing: reset for the next frame.
                            lastFailedIsbn = lastScannedIsbn
                            retryAfter = System.currentTimeMillis() + retryCooldownMs
                            delivered = false
                            lastScannedIsbn = ""
                            bookEditorViewModel.resetLookup()
                        }
                        IsbnLookupState.NOT_FOUND -> {
                            Toast.makeText(
                                this@BarcodeScanActivity, R.string.isbn_not_found, Toast.LENGTH_SHORT
                            ).show()
                            lastFailedIsbn = lastScannedIsbn
                            retryAfter = System.currentTimeMillis() + retryCooldownMs
                            delivered = false
                            lastScannedIsbn = ""
                            bookEditorViewModel.resetLookup()
                        }
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
            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
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
                        return@addOnSuccessListener
                    }
                    // RU covers often carry a Yandex Books QR sticker instead of a
                    // scannable ISBN story: same prefill path via the share-link parser.
                    val shareUrl = firstYandexShare(barcodes)
                    if (shareUrl != null && !delivered) {
                        onYandexScanned(shareUrl)
                    }
                }
                ?.addOnFailureListener { e ->
                    // Previously swallowed silently — a broken recognizer looked like
                    // "nothing scans". At least log every recognizer failure.
                    android.util.Log.w("BarcodeScan", "ML Kit failed: ${e.javaClass.name}: ${e.message}")
                }
                ?.addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    private fun firstIsbn(barcodes: List<Barcode>): String? =
        barcodes.firstNotNullOfOrNull { b ->
            val raw = b.rawValue?.trim().orEmpty()
            if (raw.isEmpty()) return@firstNotNullOfOrNull null
            // Barcode may contain hyphens/spaces or ISBN-10; normalize to ISBN-13.
            // Also tolerate Yandex share URLs in QR codes — skip them here.
            if (raw.startsWith("http", ignoreCase = true)) return@firstNotNullOfOrNull null
            val normalized = com.antisdvg.util.IsbnUtils.normalize(raw)
            if (normalized.isNotEmpty()) {
                normalized
            } else if (raw.length == 13 && raw.all(Char::isDigit)) {
                // Lenient fallback: accept any 13-digit EAN even with a bad checksum —
                // the lookup rejects garbage later, but the scan must not hang.
                raw
            } else null
        }

    /** Looks the scanned ISBN up so the form can auto-fill. */
    private fun onIsbnScanned(isbn: String) {
        if (isbn == lastFailedIsbn && System.currentTimeMillis() < retryAfter) {
            // Same barcode that just failed — wait it out instead of looping toasts.
            return
        }
        delivered = true
        lastScannedIsbn = isbn
        runOnUiThread {
            Toast.makeText(this, R.string.barcode_scanning, Toast.LENGTH_SHORT).show()
            bookEditorViewModel.lookupIsbn(isbn)
        }
    }

    /** Picks a Yandex Books share URL out of scanned QR codes, if any. */
    private fun firstYandexShare(barcodes: List<Barcode>): String? =
        barcodes.firstNotNullOfOrNull { b ->
            val raw = b.rawValue?.trim().orEmpty()
            if (!raw.startsWith("http", ignoreCase = true)) return@firstNotNullOfOrNull null
            val isYandex = raw.contains("yandex", ignoreCase = true) ||
                raw.contains("ycl.by", ignoreCase = true)
            if (isYandex) "https://" + raw.removePrefix("https://").removePrefix("http://") else null
        }

    /** Runs the Yandex share-link lookup for a QR sticker on the cover. */
    private fun onYandexScanned(url: String) {
        if (url == lastFailedIsbn && System.currentTimeMillis() < retryAfter) {
            return
        }
        delivered = true
        lastScannedIsbn = ""
        runOnUiThread {
            Toast.makeText(this, R.string.barcode_scanning, Toast.LENGTH_SHORT).show()
            bookEditorViewModel.lookupYandex(url)
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
        analysisExecutor.shutdown()
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
