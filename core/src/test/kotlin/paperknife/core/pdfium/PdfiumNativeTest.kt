package paperknife.core.pdfium

import com.sun.jna.NativeLong
import paperknife.core.CorpusDocuments
import paperknife.core.PdfDocument
import paperknife.core.pdfium.PdfiumNative.FPDFFont_GetFontData
import paperknife.core.pdfium.PdfiumNative.FPDFText_ClosePage
import paperknife.core.pdfium.PdfiumNative.FPDFText_LoadPage
import paperknife.core.pdfium.PdfiumNative.FPDFTextObj_GetFont
import paperknife.core.pdfium.PdfiumNative.FPDFTextObj_GetText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/** The C types the document layer's own calls don't exercise: `size_t*`, an `unsigned long` result and wide strings. */
class PdfiumNativeTest {
    @Test
    fun wideStringsKeepEveryCodeUnit() {
        // FPDF_WIDESTRING: UTF-16LE with a two-byte terminator; an unpaired surrogate stays as it is, as in C#.
        assertContentEquals(byteArrayOf(0x41, 0, 0x00, 0xD8.toByte(), 0xAC.toByte(), 0x20, 0, 0), PdfiumNative.wideString("A\uD800\u20AC"))
    }

    @Test
    fun fontDataAndObjectTextAreRead() {
        // The document of the tex-new-letters-cmr10 case: "Hello world" in an embedded Type 1 font, CMR10.
        PdfDocument.load(CorpusDocuments.read("documents/0f4c2884386f.pdf")).use { document ->
            synchronized(PdfiumLibrary.sync) {
                val page = document.getPage(0)
                val text = assertNotNull(page.getTextObject(0))
                val font = FPDFTextObj_GetFont(text)

                // The font program, as DocumentSession reads it (its decoded length is in the corpus).
                val length = SizeTByReference()
                assertNotEquals(0, FPDFFont_GetFontData(font, null, SizeT(0), length))
                assertEquals(16309L, length.value)
                val data = ByteArray(length.value.toInt())
                assertNotEquals(0, FPDFFont_GetFontData(font, data, SizeT(length.value), length))
                assertEquals("%!", String(data, 0, 2, Charsets.ISO_8859_1))

                // The text object's text: UTF-16LE with a terminator, measured in bytes.
                val textPage = FPDFText_LoadPage(page.handle)
                try {
                    val bytes = FPDFTextObj_GetText(text, textPage, null, NativeLong(0)).toLong()
                    val buffer = ByteArray(bytes.toInt())
                    assertEquals(bytes, FPDFTextObj_GetText(text, textPage, buffer, NativeLong(bytes)).toLong())
                    assertEquals("Hello world\u0000", String(buffer, Charsets.UTF_16LE))
                } finally {
                    FPDFText_ClosePage(textPage)
                }
            }
        }
    }
}
