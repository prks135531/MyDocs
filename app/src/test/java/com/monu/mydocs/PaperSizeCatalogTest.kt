package com.monu.mydocs

import org.junit.Assert.*
import org.junit.Test

class PaperSizeCatalogTest {
 @Test fun hasCommonFormats() { listOf("A0","A4","A8","Letter","Legal").forEach { name -> assertTrue(PaperSizeCatalog.sizes.any { it.label == name }) } }
 @Test fun dimensionsArePositive() { assertTrue(PaperSizeCatalog.sizes.all { it.widthPoints > 0 && it.heightPoints > 0 }) }
 @Test fun originalIsFirstOption() { assertEquals("Original size", PaperSizeCatalog.options[0]) }
 @Test fun a4DimensionsAreStandard() { val a4=PaperSizeCatalog.sizes.first { it.label=="A4" }; assertEquals(595,a4.widthPoints); assertEquals(842,a4.heightPoints) }
}
