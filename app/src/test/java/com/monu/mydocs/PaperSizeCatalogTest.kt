package com.monu.mydocs

import org.junit.Assert.*
import org.junit.Test

class PaperSizeCatalogTest {
    @Test fun catalogIncludesCommonPageFormats() {
        assertTrue(PaperSizeCatalog.sizes.any { it.label == "A0" })
        assertTrue(PaperSizeCatalog.sizes.any { it.label == "A8" })
        assertTrue(PaperSizeCatalog.sizes.any { it.label == "A4" })
        assertTrue(PaperSizeCatalog.sizes.any { it.label == "Letter" })
        assertTrue(PaperSizeCatalog.sizes.any { it.label == "Legal" })
    }
    @Test fun dimensionsArePositiveAndOriginalIsFirstOption() {
        assertEquals("Original size", PaperSizeCatalog.options[0])
        assertTrue(PaperSizeCatalog.sizes.all { it.widthPoints > 0 && it.heightPoints > 0 })
        assertEquals(PaperSizeCatalog.sizes.size + 1, PaperSizeCatalog.options.size)
    }
    @Test fun a4UsesStandardPdfPointDimensions() {
        val a4 = PaperSizeCatalog.sizes.first { it.label == "A4" }
        assertEquals(595, a4.widthPoints)
        assertEquals(842, a4.heightPoints)
    }
}
