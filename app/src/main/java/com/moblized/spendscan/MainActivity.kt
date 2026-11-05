package com.moblized.spendscan

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.moblized.spendscan.ui.AppModel
import com.moblized.spendscan.ui.AppRoot
import java.io.File

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels {
        viewModelFactory { initializer { AppModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as App) } }
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            model.onPages(scan?.pages?.map { it.imageUri }.orEmpty())
        }
    }

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.onPages(listOf(uri))
    }

    /** Where the camera app saves the pump photo. Kept across the phone closing this app to free memory while the camera is open. */
    private var pumpPhoto: Uri? = null

    private val pumpLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val photo = pumpPhoto
        if (taken && photo != null) model.onPumpPhoto(photo)
        pumpPhoto = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PUMP_PHOTO, pumpPhoto?.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        pumpPhoto = savedInstanceState?.getString(KEY_PUMP_PHOTO)?.let(Uri::parse)
        // The counter is dark in both themes, so the status bar icons stay light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            AppRoot(
                model = model,
                onScan = ::startScan,
                onPickPhoto = ::pickPhoto,
                onPumpPhoto = ::takePumpPhoto,
                onDarkChange = { dark -> IconSwitcher.apply(applicationContext, dark) },
                onExit = ::finish,
            )
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_SCAN -> startScan()
            Intent.ACTION_SEND -> sharedImage(intent)?.let { model.onPages(listOf(it)) }
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedImage(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun startScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(5)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { sender -> scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { model.scannerUnavailable.value = true }
    }

    /** Uses the phone's own camera app, so this app doesn't need permission to use the camera. */
    private fun takePumpPhoto() {
        val file = File(File(cacheDir, "pump").apply { mkdirs() }, "pump-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        pumpPhoto = uri
        try {
            pumpLauncher.launch(uri)
        } catch (_: ActivityNotFoundException) {
            pumpPhoto = null
            model.message.value = "This phone has no camera app to take the photo with."
        }
    }

    private fun pickPhoto() {
        pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private companion object {
        const val ACTION_SCAN = "com.moblized.spendscan.SCAN"
        const val KEY_PUMP_PHOTO = "pumpPhoto"
    }
}
