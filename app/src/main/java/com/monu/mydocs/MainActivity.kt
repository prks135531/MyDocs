package com.monu.mydocs

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.documentscanner.*
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.max
import android.graphics.PointF

class MainActivity : ComponentActivity() {
    private lateinit var list: LinearLayout
    private lateinit var count: TextView
    private lateinit var folderPathLabel: TextView
    private lateinit var searchInput: EditText
    private lateinit var folderStrip: LinearLayout
    private var gridView = false
    private var favoritesMode = false
    private val docsDir by lazy { File(filesDir, "MyDocs").apply { mkdirs() } }
    private var currentFolder: File? = null
    private val activeDir: File get() = (currentFolder ?: docsDir).also { it.mkdirs() }
    private val scanner by lazy {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(50)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF, GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
                .build()
        )
    }
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(data)
            val pageUris = scan?.pages?.map { it.imageUri }.orEmpty()
            if (pageUris.isNotEmpty()) {
                showScanReview(pageUris)
            } else {
                scan?.pdf?.let { pdf ->
                    val name = "MyDocs_${System.currentTimeMillis()}.pdf"
                    contentResolver.openInputStream(pdf.uri)?.use { input -> File(activeDir, name).outputStream().use { output -> input.copyTo(output) } }
                    Toast.makeText(this, "PDF saved in MyDocs", Toast.LENGTH_SHORT).show()
                    refreshList()
                }
            }
        }
    }
    private val cameraScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pages = result.data?.getStringArrayListExtra("page_uris").orEmpty().map { Uri.parse(it) }
            if (pages.isNotEmpty()) showScanReview(pages)
        }
    }
    private var pendingRetake: ((Uri) -> Unit)? = null
    private val retakeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val replacement = result.data?.getStringArrayListExtra("page_uris")?.firstOrNull()?.let(Uri::parse)
            if (replacement != null) pendingRetake?.invoke(replacement)
        }
        pendingRetake = null
    }
    private val mergeLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) mergePdfs(uris)
    }
    private val splitLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { splitPdf(it) }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val name = queryName(uri) ?: "Imported_${System.currentTimeMillis()}.pdf"
            val safe = name.replace(Regex("""[\\/:*?"<>|\\p{Cc}]"""), "_").trim().let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
            val target = File(activeDir, uniqueName(safe))
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            refreshList()
            Toast.makeText(this, "Imported successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { toast("Import failed: ${e.localizedMessage}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 24, 23)
        window.navigationBarColor = 0xFF101418.toInt()
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        val prefs = getSharedPreferences("mydocs_ui", MODE_PRIVATE)
        gridView = prefs.getBoolean("grid_view", false)
        val savedPath = prefs.getString("current_folder", null)
        if (!savedPath.isNullOrBlank()) {
            val restored = File(docsDir, savedPath)
            if (restored.isDirectory && restored.canonicalPath.startsWith(docsDir.canonicalPath + File.separator)) currentFolder = restored
        }
        buildUi()
        refreshList()
    }

    private fun buildUi() {
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFF101418.toInt()) }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { clipToPadding = false; setPadding(0, 0, 0, dp(96)) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(8)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.mydocs_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "MyDocs logo"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(12) })
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply { text = "MyDocs"; textSize = 25f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        brand.addView(TextView(this).apply { text = "Your documents, organized"; textSize = 13f; setTextColor(0xFF9BAAA7.toInt()) })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply { text = "♙"; textSize = 25f; setTextColor(0xFF55D6C2.toInt()); setPadding(dp(10), 0, dp(4), 0) })
        content.addView(header)
        searchInput = EditText(this).apply {
            hint = "⌕  Search documents..."; textSize = 16f; setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(0xFF9BAAA7.toInt()); setPadding(dp(16), 0, dp(16), 0)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF20292D.toInt()); cornerRadius = dp(22).toFloat() }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshList() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        content.addView(searchInput, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(22); bottomMargin = dp(16) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionCard("▤", "Scan Document", "Camera scanner", 0xFF173F3A.toInt(), 0xFF61DCC6.toInt()) { startScan() }, LinearLayout.LayoutParams(0, dp(142), 1f).apply { marginEnd = dp(7) })
        actions.addView(actionCard("⇧", "Import PDF", "From your phone", 0xFF26364E.toInt(), 0xFF91B9FF.toInt()) { importLauncher.launch(arrayOf("application/pdf")) }, LinearLayout.LayoutParams(0, dp(142), 1f).apply { marginStart = dp(7) })
        content.addView(actions)
        val pdfTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF20292D.toInt()); cornerRadius = dp(14).toFloat() }
        }
        pdfTools.addView(TextView(this).apply {
            text = "PDF Tools"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        pdfTools.addView(actionButton("Merge PDFs") { mergeLauncher.launch(arrayOf("application/pdf")) })
        pdfTools.addView(actionButton("Split PDF") { splitLauncher.launch(arrayOf("application/pdf")) }.apply { setPadding(dp(8), 0, dp(8), 0) })
        content.addView(pdfTools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val folderHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        folderHeader.addView(TextView(this).apply { text = "My Folders"; textSize = 19f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        folderHeader.addView(TextView(this).apply { text = "+ Folder"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF087F78.toInt()); cornerRadius = dp(12).toFloat() }; setOnClickListener { createFolder() } })
        folderHeader.addView(TextView(this).apply { text = "View all  ›"; textSize = 14f; setTextColor(0xFF55D6C2.toInt()); setPadding(dp(12), dp(8), 0, dp(8)); setOnClickListener { showFolderPicker() } })
        content.addView(folderHeader, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(12) })
        folderPathLabel = TextView(this).apply { text = "All Documents"; textSize = 12f; setTextColor(0xFF9BAAA7.toInt()); setOnClickListener { showFolderPicker() } }
        content.addView(folderPathLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        folderStrip = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(folderStrip)
        val docsHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        count = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END }
        docsHeader.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
        docsHeader.addView(TextView(this).apply { text = "☷"; textSize = 25f; setTextColor(0xFF61DCC6.toInt()); setPadding(dp(8), dp(4), dp(8), dp(4)); setOnClickListener { gridView = false; refreshList() } })
        docsHeader.addView(TextView(this).apply { text = "▦"; textSize = 23f; setTextColor(0xFF61DCC6.toInt()); setPadding(dp(8), dp(4), 0, dp(4)); setOnClickListener { gridView = true; refreshList() } })
        content.addView(docsHeader, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(12) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF151D20.toInt()); cornerRadius = dp(20).toFloat() }
        }
        bottom.addView(navItem("⌂", "Home", true) { favoritesMode = false; refreshList(); scroll.smoothScrollTo(0, 0) }, LinearLayout.LayoutParams(0, dp(66), 1f))
        bottom.addView(navItem("▱", "Files", false) { showFolderPicker() }, LinearLayout.LayoutParams(0, dp(66), 1f))
        bottom.addView(navItem("☆", "Favorites", false) { favoritesMode = true; if (::searchInput.isInitialized) searchInput.setText(""); refreshList() }, LinearLayout.LayoutParams(0, dp(66), 1f))
        bottom.addView(navItem("⚙", "Settings", false) { toast("Settings feature coming soon") }, LinearLayout.LayoutParams(0, dp(66), 1f))
        page.addView(bottom, LinearLayout.LayoutParams(-1, dp(82)).apply { leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8) })
        frame.addView(page)
        val fab = TextView(this).apply {
            text = "＋"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); elevation = dp(9).toFloat()
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF087F78.toInt()); cornerRadius = dp(30).toFloat() }
            setOnClickListener { startScan() }
        }
        frame.addView(fab, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(24); bottomMargin = dp(104) })
        setContentView(frame)
    }

    private fun actionCard(icon: String, title: String, subtitle: String, bg: Int, accent: Int, click: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(bg); cornerRadius = dp(22).toFloat() }
            setOnClickListener { click() }
            addView(TextView(this@MainActivity).apply { text = icon; textSize = 27f; setTextColor(accent) })
            addView(TextView(this@MainActivity).apply { text = title; textSize = 17f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(5)) })
            addView(TextView(this@MainActivity).apply { text = subtitle; textSize = 13f; setTextColor(0xFFB4C4C5.toInt()) })
        }
    }

    private fun navItem(icon: String, label: String, selected: Boolean, click: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setOnClickListener { click() }
            addView(TextView(this@MainActivity).apply { text = icon; textSize = 25f; gravity = Gravity.CENTER; setTextColor(if (selected) 0xFF61DCC6.toInt() else 0xFF9BAAA7.toInt()) })
            addView(TextView(this@MainActivity).apply { text = label; textSize = 12f; setTextColor(if (selected) 0xFF61DCC6.toInt() else 0xFF9BAAA7.toInt()) })
        }
    }
    private fun actionButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE); setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF087F78.toInt())); setOnClickListener { click() }
    }

    private fun startScan() {
        cameraScanLauncher.launch(Intent(this, DocumentScannerActivity::class.java))
    }

    private fun launchScanner() {
        scanner.getStartScanIntent(this).addOnSuccessListener { sender ->
            scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }.addOnFailureListener { toast("Scanner unavailable. Check Google Play services.") }
    }


    private fun decodeBitmapWithExif(uri: Uri, maxDimension: Int = 3000): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension * 2 ||
            bounds.outHeight / sampleSize > maxDimension * 2) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        return try {
            val orientation = contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
            val matrix = android.graphics.Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postScale(-1f, 1f)
                    matrix.postRotate(90f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postScale(-1f, 1f)
                    matrix.postRotate(270f)
                }
            }
            if (matrix.isIdentity) bitmap else Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            ).also { if (it !== bitmap) bitmap.recycle() }
        } catch (_: Exception) {
            bitmap
        }
    }

    private val filterNames = arrayOf("Original (no filter)", "Black & White", "Grayscale", "Color Enhance")

    private fun showScanReview(pageUris: List<Uri>) {
        val pages = pageUris.toMutableList()
        val filters = mutableListOf<Int>().apply { repeat(pages.size) { add(0) } }
        val rotations = mutableListOf<Int>().apply { repeat(pages.size) { add(0) } }
        var pageIndex = 0
        val selectedPages = linkedSetOf<Int>()
        data class PageSnapshot(val uris: List<Uri>, val filters: List<Int>, val rotations: List<Int>, val index: Int)
        val undoStack = mutableListOf<PageSnapshot>()
        var multiSelectMode = false
        val thumbnailStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun savePageUndo() {
            undoStack.add(PageSnapshot(pages.toList(), filters.toList(), rotations.toList(), pageIndex))
            if (undoStack.size > 20) undoStack.removeAt(0)
        }
        var brightness = 0
        var contrast = 100
        var sharpness = 0
        var enhancementMode = 0
        val enhancement = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("None", "Auto Enhance", "Text Clarity", "Vivid Color"))
        }
        fun enhanceImage(input: Bitmap, mode: Int): Bitmap {
            if (mode == 0) return input
            val contrastBoost = when (mode) { 1 -> 1.12f; 2 -> 1.32f; else -> 1.16f }
            val saturation = when (mode) { 1 -> 1.10f; 2 -> 0f; else -> 1.28f }
            val offset = 127f * (1f - contrastBoost)
            val matrix = ColorMatrix(floatArrayOf(
                contrastBoost,0f,0f,0f,offset,
                0f,contrastBoost,0f,0f,offset,
                0f,0f,contrastBoost,0f,offset,
                0f,0f,0f,1f,0f
            ))
            matrix.postConcat(ColorMatrix().apply { setSaturation(saturation) })
            val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
            Canvas(output).drawBitmap(input, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            })
            return output
        }
        var originalBitmap: Bitmap? = null
        var displayBitmap: Bitmap? = null
        val image = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE) }
        val label = TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 14f }
        val selectionLabel = TextView(this).apply { setTextColor(0xFF00D6C8.toInt()); gravity = Gravity.CENTER; textSize = 12f }
        val filter = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, filterNames) }
        val applyAll = CheckBox(this).apply { text = "Apply filter to all pages"; setTextColor(Color.WHITE) }
        val brightnessBar = SeekBar(this).apply { max = 100; progress = 50 }
        val contrastBar = SeekBar(this).apply { max = 200; progress = 100 }
        val sharpnessBar = SeekBar(this).apply { max = 100; progress = 0 }
        val paperSize = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Original size", "A4")) }
        val pageOrientation = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Auto orientation", "Portrait", "Landscape")) }
        val exportQuality = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("High quality", "Balanced", "Smaller file")) }
        val pageMargins = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("No margins", "Small margins", "Large margins")) }
        val pdfName = EditText(this).apply { setText("MyDocs_${System.currentTimeMillis()}"); hint = "PDF file name"; setTextColor(Color.WHITE); setHintTextColor(0xFF9BAAA7.toInt()) }
        var refreshThumbnails: () -> Unit = {}
        fun render() {
            displayBitmap?.recycle()
            displayBitmap = null
            originalBitmap?.recycle()
            originalBitmap = decodeBitmapWithExif(pages[pageIndex])
            label.text = "Page ${pageIndex + 1} of ${pages.size}"
            selectionLabel.text = if (multiSelectMode) "${selectedPages.size} selected" else "Long-press a thumbnail to select multiple"
            filter.setSelection(filters[pageIndex], false)
            val src = originalBitmap ?: return
            var bitmap = applyDocumentFilter(src, filters[pageIndex])
            if (rotations[pageIndex] % 360 != 0) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotations[pageIndex].toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (bitmap !== src) bitmap.recycle()
                bitmap = rotated
            }
            val adjusted = if (brightness != 0 || contrast != 100) {
                val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val scale = contrast / 100f
                val offset = brightness * 2.55f + 127f * (1f - scale)
                val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    scale,0f,0f,0f,offset, 0f,scale,0f,0f,offset, 0f,0f,scale,0f,offset, 0f,0f,0f,1f,0f
                ))) }
                Canvas(out).drawBitmap(bitmap,0f,0f,p)
                out
            } else bitmap
            val sharpened = if (sharpness > 0) applySharpness(adjusted, sharpness) else adjusted
            if (sharpness > 0 && adjusted !== bitmap) adjusted.recycle()
            if (sharpness > 0 && bitmap !== src) bitmap.recycle()
            val enhanced = enhanceImage(sharpened, enhancementMode)
            if (enhanced !== sharpened) sharpened.recycle()
            displayBitmap = enhanced
            image.setImageBitmap(displayBitmap)
            refreshThumbnails()
        }
        enhancement.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (enhancementMode != position) {
                    enhancementMode = position
                    if (pages.isNotEmpty()) render()
                }
            }
        }
        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (pages.isNotEmpty()) {
                    filters[pageIndex] = position
                    if (applyAll.isChecked) filters.indices.forEach { filters[it] = position }
                    render()
                }
            }
        }
        brightnessBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { brightness = p - 50; if (user && pages.isNotEmpty()) render() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sharpnessBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { sharpness = p; if (user && pages.isNotEmpty()) render() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        contrastBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { contrast = p; if (user && pages.isNotEmpty()) render() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { action() } }
        refreshThumbnails = {
            thumbnailStrip.removeAllViews()
            pages.forEachIndexed { index, uri ->
                val thumb = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(66), dp(82)).apply { marginEnd = dp(8) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    setBackgroundColor(if (index == pageIndex) 0xFF00A99D.toInt() else 0xFF34413E.toInt())
                    contentDescription = "Page ${index + 1}"
                    val bmp = decodeBitmapWithExif(uri, 180)
                    if (bmp != null) setImageBitmap(bmp)
                    alpha = if (multiSelectMode && index in selectedPages) 0.65f else 1f
                    setOnClickListener {
                        if (multiSelectMode) {
                            if (!selectedPages.add(index)) selectedPages.remove(index)
                            selectionLabel.text = "${selectedPages.size} selected"
                            refreshThumbnails()
                        } else { pageIndex = index; render() }
                    }
                    setOnLongClickListener {
                        multiSelectMode = true
                        if (!selectedPages.add(index)) selectedPages.remove(index)
                        selectionLabel.text = "${selectedPages.size} selected"
                        refreshThumbnails()
                        true
                    }
                }
                thumbnailStrip.addView(thumb)
            }
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)); setBackgroundColor(0xFF101614.toInt()) }
        controls.addView(label)
        controls.addView(image, LinearLayout.LayoutParams(-1, dp(260)))
        controls.addView(TextView(this).apply { text="Filter"; setTextColor(Color.WHITE) })
        controls.addView(filter)
        controls.addView(applyAll)
        val batchFilterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        batchFilterRow.addView(button("Apply to selected") {
            if (selectedPages.isEmpty()) {
                toast("Select pages first using Multi-select")
            } else {
                savePageUndo()
                selectedPages.forEach { index -> filters[index] = filter.selectedItemPosition }
                selectedPages.clear()
                multiSelectMode = false
                render()
                toast("Filter applied to selected pages")
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        batchFilterRow.addView(button("Apply to all") {
            savePageUndo()
            filters.indices.forEach { filters[it] = filter.selectedItemPosition }
            selectedPages.clear()
            multiSelectMode = false
            applyAll.isChecked = false
            render()
            toast("Filter applied to all pages")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(batchFilterRow)
        controls.addView(TextView(this).apply { text="Brightness"; setTextColor(Color.WHITE) })
        controls.addView(brightnessBar)
        controls.addView(TextView(this).apply { text="Contrast"; setTextColor(Color.WHITE) })
        controls.addView(contrastBar)
        controls.addView(TextView(this).apply { text="Sharpness"; setTextColor(Color.WHITE) })
        controls.addView(sharpnessBar)
        controls.addView(TextView(this).apply { text="Image enhancement"; setTextColor(Color.WHITE) })
        controls.addView(enhancement)
        controls.addView(TextView(this).apply { text="PDF page size"; setTextColor(Color.WHITE) })
        controls.addView(paperSize)
        controls.addView(TextView(this).apply { text="Page orientation"; setTextColor(Color.WHITE) })
        controls.addView(pageOrientation)
        controls.addView(TextView(this).apply { text="Export quality"; setTextColor(Color.WHITE) })
        controls.addView(exportQuality)
        controls.addView(TextView(this).apply { text="Page margins"; setTextColor(Color.WHITE) })
        controls.addView(pageMargins)
        controls.addView(TextView(this).apply { text="PDF file name"; setTextColor(Color.WHITE) })
        controls.addView(pdfName)
        controls.addView(selectionLabel)
        val thumbScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(thumbnailStrip) }
        controls.addView(thumbScroll, LinearLayout.LayoutParams(-1, dp(92)))
        val navRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        navRow.addView(button("‹ Prev") { if (pageIndex > 0) { pageIndex--; render() } })
        navRow.addView(button("Next ›") { if (pageIndex < pages.lastIndex) { pageIndex++; render() } })
        navRow.addView(button("Grid") {
            val grid = android.widget.GridLayout(this@MainActivity).apply { columnCount = 3; setPadding(dp(8), dp(8), dp(8), dp(8)) }
            var gridDialog: AlertDialog? = null
            pages.forEachIndexed { index, uri ->
                val cell = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
                val preview = ImageView(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(82), dp(100)); scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(decodeBitmapWithExif(uri, 180)) }
                cell.addView(preview)
                cell.addView(TextView(this@MainActivity).apply { text = "Page ${index + 1}"; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
                cell.setOnClickListener { pageIndex = index; gridDialog?.dismiss(); render() }
                grid.addView(cell)
            }
            val gridScroll = ScrollView(this@MainActivity).apply { addView(grid) }
            gridDialog = AlertDialog.Builder(this@MainActivity).setTitle("All Pages").setView(gridScroll).setNegativeButton("Close", null).create()
            gridDialog?.show()
        })
        controls.addView(navRow)
        val editRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        editRow.addView(button("Rotate ↻") { savePageUndo(); rotations[pageIndex] = (rotations[pageIndex] + 90) % 360; render() })
        editRow.addView(button("Crop") { showPerspectiveCrop(pages[pageIndex]) { cropped -> savePageUndo(); pages[pageIndex] = cropped; render() } })
        editRow.addView(button("Retake") {
            val target = if (selectedPages.isNotEmpty()) selectedPages.minOrNull() ?: pageIndex else pageIndex
            pendingRetake = { replacement -> savePageUndo(); pages[target] = replacement; selectedPages.clear(); multiSelectMode = false; pageIndex = target; render() }
            retakeLauncher.launch(Intent(this, DocumentScannerActivity::class.java))
        })
        controls.addView(editRow)
        val manageRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        manageRow.addView(button("Duplicate") { savePageUndo(); pages.add(pageIndex + 1, pages[pageIndex]); filters.add(pageIndex + 1, filters[pageIndex]); rotations.add(pageIndex + 1, rotations[pageIndex]); pageIndex++; render() })
        manageRow.addView(button("Undo") {
            val previous = undoStack.removeLastOrNull()
            if (previous != null) { pages.clear(); pages.addAll(previous.uris); filters.clear(); filters.addAll(previous.filters); rotations.clear(); rotations.addAll(previous.rotations); pageIndex = previous.index.coerceIn(0, pages.lastIndex); selectedPages.clear(); multiSelectMode = false; render() } else toast("Nothing to undo")
        })
        manageRow.addView(button(if (multiSelectMode) "Exit Select" else "Multi-select") { multiSelectMode = !multiSelectMode; selectedPages.clear(); render() })
        manageRow.addView(button("Select All") { multiSelectMode = true; selectedPages.clear(); selectedPages.addAll(pages.indices); selectionLabel.text = "${selectedPages.size} selected"; refreshThumbnails() })
        controls.addView(manageRow)
        val deleteRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        deleteRow.addView(button(if (multiSelectMode && selectedPages.isNotEmpty()) "Delete Selected" else "Delete Page") {
            val targets = if (multiSelectMode && selectedPages.isNotEmpty()) selectedPages.sortedDescending() else listOf(pageIndex)
            if (pages.size - targets.size < 1) toast("At least one page is required") else {
                savePageUndo()
                targets.forEach { idx -> pages.removeAt(idx); filters.removeAt(idx); rotations.removeAt(idx) }
                selectedPages.clear(); multiSelectMode = false; pageIndex = pageIndex.coerceAtMost(pages.lastIndex); render()
            }
        })
        controls.addView(deleteRow)

        // Split the former long review form into three focused steps.
        // Step 1: image adjustments. Step 2: page management. Step 3: PDF export.
        val originalControls = (0 until controls.childCount).map { controls.getChildAt(it) }
        controls.removeAllViews()
        val imagePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val managePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val exportPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        originalControls.forEachIndexed { index, child ->
            when (index) {
                in 0..13 -> imagePage.addView(child)
                in 14..23 -> exportPage.addView(child)
                in 24..29 -> managePage.addView(child)
            }
        }
        val pageFlipper = android.widget.ViewFlipper(this).apply {
            addView(ScrollView(this@MainActivity).apply { isFillViewport = true; addView(imagePage) })
            addView(ScrollView(this@MainActivity).apply { isFillViewport = true; addView(managePage) })
            addView(ScrollView(this@MainActivity).apply { isFillViewport = true; addView(exportPage) })
        }
        val pageIndicator = TextView(this).apply {
            setTextColor(0xFF00D6C8.toInt()); gravity = Gravity.CENTER; textSize = 14f
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        val pageNavigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(10))
        }
        val previousStep = Button(this).apply { text = "‹ Back"; isAllCaps = false }
        val nextStep = Button(this).apply { text = "Next ›"; isAllCaps = false }
        pageNavigation.addView(previousStep, LinearLayout.LayoutParams(0, dp(46), 1f))
        pageNavigation.addView(nextStep, LinearLayout.LayoutParams(0, dp(46), 1f))
        fun updateReviewStep() {
            pageIndicator.text = when (pageFlipper.displayedChild) {
                0 -> "Step 1 of 3  •  Image adjustments"
                1 -> "Step 2 of 3  •  Manage pages"
                else -> "Step 3 of 3  •  PDF export"
            }
            previousStep.isEnabled = pageFlipper.displayedChild > 0
            nextStep.isEnabled = pageFlipper.displayedChild < 2
        }
        previousStep.setOnClickListener { if (pageFlipper.displayedChild > 0) pageFlipper.showPrevious(); updateReviewStep() }
        nextStep.setOnClickListener { if (pageFlipper.displayedChild < 2) pageFlipper.showNext(); updateReviewStep() }
        val reviewRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101614.toInt())
            addView(pageIndicator, LinearLayout.LayoutParams(-1, -2))
            addView(pageFlipper, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(pageNavigation, LinearLayout.LayoutParams(-1, -2))
        }
        updateReviewStep()
        val dialog=AlertDialog.Builder(this).setTitle("Review & Edit Pages").setView(reviewRoot).setNegativeButton("Discard",null).setPositiveButton("Save PDF",null).create()
        render()
        dialog.setOnDismissListener { image.setImageDrawable(null); displayBitmap?.recycle(); originalBitmap?.recycle(); displayBitmap = null; originalBitmap = null }
        dialog.setOnShowListener {
            dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.88f).toInt())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (pageFlipper.displayedChild != 2) {
                    pageFlipper.displayedChild = 2
                    updateReviewStep()
                    Toast.makeText(this, "PDF export settings check karke Save PDF dabayein", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                try {
                    val pdf=PdfDocument()
                    pages.forEachIndexed { index,uri ->
                        val source=decodeBitmapWithExif(uri) ?: throw IllegalStateException("Page ${index+1} load failed")
                        var bitmap=applyDocumentFilter(source,filters[index])
                        if(rotations[index]%360!=0){val m=android.graphics.Matrix().apply{postRotate(rotations[index].toFloat())};val r=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,m,true);if(bitmap!==source)bitmap.recycle();bitmap=r}
                        val scale=contrast/100f
                        val offset=brightness*2.55f+127f*(1f-scale)
                        if(brightness!=0||contrast!=100){val out=Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888);val p=Paint(Paint.FILTER_BITMAP_FLAG).apply{colorFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(scale,0f,0f,0f,offset,0f,scale,0f,0f,offset,0f,0f,scale,0f,offset,0f,0f,0f,1f,0f)))};Canvas(out).drawBitmap(bitmap,0f,0f,p);if(bitmap!==source)bitmap.recycle();bitmap=out}
                        if(sharpness>0){val sharpened=applySharpness(bitmap,sharpness);if(bitmap!==source)bitmap.recycle();bitmap=sharpened}
                        val enhanced = enhanceImage(bitmap, enhancementMode)
                        if (enhanced !== bitmap) { if (bitmap !== source) bitmap.recycle(); bitmap = enhanced }
                        val exportSize = ScannerExportRules.scaledDimensions(bitmap.width, bitmap.height, exportQuality.selectedItemPosition)
                        if (exportSize.first != bitmap.width || exportSize.second != bitmap.height) {
                            val scaled = Bitmap.createScaledBitmap(bitmap, exportSize.first, exportSize.second, true)
                            if (bitmap !== source) bitmap.recycle()
                            bitmap = scaled
                        }
                        val originalW = bitmap.width
                        val originalH = bitmap.height
                        val a4 = paperSize.selectedItemPosition == 1
                        var pageW = if (a4) 595 else originalW
                        var pageH = if (a4) 842 else originalH
                        val landscape = pageOrientation.selectedItemPosition == 2 ||
                            (pageOrientation.selectedItemPosition == 0 && originalW > originalH)
                        if (pageOrientation.selectedItemPosition != 0) {
                            pageW = if (landscape) 842 else 595
                            pageH = if (landscape) 595 else 842
                        } else if (a4 && landscape) {
                            pageW = 842; pageH = 595
                        }
                        val info=PdfDocument.PageInfo.Builder(pageW,pageH,index+1).create()
                        val page=pdf.startPage(info)
                        page.canvas.drawColor(Color.WHITE)
                        val fit = ScannerExportRules.fitRect(bitmap.width, bitmap.height, pageW, pageH, pageMargins.selectedItemPosition)
                        val dest = RectF(fit[0], fit[1], fit[2], fit[3])
                        page.canvas.drawBitmap(bitmap,null,dest,Paint(Paint.FILTER_BITMAP_FLAG))
                        pdf.finishPage(page)
                        if(bitmap!==source)bitmap.recycle();source.recycle()
                    }
                    val baseName = ScannerExportRules.safePdfFileName(pdfName.text.toString(), "MyDocs_${System.currentTimeMillis()}")
                    val target=File(activeDir,uniqueName(baseName))
                    target.outputStream().use{pdf.writeTo(it)};pdf.close()
                    dialog.dismiss();refreshList();toast("PDF saved: ${target.name} (${exportQuality.selectedItem})")
                } catch(e:Exception){toast("PDF save failed: ${e.localizedMessage}")}
            }
        }
        dialog.show()
    }

    private fun showPerspectiveCrop(uri: Uri, onApplied: (Uri) -> Unit) {
        val source = decodeBitmapWithExif(uri, 3000)
        if (source == null) { toast("Image open nahi hua"); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF101614.toInt())
        }
        val heading = TextView(this).apply {
            text = "Adjust document corners"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        }
        val hint = TextView(this).apply {
            text = "Drag the 4 green handles to fit the page"
            textSize = 13f
            setTextColor(0xFFB8C8C3.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        val editor = PerspectiveCropView(this, source)
        root.addView(heading, LinearLayout.LayoutParams(-1, dp(34)))
        root.addView(hint, LinearLayout.LayoutParams(-1, dp(30)))
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))

        fun toolButton(title: String, action: () -> Unit) = Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 13f
            setOnClickListener { action() }
        }
        val tools = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        tools.addView(toolButton("Auto Crop") { editor.autoCrop() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        tools.addView(toolButton("Full Image") { editor.fullImage() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(tools)

        val footer = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        val dialog = AlertDialog.Builder(this).setView(root).create()
        val cancel = toolButton("Cancel") { dialog.dismiss() }
        val apply = toolButton("Apply Crop") {
            try {
                val p = editor.sourcePoints()
                val tl = PointF(p[0], p[1])
                val tr = PointF(p[2], p[3])
                val br = PointF(p[4], p[5])
                val bl = PointF(p[6], p[7])
                val outW = max(
                    hypot((tr.x - tl.x).toDouble(), (tr.y - tl.y).toDouble()),
                    hypot((br.x - bl.x).toDouble(), (br.y - bl.y).toDouble())
                ).roundToInt().coerceIn(2, 5000)
                val outH = max(
                    hypot((bl.x - tl.x).toDouble(), (bl.y - tl.y).toDouble()),
                    hypot((br.x - tr.x).toDouble(), (br.y - tr.y).toDouble())
                ).roundToInt().coerceIn(2, 7000)
                val srcPts = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)
                val dstPts = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
                val matrix = android.graphics.Matrix()
                if (!matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) {
                    throw IllegalArgumentException("Corners invalid")
                }
                val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                Canvas(result).drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                val file = File(docsDir, ".crop_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                result.recycle()
                onApplied(FileProvider.getUriForFile(this, "$packageName.provider", file))
                dialog.dismiss()
            } catch (e: Exception) {
                toast("Crop failed: ${e.localizedMessage}")
            }
        }
        footer.addView(cancel, LinearLayout.LayoutParams(0, dp(52), 1f))
        footer.addView(apply, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(footer, LinearLayout.LayoutParams(-1, dp(58)))

        dialog.setOnDismissListener { if (!source.isRecycled) source.recycle() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun applySharpness(source: Bitmap, amount: Int): Bitmap {
        if (amount <= 0 || source.width < 3 || source.height < 3) return source
        val w = source.width
        val h = source.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val previous = IntArray(w)
        val current = IntArray(w)
        val next = IntArray(w)
        val output = IntArray(w)
        val strength = (amount / 100f) * 1.5f

        source.getPixels(current, 0, w, 0, 0, w, 1)
        result.setPixels(current, 0, w, 0, 0, w, 1)
        source.getPixels(previous, 0, w, 0, 0, w, 1)

        for (y in 1 until h - 1) {
            source.getPixels(current, 0, w, 0, y, w, 1)
            source.getPixels(next, 0, w, 0, y + 1, w, 1)
            output[0] = current[0]
            output[w - 1] = current[w - 1]
            for (x in 1 until w - 1) {
                val color = current[x]
                fun channel(shift: Int): Int {
                    val center = (color ushr shift) and 255
                    val neighbors = ((previous[x] ushr shift) and 255) +
                        ((current[x - 1] ushr shift) and 255) +
                        ((current[x + 1] ushr shift) and 255) +
                        ((next[x] ushr shift) and 255)
                    return (center + strength * (4 * center - neighbors) / 4f)
                        .toInt().coerceIn(0, 255)
                }
                output[x] = (Color.alpha(color) shl 24) or
                    (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
            result.setPixels(output, 0, w, 0, y, w, 1)
            System.arraycopy(current, 0, previous, 0, w)
        }
        source.getPixels(current, 0, w, 0, h - 1, w, 1)
        result.setPixels(current, 0, w, 0, h - 1, w, 1)
        return result
    }

    private fun applyDocumentFilter(source: Bitmap, filter: Int): Bitmap {
        if (filter == 0) return source
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        when (filter) {
            1 -> {
                val row = IntArray(source.width)
                for (y in 0 until source.height) {
                    source.getPixels(row, 0, source.width, 0, y, source.width, 1)
                    for (x in row.indices) {
                        val color = row[x]
                        val gray = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
                        val value = if (gray >= 170) 255 else 0
                        row[x] = Color.rgb(value, value, value)
                    }
                    result.setPixels(row, 0, source.width, 0, y, source.width, 1)
                }
                return result
            }
            2 -> paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            3 -> paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                1.18f, 0f, 0f, 0f, 8f,
                0f, 1.18f, 0f, 0f, 8f,
                0f, 0f, 1.18f, 0f, 8f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        Canvas(result).drawBitmap(source, null, RectF(0f, 0f, result.width.toFloat(), result.height.toFloat()), paint)
        return result
    }

    private fun refreshList() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val dir = activeDir
        val query = if (::searchInput.isInitialized) searchInput.text.toString().trim().lowercase() else ""
        folderPathLabel.text = if (dir == docsDir) "📁 All Documents" else "← Back    📁 " + dir.relativeTo(docsDir).invariantSeparatorsPath
        folderPathLabel.setOnClickListener {
            if (dir != docsDir) {
                currentFolder = if (dir.parentFile == docsDir) null else dir.parentFile
                refreshList()
            } else showFolderPicker()
        }
        val folders = if (favoritesMode) emptyList() else dir.listFiles()?.filter { it.isDirectory && it.name.lowercase().contains(query) }?.sortedBy { it.name.lowercase() }.orEmpty()
        val allFiles = if (favoritesMode) allPdfFiles() else dir.listFiles()?.filter { it.isFile && it.extension.equals("pdf", true) }.orEmpty()
        val favoritePaths = getSharedPreferences("mydocs_data", MODE_PRIVATE).getStringSet("favorites", emptySet()).orEmpty()
        val files = allFiles.filter { file -> (query.isEmpty() || file.nameWithoutExtension.lowercase().contains(query) || (ocrCacheFile(file).takeIf { it.exists() }?.readText()?.contains(query, ignoreCase = true) == true)) && (!favoritesMode || relativeDocPath(file) in favoritePaths) }
            .sortedByDescending { getSharedPreferences("mydocs_data", MODE_PRIVATE).getLong("opened_${relativeDocPath(it)}", it.lastModified()) }
        count.text = (if (favoritesMode) "Favorites (" else "Recent Documents (") + files.size + ")"
        folderStrip.removeAllViews()
        folderStrip.visibility = if (favoritesMode) View.GONE else View.VISIBLE
        if (folders.isEmpty()) {
            folderStrip.addView(TextView(this).apply {
                text = if (query.isEmpty()) "No folders yet. Tap + Folder to create one." else "No matching folders"
                textSize = 13f; setTextColor(0xFF82918D.toInt()); setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            var index = 0
            while (index < folders.size) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (slot in 0..1) {
                    if (index < folders.size) {
                        val folder = folders[index++]
                        val childCount = folder.listFiles()?.count { it.isFile && it.extension.equals("pdf", true) } ?: 0
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(15), dp(12), dp(10), dp(12))
                            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF20292D.toInt()); cornerRadius = dp(18).toFloat() }
                            setOnClickListener { currentFolder = folder; if (::searchInput.isInitialized) searchInput.setText(""); refreshList() }
                            setOnLongClickListener { showFolderMenu(folder); true }
                        }
                        card.addView(TextView(this).apply { text = "▱"; textSize = 25f; setTextColor(if (slot == 0) 0xFFE8C16A.toInt() else 0xFF91B9FF.toInt()) })
                        card.addView(TextView(this).apply { text = folder.name; textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, dp(5)) })
                        card.addView(TextView(this).apply { text = childCount.toString() + " files"; textSize = 13f; setTextColor(0xFF9BAAA7.toInt()) })
                        row.addView(card, LinearLayout.LayoutParams(0, dp(112), 1f).apply { marginEnd = if (slot == 0) dp(6) else 0; marginStart = if (slot == 1) dp(6) else 0; bottomMargin = dp(8) })
                    } else row.addView(View(this), LinearLayout.LayoutParams(0, dp(112), 1f))
                }
                folderStrip.addView(row)
            }
        }
        if (files.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (query.isEmpty()) "No documents yet\nTap Scan Document to create your first PDF." else "No matching documents"
                gravity = Gravity.CENTER; textSize = 15f; setTextColor(0xFF9CA3AF.toInt()); setPadding(dp(20), dp(34), dp(20), dp(34))
            })
        } else if (gridView) {
            var index = 0
            while (index < files.size) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (slot in 0..1) {
                    if (index < files.size) {
                        val file = files[index++]
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(14), dp(8), dp(10))
                            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF18221F.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF293832.toInt()) }
                            setOnClickListener { openPdf(file) }
                        }
                        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        top.addView(TextView(this).apply { text = "▤"; textSize = 26f; setTextColor(0xFFFF8C83.toInt()) }, LinearLayout.LayoutParams(0, -2, 1f))
                        top.addView(TextView(this).apply { text = "⋮"; textSize = 22f; setTextColor(Color.WHITE); setOnClickListener { showFileMenu(file) } })
                        card.addView(top)
                        card.addView(TextView(this).apply { text = file.name; textSize = 13f; setTextColor(Color.WHITE); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, dp(4)) })
                        card.addView(TextView(this).apply { text = formatSize(file.length()); textSize = 11f; setTextColor(0xFF91A29D.toInt()) })
                        row.addView(card, LinearLayout.LayoutParams(0, dp(140), 1f).apply { marginEnd = if (slot == 0) dp(6) else 0; marginStart = if (slot == 1) dp(6) else 0; bottomMargin = dp(10) })
                    } else row.addView(View(this), LinearLayout.LayoutParams(0, dp(140), 1f))
                }
                list.addView(row)
            }
        } else {
            files.forEach { file ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(13), dp(6), dp(13))
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF18221F.toInt()); cornerRadius = dp(12).toFloat() }
                }
                row.addView(TextView(this).apply { text = "▤"; textSize = 27f; setTextColor(0xFFFF8C83.toInt()); setPadding(0, 0, dp(12), 0) })
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(TextView(this).apply { text = file.name; textSize = 14f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                info.addView(TextView(this).apply { text = formatSize(file.length()) + "  •  " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.lastModified())); textSize = 11f; setTextColor(0xFF91A29D.toInt()); setPadding(0, dp(4), 0, 0) })
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(this).apply { text = "⋮"; textSize = 25f; setTextColor(Color.WHITE); setPadding(dp(14), 0, dp(12), 0); setOnClickListener { showFileMenu(file) } })
                row.setOnClickListener { openPdf(file) }
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
            }
        }
    }
    private val ocrCacheDir by lazy { File(filesDir, "MyDocsOcrCache").apply { mkdirs() } }

    private fun ocrCacheFile(file: File): File {
        val key = java.security.MessageDigest.getInstance("MD5").digest(relativeDocPath(file).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(ocrCacheDir, "$key.txt")
    }

    private fun indexPdfText(file: File) {
        val progress = ProgressBar(this).apply { isIndeterminate = true }
        val dialog = AlertDialog.Builder(this).setTitle("Reading document")
            .setMessage("Hindi + English OCR is extracting text. Please wait.")
            .setView(progress).setNegativeButton("Cancel", null).create()
        dialog.show()
        Thread {
            var descriptor: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val devanagariRecognizer = TextRecognition.getClient(
                com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions.Builder().build()
            )
            try {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val pdfRenderer = PdfRenderer(descriptor)
                renderer = pdfRenderer
                val text = StringBuilder()
                for (index in 0 until pdfRenderer.pageCount) {
                    val page = pdfRenderer.openPage(index)
                    val bitmap = try {
                        val scale = (1500f / page.width).coerceAtMost(2f)
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        }
                    } finally { page.close() }
                    try {
                        val input = InputImage.fromBitmap(bitmap, 0)
                        val latin = com.google.android.gms.tasks.Tasks.await(latinRecognizer.process(input)).text
                        val devanagari = com.google.android.gms.tasks.Tasks.await(devanagariRecognizer.process(input)).text
                        val lines = (latin.lines() + devanagari.lines())
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinctBy { it.lowercase().replace(Regex("\\s+"), "") }
                        text.append(lines.joinToString("\n")).append("\n\n")
                    } finally { bitmap.recycle() }
                }
                ocrCacheFile(file).writeText(text.toString())
                runOnUiThread {
                    if (!isFinishing) {
                        dialog.dismiss()
                        toast("Hindi + English OCR complete. Ab PDF ke andar ka text search kar sakte ho.")
                        refreshList()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        dialog.dismiss()
                        toast("OCR failed: ${e.localizedMessage}")
                    }
                }
            } finally {
                try { renderer?.close() } catch (_: Exception) {}
                try { descriptor?.close() } catch (_: Exception) {}
                latinRecognizer.close()
                devanagariRecognizer.close()
            }
        }.start()
    }

    private fun showOcrText(file: File) {
        val cache = ocrCacheFile(file)
        if (!cache.exists()) {
            AlertDialog.Builder(this).setMessage("Is PDF ka OCR text abhi available nahi hai. Pehle Index text (OCR) chalao.")
                .setPositiveButton("Index now") { _, _ -> indexPdfText(file) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val extracted = cache.readText().trim()
        if (extracted.isBlank()) {
            toast("Is PDF se readable text nahi mila.")
            return
        }
        val textView = TextView(this).apply {
            text = extracted
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(textView) }
        AlertDialog.Builder(this).setTitle("Extracted text")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(file.name, extracted))
                toast("OCR text copied")
            }
            .setNeutralButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Text from ${file.name}")
                    putExtra(Intent.EXTRA_TEXT, extracted)
                }
                startActivity(Intent.createChooser(send, "Share extracted text"))
            }
            .setNegativeButton("Close", null).show()
    }

    private fun createFolder() {
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this).setTitle("Create folder").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || name == "." || name == ".." || name.contains("/") || name.contains("\\\\")) toast("Enter a valid folder name")
                else {
                    val folder = File(activeDir, name)
                    if (folder.exists()) toast("Folder already exists")
                    else if (folder.mkdirs()) refreshList() else toast("Folder could not be created")
                }
            }.show()
    }

    private fun showFolderPicker() {
        val options = mutableListOf("All Documents")
        val dirs = mutableListOf<File?>(null)
        fun addChildren(parent: File, prefix: String) {
            parent.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { folder ->
                options.add(prefix + folder.name); dirs.add(folder)
                addChildren(folder, prefix + folder.name + " / ")
            }
        }
        addChildren(docsDir, "")
        AlertDialog.Builder(this).setTitle("Go to folder").setItems(options.toTypedArray()) { _, which ->
            currentFolder = dirs[which]; refreshList()
        }.show()
    }

    private fun showFolderMenu(folder: File) {
        AlertDialog.Builder(this).setTitle(folder.name).setItems(arrayOf("Open", "Rename", "Delete")) { _, which ->
            when (which) {
                0 -> { currentFolder = folder; refreshList() }
                1 -> {
                    val input = EditText(this).apply { setText(folder.name); selectAll() }
                    AlertDialog.Builder(this).setTitle("Rename folder").setView(input)
                        .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isNotBlank() && !name.contains("/") && !name.contains("\\\\")) {
                                val target = File(folder.parentFile, name)
                                if (!target.exists() && folder.renameTo(target)) {
                                    if (currentFolder == folder) currentFolder = target
                                    refreshList()
                                } else toast("Folder rename failed")
                            }
                        }.show()
                }
                2 -> AlertDialog.Builder(this).setMessage("Delete folder and all its contents?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        if (folder.deleteRecursively()) {
                            if (currentFolder == folder || currentFolder?.path?.startsWith(folder.path + File.separator) == true) currentFolder = null
                            refreshList()
                        } else toast("Folder could not be deleted")
                    }.show()
            }
        }.show()
    }

    private fun moveFile(file: File) {
        val folders = mutableListOf<File?>(null)
        val labels = mutableListOf("All Documents")
        fun addChildren(parent: File, prefix: String) {
            parent.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { folder ->
                folders.add(folder); labels.add(prefix + folder.name)
                addChildren(folder, prefix + folder.name + " / ")
            }
        }
        addChildren(docsDir, "")
        AlertDialog.Builder(this).setTitle("Move ${file.name} to").setItems(labels.toTypedArray()) { _, which ->
            val destination = folders[which] ?: docsDir
            if (file.parentFile == destination) toast("Already in this folder")
            else {
                val target = File(destination, uniqueNameFor(destination, file.name))
                if (file.renameTo(target)) refreshList() else toast("Move failed")
            }
        }.show()
    }

    private fun uniqueNameFor(dir: File, name: String): String {
        var candidate = name; var index = 1
        while (File(dir, candidate).exists()) {
            candidate = "${name.substringBeforeLast('.')}_$index.${name.substringAfterLast('.', "pdf")}"
            index++
        }
        return candidate
    }

    private fun mergePdfs(uris: List<Uri>) {
        if (uris.size < 2) { toast("Merge ke liye kam se kam 2 PDF chunein"); return }
        try {
            val output = PdfDocument()
            var pageNumber = 1
            uris.forEach { uri ->
                val descriptor = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Selected PDF open nahi hui")
                val renderer = PdfRenderer(descriptor)
                try {
                    for (index in 0 until renderer.pageCount) {
                        val sourcePage = renderer.openPage(index)
                        try {
                            val scale = (1800f / sourcePage.width).coerceAtMost(2f)
                            val width = (sourcePage.width * scale).toInt().coerceAtLeast(1)
                            val height = (sourcePage.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            sourcePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val info = PdfDocument.PageInfo.Builder(width, height, pageNumber++).create()
                            val page = output.startPage(info)
                            page.canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                            output.finishPage(page)
                            bitmap.recycle()
                        } finally { sourcePage.close() }
                    }
                } finally { renderer.close(); descriptor.close() }
            }
            val target = File(activeDir, uniqueName("Merged_${System.currentTimeMillis()}.pdf"))
            target.outputStream().use { output.writeTo(it) }
            output.close()
            refreshList()
            toast("Merged PDF saved: ${target.name}")
        } catch (e: Exception) {
            toast("Merge failed: ${e.localizedMessage}")
        }
    }

    private fun splitPdf(uri: Uri) {
        try {
            val descriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("PDF open nahi hui")
            val renderer = PdfRenderer(descriptor)
            val totalPages = renderer.pageCount
            val baseName = queryName(uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Split"
            val outputFolder = File(activeDir, uniqueFolderName(baseName + "_pages"))
            if (!outputFolder.mkdirs()) throw IllegalStateException("Output folder nahi ban paya")
            try {
                for (index in 0 until renderer.pageCount) {
                    val sourcePage = renderer.openPage(index)
                    try {
                        val scale = (1800f / sourcePage.width).coerceAtMost(2f)
                        val width = (sourcePage.width * scale).toInt().coerceAtLeast(1)
                        val height = (sourcePage.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        sourcePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val pdf = PdfDocument()
                        val info = PdfDocument.PageInfo.Builder(width, height, 1).create()
                        val page = pdf.startPage(info)
                        page.canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                        pdf.finishPage(page)
                        File(outputFolder, "Page_${index + 1}.pdf").outputStream().use { pdf.writeTo(it) }
                        pdf.close()
                        bitmap.recycle()
                    } finally { sourcePage.close() }
                }
            } finally { renderer.close(); descriptor.close() }
            refreshList()
            toast("${totalPages} pages split into ${outputFolder.name}")
        } catch (e: Exception) {
            toast("Split failed: ${e.localizedMessage}")
        }
    }

    private fun uniqueFolderName(name: String): String {
        var candidate = name
        var index = 1
        while (File(activeDir, candidate).exists()) candidate = "${name}_${index++}"
        return candidate
    }

    private fun editPdfPages(file: File) {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            val pdf = renderer
            val order = (0 until pdf.pageCount).toMutableList()
            val rotations = IntArray(pdf.pageCount)
            var selected = 0
            val label = TextView(this).apply {
                setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
                setPadding(dp(8), dp(14), dp(8), dp(14))
            }
            val preview = ImageView(this).apply {
                adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.WHITE)
            }
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val row1 = LinearLayout(this).apply { gravity = Gravity.CENTER }
            val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER }
            fun smallButton(textValue: String, action: () -> Unit) = Button(this).apply {
                text = textValue; isAllCaps = false; setOnClickListener { action() }
            }
            fun renderSelected() {
                label.text = "Page ${selected + 1} of ${order.size}  •  Source page ${order[selected] + 1}"
                val source = pdf.openPage(order[selected])
                try {
                    val maxWidth = (resources.displayMetrics.widthPixels - dp(56)).coerceAtMost(900)
                    val scale = (maxWidth.toFloat() / source.width).coerceAtMost(1.5f)
                    val bitmap = Bitmap.createBitmap((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val rotated = if (rotations[order[selected]] % 360 == 0) bitmap else {
                        val matrix = android.graphics.Matrix().apply { postRotate(rotations[order[selected]].toFloat()) }
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
                    }
                    preview.setImageBitmap(rotated)
                    preview.tag = rotated
                } finally { source.close() }
            }
            row1.addView(smallButton("‹ Prev") { if (selected > 0) { selected--; renderSelected() } })
            row1.addView(smallButton("Next ›") { if (selected < order.lastIndex) { selected++; renderSelected() } })
            row2.addView(smallButton("↶ Rotate") { rotations[order[selected]] = (rotations[order[selected]] + 270) % 360; renderSelected() })
            row2.addView(smallButton("Rotate ↻") { rotations[order[selected]] = (rotations[order[selected]] + 90) % 360; renderSelected() })
            row2.addView(smallButton("↑ Move") {
                if (selected > 0) { val item = order.removeAt(selected); order.add(selected - 1, item); selected--; renderSelected() }
            })
            row2.addView(smallButton("↓ Move") {
                if (selected < order.lastIndex) { val item = order.removeAt(selected); order.add(selected + 1, item); selected++; renderSelected() }
            })
            controls.addView(label)
            controls.addView(preview, LinearLayout.LayoutParams(-1, dp(330)))
            controls.addView(row1)
            controls.addView(row2)
            renderSelected()
            val dialog = AlertDialog.Builder(this).setTitle("Rotate / Reorder Pages")
                .setView(controls).setNegativeButton("Cancel", null)
                .setPositiveButton("Save as new PDF", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    try {
                        val output = PdfDocument()
                        order.forEachIndexed { index, sourceIndex ->
                            val source = pdf.openPage(sourceIndex)
                            try {
                                val scale = (1600f / source.width).coerceAtMost(2f)
                                val width = (source.width * scale).toInt().coerceAtLeast(1)
                                val height = (source.height * scale).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE)
                                source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val rotated = if (rotations[sourceIndex] % 360 == 0) bitmap else {
                                    val matrix = android.graphics.Matrix().apply { postRotate(rotations[sourceIndex].toFloat()) }
                                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
                                }
                                val info = PdfDocument.PageInfo.Builder(rotated.width, rotated.height, index + 1).create()
                                val page = output.startPage(info)
                                page.canvas.drawBitmap(rotated, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                                output.finishPage(page)
                                rotated.recycle()
                            } finally { source.close() }
                        }
                        val target = File(activeDir, uniqueName(file.nameWithoutExtension + "_edited.pdf"))
                        target.outputStream().use { output.writeTo(it) }
                        output.close()
                        dialog.dismiss()
                        refreshList()
                        toast("Edited PDF saved: ${target.name}")
                    } catch (e: Exception) { toast("Page editing failed: ${e.localizedMessage}") }
                }
            }
            dialog.setOnDismissListener {
                (preview.tag as? Bitmap)?.let { if (!it.isRecycled) it.recycle() }
                preview.setImageDrawable(null)
                pdf.close()
                descriptor?.close()
            }
            dialog.show()
        } catch (e: Exception) {
            try { renderer?.close() } catch (_: Exception) {}
            try { descriptor?.close() } catch (_: Exception) {}
            toast("PDF open nahi hui: ${e.localizedMessage}")
        }
    }

    private fun showFileMenu(file: File) {
        val options = arrayOf("Open", "Share", if (isFavorite(file)) "Remove from Favorites" else "Add to Favorites", "Index text (OCR)", "View / Copy OCR text", "Rotate / Reorder Pages", "Rename", "Move to folder", "Delete")
        AlertDialog.Builder(this).setTitle(file.name).setItems(options) { _, which ->
            when (which) {
                0 -> openPdf(file)
                1 -> sharePdf(file)
                2 -> toggleFavorite(file)
                3 -> indexPdfText(file)
                4 -> showOcrText(file)
                5 -> editPdfPages(file)
                6 -> renamePdf(file)
                7 -> moveFile(file)
                8 -> AlertDialog.Builder(this).setMessage("Delete ${file.name}?").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> if (file.delete()) { removeFromRecentAndFavorites(file); ocrCacheFile(file).delete(); refreshList() } else toast("Delete failed") }.show()
            }
        }.show()
    }

    private fun openPdf(file: File) {
        addToRecent(file)
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var pageIndex = 0
        var currentBitmap: Bitmap? = null
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            val pdf = renderer
            lateinit var dialog: AlertDialog
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF101614.toInt())
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(8), dp(10))
            }
            val title = TextView(this).apply {
                text = file.name
                textSize = 15f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            val close = Button(this).apply {
                text = "Close"
                isAllCaps = false
                setOnClickListener { dialog.dismiss() }
            }
            top.addView(close)
            content.addView(top)
            val pageLabel = TextView(this).apply {
                setTextColor(0xFF9BAAA7.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(8))
            }
            content.addView(pageLabel)
            val image = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.WHITE)
            }
            val imageScroll = ScrollView(this)
            imageScroll.addView(image)
            content.addView(imageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(12))
            }
            val previous = Button(this).apply { text = "‹ Previous"; isAllCaps = false }
            val next = Button(this).apply { text = "Next ›"; isAllCaps = false }
            controls.addView(previous, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(next, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(controls)
            dialog = AlertDialog.Builder(this).setView(content).create()
            fun renderPage() {
                currentBitmap?.recycle()
                currentBitmap = null
                val page = pdf.openPage(pageIndex)
                try {
                    val maxWidth = (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(1100)
                    val scale = maxWidth.toFloat() / page.width.toFloat()
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    currentBitmap = bitmap
                    image.setImageBitmap(bitmap)
                    imageScroll.scrollTo(0, 0)
                    pageLabel.text = "Page ${pageIndex + 1} of ${pdf.pageCount}"
                    previous.isEnabled = pageIndex > 0
                    next.isEnabled = pageIndex < pdf.pageCount - 1
                } finally {
                    page.close()
                }
            }
            previous.setOnClickListener { if (pageIndex > 0) { pageIndex--; renderPage() } }
            next.setOnClickListener { if (pageIndex < pdf.pageCount - 1) { pageIndex++; renderPage() } }
            renderPage()
            dialog.setOnDismissListener {
                image.setImageDrawable(null)
                currentBitmap?.recycle()
                currentBitmap = null
                pdf.close()
                descriptor?.close()
            }
            dialog.show()
            dialog.window?.setLayout(-1, -1)
        } catch (e: Exception) {
            try { renderer?.close() } catch (_: Exception) {}
            try { descriptor?.close() } catch (_: Exception) {}
            currentBitmap?.recycle()
            toast("PDF open nahi hua: ${e.localizedMessage}")
        }
    }

    private fun relativeDocPath(file: File): String = file.relativeTo(docsDir).invariantSeparatorsPath

    private fun allPdfFiles(): List<File> {
        val result = mutableListOf<File>()
        fun walk(dir: File) {
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) walk(child)
                else if (child.isFile && child.extension.equals("pdf", true)) result.add(child)
            }
        }
        walk(docsDir)
        return result
    }

    private fun isFavorite(file: File): Boolean =
        relativeDocPath(file) in getSharedPreferences("mydocs_data", MODE_PRIVATE).getStringSet("favorites", emptySet()).orEmpty()

    private fun toggleFavorite(file: File) {
        val prefs = getSharedPreferences("mydocs_data", MODE_PRIVATE)
        val favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
        val path = relativeDocPath(file)
        val added = if (path in favorites) { favorites.remove(path); false } else { favorites.add(path); true }
        prefs.edit().putStringSet("favorites", favorites).apply()
        toast(if (added) "Added to Favorites" else "Removed from Favorites")
        refreshList()
    }

    private fun addToRecent(file: File) {
        val prefs = getSharedPreferences("mydocs_data", MODE_PRIVATE)
        val recent = prefs.getStringSet("recent", emptySet()).orEmpty().toMutableSet()
        val path = relativeDocPath(file)
        recent.remove(path)
        recent.add(path)
        prefs.edit().putStringSet("recent", recent).putLong("opened_$path", System.currentTimeMillis()).apply()
    }

    private fun removeFromRecentAndFavorites(file: File) {
        val prefs = getSharedPreferences("mydocs_data", MODE_PRIVATE)
        val path = relativeDocPath(file)
        prefs.edit()
            .putStringSet("recent", prefs.getStringSet("recent", emptySet()).orEmpty().toMutableSet().apply { remove(path) })
            .putStringSet("favorites", prefs.getStringSet("favorites", emptySet()).orEmpty().toMutableSet().apply { remove(path) })
            .remove("opened_$path").apply()
    }

    private fun sharePdf(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share PDF"))
    }

    private fun renamePdf(file: File) {
        val input = EditText(this).apply { setText(file.nameWithoutExtension); selectAll() }
        AlertDialog.Builder(this).setTitle("Rename document").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val rawName = input.text.toString().trim()
                val newName = rawName.replace(Regex("""[\\/:*?"<>|\\p{Cc}]"""), "_").trim()
                if (newName.isNotBlank() && newName != "." && newName != "..") {
                    var target = File(file.parentFile, "$newName.pdf")
                    var suffix = 1
                    while (target.exists() && target != file) {
                        target = File(file.parentFile, "${newName}_${suffix++}.pdf")
                    }
                    if (file.renameTo(target)) toast("Document renamed") else toast("Rename failed")
                } else toast("Enter a valid name")
                refreshList()
            }.show()
    }

    private fun queryName(uri: Uri): String? = contentResolver.query(uri, null, null, null, null)?.use { c ->
        val index = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && c.moveToFirst()) c.getString(index) else null
    }
    private fun uniqueName(name: String, ignore: String? = null): String {
        var candidate = name; var n = 1
        while (File(activeDir, candidate).exists() && candidate != ignore) { candidate = "${name.substringBeforeLast('.')}_$n.${name.substringAfterLast('.', "pdf")}"; n++ }
        return candidate
    }
    override fun onPause() {
        super.onPause()
        getSharedPreferences("mydocs_ui", MODE_PRIVATE).edit()
            .putBoolean("grid_view", gridView)
            .putString("current_folder", currentFolder?.takeIf { it.exists() }?.relativeTo(docsDir)?.invariantSeparatorsPath)
            .apply()
    }

    override fun onBackPressed() {
        val dir = currentFolder
        if (dir != null) {
            currentFolder = if (dir.parentFile == docsDir) null else dir.parentFile
            refreshList()
        } else super.onBackPressed()
    }

    private fun formatSize(bytes: Long): String = if (bytes < 1024*1024) "${bytes/1024} KB" else "%.1f MB".format(bytes/1024.0/1024.0)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
