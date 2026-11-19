package paperknife.core

import com.sun.jna.Pointer
import paperknife.core.pdfium.PdfiumLibrary
import paperknife.core.pdfium.PdfiumNative
import paperknife.core.pdfium.PdfiumNative.FPDF_ClosePage
import paperknife.core.pdfium.PdfiumNative.FPDFFont_GetGlyphWidth
import paperknife.core.pdfium.PdfiumNative.FPDFPage_GenerateContent
import paperknife.core.pdfium.PdfiumNative.FPDFPage_InsertObject
import paperknife.core.pdfium.PdfiumNative.FPDFPage_New
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_NewTextObj
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_Transform
import paperknife.core.pdfium.PdfiumNative.FPDFText_SetText
import paperknife.core.pdfium.PdfiumNative.FPDFTextObj_GetFont
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Builds PDFs with PDFium at test time, so the repository never ships documents it doesn't own
 * (a port of TestPdfs in PaperKnife for Windows' tests, so far the parts these tests use).
 */
internal object TestPdfs {
    /** Letter-size pages whose only text is "Page N". */
    fun numberedPages(count: Int): PdfSnapshot {
        synchronized(PdfiumLibrary.sync) {
            PdfDocument.createNew().use { document ->
                for (i in 0 until count) {
                    val page = assertNotNull(FPDFPage_New(document.handle, i, 612.0, 792.0))
                    addStandardText(document.handle, page, "Page ${i + 1}", 72.0, 700.0, 24f)
                    finishPage(page)
                }

                return save(document)
            }
        }
    }

    /**
     * One page whose lines draw every character, spaces included, as a text object of its own,
     * the way PDFs made from web pages by wkhtmltopdf (Qt) do. 12 pt Helvetica.
     */
    fun characterByCharacter(vararg lines: Triple<String, Double, Double>): PdfSnapshot {
        synchronized(PdfiumLibrary.sync) {
            PdfDocument.createNew().use { document ->
                val page = assertNotNull(FPDFPage_New(document.handle, 0, 612.0, 792.0))
                for ((text, start, y) in lines) {
                    var x = start
                    for (c in text) {
                        val textObject = addStandardText(document.handle, page, c.toString(), x, y, 12f)
                        val width = FloatArray(1)
                        assertNotEquals(0, FPDFFont_GetGlyphWidth(FPDFTextObj_GetFont(textObject), c.code, 12f, width))
                        x += width[0]
                    }
                }

                finishPage(page)
                return save(document)
            }
        }
    }

    /**
     * The document as PDFium saves it, with the creation date and the file identifier it makes up
     * set to constants of the same length, so the same test document has the same bytes every
     * time (the conformance corpus stores them).
     */
    private fun save(document: PdfDocument): PdfSnapshot {
        val bytes = document.save().span
        val text = String(bytes, Charsets.ISO_8859_1)
        val id = text.lastIndexOf("/ID[<")
        var i = id + 5
        while (id >= 0 && i < bytes.size && bytes[i] != ']'.code.toByte()) {
            if ((bytes[i].toInt() and 0xFF).toChar() in "0123456789abcdefABCDEF") {
                bytes[i] = '0'.code.toByte()
            }

            i++
        }

        val date = text.indexOf("/CreationDate(D:")
        if (date >= 0 && text.length >= date + 30 && text.substring(date + 16, date + 30).all { it in '0'..'9' }) {
            "20240101000000".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, date + 16)
        }

        return PdfSnapshot.fromBytes(bytes)
    }

    private fun addStandardText(document: Pointer, page: Pointer, text: String, x: Double, y: Double, size: Float): Pointer {
        val textObject = assertNotNull(FPDFPageObj_NewTextObj(document, "Helvetica\u0000".toByteArray(Charsets.ISO_8859_1), size))
        setText(textObject, text)
        FPDFPageObj_Transform(textObject, 1.0, 0.0, 0.0, 1.0, x, y)
        FPDFPage_InsertObject(page, textObject)
        return textObject
    }

    private fun setText(textObject: Pointer, text: String) {
        assertNotEquals(0, FPDFText_SetText(textObject, PdfiumNative.wideString(text)))
    }

    private fun finishPage(page: Pointer) {
        assertNotEquals(0, FPDFPage_GenerateContent(page))
        FPDF_ClosePage(page)
    }
}
