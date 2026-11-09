package com.monu.mydocs

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.ScaleGestureDetector
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import androidx.activity.result.IntentSenderRequest
import java.util.concurrent.Executors

class DocumentScannerActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var capture: ImageCapture
    private lateinit var warningView: TextView
    private var stabilizationLabel = "Stabilization: standard"
    private val executor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var captureInProgress = false
    private val pages = arrayListOf<Uri>()
    private val folder by lazy { File(filesDir, "MyDocs/.scan_${System.currentTimeMillis()}").apply { mkdirs() } }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) bindCamera() else finish() }

    private val smartScan = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val images = scan?.pages.orEmpty()
            images.forEachIndexed { index, page ->
                runCatching {
                    val target = File(folder, "smart_${System.currentTimeMillis()}_${index + 1}.jpg")
                    contentResolver.openInputStream(page.imageUri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (target.length() > 0L) pages.add(FileProvider.getUriForFile(this, "$"+"packageName.provider", target))
                }
            }
            countView?.text = "${pages.size} pages"
            updateCaptureThumbnails()
            if (images.isNotEmpty()) warningView.text = "Smart scan pages added"
        }
    }

    private fun launchSmartScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender -> smartScan.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { error ->
                Toast.makeText(this, "Smart scanner unavailable: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }
    private val gallery = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { items ->
        items.forEach { uri -> runCatching {
            val file = File(folder, "import_${System.nanoTime()}.jpg")
            contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use(input::copyTo) }
            if (file.length() > 0) pages.add(FileProvider.getUriForFile(this, "$packageName.provider", file))
        } }
        countView?.text = "${pages.size} pages"
        updateCaptureThumbnails()
    }
    private var countView: TextView? = null
    private var updateCaptureThumbnails: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false

        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            clipToPadding = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        fun actionButton(label: String, round: Boolean = true, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = if (round) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                setColor(0xFF202629.toInt())
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener { action() }
        }
        fun captureButton(action: () -> Unit): TextView = TextView(this).apply {
            text = " "
            textSize = 43f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF00A99D.toInt())
                setStroke(dp(5), 0xFFE8FFFC.toInt())
            }
            setOnClickListener { action() }
        }

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF080A0A.toInt())
        }
        countView = TextView(this).apply {
            text = "0 pages"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        top.addView(actionButton("×") { finish() }, LinearLayout.LayoutParams(dp(42), dp(48)))
        top.addView(countView, LinearLayout.LayoutParams(0, -1, 1f))
        top.addView(TextView(this).apply {
            text = "Smart"; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(0xFF00D6C8.toInt()); setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener { launchSmartScan() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(actionButton(if (flashMode == ImageCapture.FLASH_MODE_OFF) "ϟ" else "⚡") {
            flashMode = (flashMode + 1) % 3
            if (::capture.isInitialized) capture.flashMode = flashMode
            Toast.makeText(this, listOf("Flash Off", "Flash On", "Flash Auto")[flashMode], Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val topDone = TextView(this).apply {
            text = "Done"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF00D6C8.toInt())
            setPadding(dp(10), 0, dp(4), 0)
            setOnClickListener { finishScan() }
        }
        top.addView(topDone, LinearLayout.LayoutParams(dp(58), dp(48)))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(64)))
        val previewFrame = FrameLayout(this)
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        warningView = TextView(this).apply {
            text = "Camera ready"
            setTextColor(0xFFB9E8DF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setBackgroundColor(0xFF101719.toInt())
        }
        // Compact scan modes, inspired by the supplied reference video.
        var scanMode = "Batch"
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(5))
            setBackgroundColor(0xFF101719.toInt())
        }
        val modeButtons = linkedMapOf<String, TextView>()
        fun updateModeButtons() {
            modeButtons.forEach { (name, view) ->
                val selected = name == scanMode
                view.setTextColor(if (selected) 0xFF00D6C8.toInt() else 0xFFB8C4C4.toInt())
                view.background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (selected) 0xFF183A38.toInt() else Color.TRANSPARENT)
                }
            }
        }
        listOf("Single", "Batch", "ID Card", "Passport").forEach { name ->
            val item = TextView(this).apply {
                text = name
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setOnClickListener {
                    scanMode = name
                    updateModeButtons()
                    warningView.text = when (name) {
                        "Single" -> "Single page scan"
                        "Batch" -> "Batch scan: capture multiple pages"
                        "ID Card" -> "ID Card mode: capture card"
                        else -> "Passport mode: capture passport pages"
                    }
                }
            }
            modeButtons[name] = item
            modeRow.addView(item, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        updateModeButtons()
        warningView.visibility = View.GONE

        val thumbnailStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        val thumbnailScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(thumbnailStrip)
        }
        updateCaptureThumbnails = {
            thumbnailStrip.removeAllViews()
            pages.forEachIndexed { index, uri ->
                val tile = FrameLayout(this).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(9).toFloat()
                        setColor(0xFF203735.toInt())
                        setStroke(dp(2), if (index == pages.lastIndex) 0xFF00D6C8.toInt() else 0xFF40504C.toInt())
                    }
                    clipToOutline = true
                    isClickable = true
                    isFocusable = true
                }
                val thumb = ImageView(this).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    options.inSampleSize = 4
                    options.inJustDecodeBounds = false
                    val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    setImageBitmap(bitmap)
                    contentDescription = "Preview page ${index + 1}"
                }
                tile.addView(thumb, FrameLayout.LayoutParams(-1, -1))
                val number = TextView(this).apply {
                    text = "${index + 1}"
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(0xCC101719.toInt())
                    }
                }
                tile.addView(number, FrameLayout.LayoutParams(dp(24), dp(22), Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, dp(3), dp(3))
                })
                tile.setOnClickListener {
                    val previewBitmap = runCatching {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    }.getOrNull()
                    if (previewBitmap != null) {
                        val previewImage = ImageView(this).apply {
                            setImageBitmap(previewBitmap)
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setBackgroundColor(Color.BLACK)
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Page ${index + 1} of ${pages.size}")
                            .setView(previewImage)
                            .setPositiveButton("Close", null)
                            .setOnDismissListener { if (!previewBitmap.isRecycled) previewBitmap.recycle() }
                            .show()
                    }
                }
                thumbnailStrip.addView(tile, LinearLayout.LayoutParams(dp(66), dp(78)).apply { marginEnd = dp(7) })
            }
            thumbnailScroll.post { thumbnailScroll.fullScroll(android.view.View.FOCUS_RIGHT) }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF080A0A.toInt())
            setPadding(0, dp(4), 0, dp(6))
        }
        bottom.addView(modeRow, LinearLayout.LayoutParams(-1, dp(48)))
        bottom.addView(thumbnailScroll, LinearLayout.LayoutParams(-1, dp(44)))
        val controls = FrameLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), dp(8))
            setBackgroundColor(0xFF080A0A.toInt())
        }
        controls.addView(actionButton("▣", false) { gallery.launch("image/*") },
            FrameLayout.LayoutParams(dp(54), dp(54), Gravity.START or Gravity.CENTER_VERTICAL))
        controls.addView(captureButton { takePhoto() },
            FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
        controls.addView(actionButton("⟳") {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END or Gravity.CENTER_VERTICAL))
        bottom.addView(controls, LinearLayout.LayoutParams(-1, dp(94)))
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(186)))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        val zoom = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                camera?.let { c -> val z = c.cameraInfo.zoomState.value; c.cameraControl.setZoomRatio(((z?.zoomRatio ?: 1f) * d.scaleFactor).coerceIn(z?.minZoomRatio ?: 1f, z?.maxZoomRatio ?: 1f)) }
                return true
            }
        })
        preview.setOnTouchListener { _, e ->
            zoom.onTouchEvent(e)
            if (e.action == android.view.MotionEvent.ACTION_UP) {
                val p = preview.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(p).build())
            }
            true
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ runCatching {
            val provider = future.get()
            provider.unbindAll()
            val selector = CameraSelector.Builder().requireLensFacing(lens).build()
            // Document scans are saved at a practical resolution instead of the camera's
            // maximum sensor resolution. This keeps each JPEG manageable while retaining
            // enough detail for readable PDFs and OCR.
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(1600, 1200),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                ).build()
            val cameraInfo = provider.availableCameraInfos.firstOrNull { info ->
                Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ==
                    (if (lens == CameraSelector.LENS_FACING_BACK) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT)
            }
            val targetRotation = preview.display?.rotation ?: Surface.ROTATION_0
            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(85)
                .setTargetRotation(targetRotation)
                .setResolutionSelector(resolutionSelector)
                .setFlashMode(flashMode)
            if (cameraInfo != null) {
                val camera2Info = Camera2CameraInfo.from(cameraInfo)
                val oisModes = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                if (oisModes?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                    Camera2Interop.Extender(captureBuilder).setCaptureRequestOption(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                    stabilizationLabel = "OIS stabilization enabled"
                } else stabilizationLabel = "Hardware OIS unavailable"
            }
            capture = captureBuilder.build()
            warningView.text = stabilizationLabel
            val p = Preview.Builder().setTargetRotation(targetRotation).build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            camera = provider.bindToLifecycle(this, selector, p, capture)
        }.onFailure { Toast.makeText(this, "Camera error: ${it.localizedMessage}", Toast.LENGTH_LONG).show() } }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        if (!::capture.isInitialized || captureInProgress) return
        captureInProgress = true
        warningView.text = "Capturing page..."
        val file = File(folder, "page_${pages.size + 1}_${System.currentTimeMillis()}.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                runOnUiThread {
                    captureInProgress = false
                    pages.add(FileProvider.getUriForFile(this@DocumentScannerActivity, "$packageName.provider", file))
                    countView?.text = "${pages.size} pages"
                    updateCaptureThumbnails()
                }
            }
            override fun onError(error: ImageCaptureException) {
                runOnUiThread {
                    captureInProgress = false
                    warningView.text = "Capture failed. Please try again."
                    Toast.makeText(this@DocumentScannerActivity, error.message ?: "Capture failed", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun finishScan() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "Capture ya gallery se pages add karein", Toast.LENGTH_SHORT).show()
            return
        }
        setResult(Activity.RESULT_OK, Intent().putStringArrayListExtra("page_uris", ArrayList(pages.map(Uri::toString))))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        camera = null
        executor.shutdown()
    }
}
