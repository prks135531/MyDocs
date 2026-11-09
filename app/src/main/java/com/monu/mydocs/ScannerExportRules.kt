package com.monu.mydocs

/** Android-free rules used by PDF export and exercised by local JVM tests. */
object ScannerExportRules {
    fun scaledDimensions(width: Int, height: Int, quality: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val maxDimension = when (quality) { 1 -> 1800; 2 -> 1200; else -> 2800 }
        val longest = maxOf(width, height)
        if (longest <= maxDimension) return width to height
        val scale = maxDimension.toFloat() / longest.toFloat()
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    /** Returns [left, top, right, bottom], preserving aspect ratio and centering. */
    fun fitRect(sourceWidth: Int, sourceHeight: Int, pageWidth: Int, pageHeight: Int, marginMode: Int): FloatArray {
        require(sourceWidth > 0 && sourceHeight > 0 && pageWidth > 0 && pageHeight > 0)
        val margin = pageWidth * when (marginMode) { 1 -> 0.04f; 2 -> 0.08f; else -> 0f }
        val availableWidth = (pageWidth - margin * 2f).coerceAtLeast(1f)
        val availableHeight = (pageHeight - margin * 2f).coerceAtLeast(1f)
        val scale = minOf(availableWidth / sourceWidth, availableHeight / sourceHeight)
        val w = sourceWidth * scale
        val h = sourceHeight * scale
        val left = (pageWidth - w) / 2f
        val top = (pageHeight - h) / 2f
        return floatArrayOf(left, top, left + w, top + h)
    }

    fun safePdfFileName(input: String, fallback: String): String {
        // Escape the backslash and control-character property for Kotlin's regex parser.
        val invalidCharacters = Regex("[\\\\/:*?\"<>|\\p{Cc}]")
        val cleaned = invalidCharacters.replace(input.trim(), "_").trim().trimEnd('.')
        val safeName = cleaned.ifBlank {
            invalidCharacters.replace(fallback.trim(), "_").trim().trimEnd('.').ifBlank { "scan" }
        }
        return if (safeName.endsWith(".pdf", true)) safeName else "$safeName.pdf"
    }
}
