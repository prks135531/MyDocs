package com.monu.mydocs

data class PdfPaperSize(val label: String, val widthPoints: Int, val heightPoints: Int)

object PaperSizeCatalog {
    val sizes = listOf(
        PdfPaperSize("A0",2384,3370), PdfPaperSize("A1",1684,2384), PdfPaperSize("A2",1191,1684),
        PdfPaperSize("A3",842,1191), PdfPaperSize("A4",595,842), PdfPaperSize("A5",420,595),
        PdfPaperSize("A6",298,420), PdfPaperSize("A7",210,298), PdfPaperSize("A8",147,210),
        PdfPaperSize("B5",499,709), PdfPaperSize("Letter",612,792), PdfPaperSize("Legal",612,1008),
        PdfPaperSize("Ledger",1224,792), PdfPaperSize("Business card",252,144)
    )
    val options: Array<String> = arrayOf("Original size") + sizes.map { it.label }.toTypedArray()
}
