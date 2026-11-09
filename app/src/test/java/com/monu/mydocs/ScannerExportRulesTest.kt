package com.monu.mydocs

import org.junit.Assert.*
import org.junit.Test

class ScannerExportRulesTest {
    @Test fun qualityPresetsRespectMaximumDimensions() {
        assertEquals(2800 to 1400, ScannerExportRules.scaledDimensions(4000, 2000, 0))
        assertEquals(1800 to 900, ScannerExportRules.scaledDimensions(4000, 2000, 1))
        assertEquals(1200 to 600, ScannerExportRules.scaledDimensions(4000, 2000, 2))
    }
    @Test fun smallerImagesAreNotUpscaled() {
        assertEquals(900 to 600, ScannerExportRules.scaledDimensions(900, 600, 2))
    }
    @Test fun fitRectPreservesAspectRatioAndCenters() {
        val r = ScannerExportRules.fitRect(1000, 500, 595, 842, 0)
        assertEquals(595f, r[2] - r[0], 0.01f)
        assertEquals(297.5f, r[3] - r[1], 0.01f)
        assertEquals((842f - 297.5f) / 2f, r[1], 0.01f)
    }
    @Test fun marginsReduceImageArea() {
        val no = ScannerExportRules.fitRect(1000, 1000, 595, 842, 0)
        val large = ScannerExportRules.fitRect(1000, 1000, 595, 842, 2)
        assertTrue(large[0] > no[0])
        assertTrue(large[2] < no[2])
    }
    @Test fun fileNamesAreSafeAndHavePdfExtension() {
        assertEquals("scan_test.pdf", ScannerExportRules.safePdfFileName("scan:test", "fallback"))
        assertEquals("report.PDF", ScannerExportRules.safePdfFileName("report.PDF", "fallback"))
        assertEquals("fallback.pdf", ScannerExportRules.safePdfFileName(" ", "fallback"))
    }
}
