package paperknife.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DocumentTests of PaperKnife for Windows that the corpus doesn't cover, on the document and page the session uses. */
class DocumentTest {
    @Test
    fun loadRejectsDataThatIsNotPdf() {
        val snapshot = PdfSnapshot.fromBytes("definitely not a pdf".toByteArray(Charsets.UTF_8))

        val error = assertFailsWith<PdfException> { PdfDocument.load(snapshot) }

        assertEquals(PdfErrorCode.InvalidFormat, error.errorCode)
    }

    @Test
    fun renderPageDrawsContentOnWhite() {
        PdfDocument.load(CorpusDocuments.baseContent()).use { document ->
            val bitmap = document.getPage(0).render(612, 792)

            val (r, g, b) = pixel(bitmap, 172, 792 - 550)
            assertTrue(r in 210..225, "red is $r")
            assertTrue(g in 35..47, "green is $g")
            assertTrue(b in 35..47, "blue is $b")
            assertEquals(Triple(255, 255, 255), pixel(bitmap, 500, 100))
        }
    }

    @Test
    fun hitTestTextFindsTheRunUnderAPixel() {
        PdfDocument.load(CorpusDocuments.baseContent()).use { document ->
            val page = document.getPage(0)

            val hit = page.hitTestText(612, 792, 100, 792 - 708)

            assertEquals("Hello World", hit?.text)
            assertTrue(page.mapToPixels(hit!!.bounds, 612, 792).contains(100, 792 - 708))
            assertNull(page.hitTestText(612, 792, 500, 50))
        }
    }

    private fun pixel(bitmap: RenderedBitmap, x: Int, y: Int): Triple<Int, Int, Int> {
        val i = (y * bitmap.stride) + (x * 4)
        return Triple(bitmap.pixels[i + 2].toInt() and 0xFF, bitmap.pixels[i + 1].toInt() and 0xFF, bitmap.pixels[i].toInt() and 0xFF)
    }
}
