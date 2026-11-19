package paperknife.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PdfToolsTest {
    @Test
    fun mergeCombinesDocumentsInOrder() {
        val merged = PdfTools.merge(listOf(TestPdfs.numberedPages(2), TestPdfs.numberedPages(1)))

        assertEquals(listOf("Page 1", "Page 2", "Page 1"), pageTexts(merged))
    }

    @Test
    fun extractPagesCopiesSelectedPagesInTheRequestedOrder() {
        val extracted = PdfTools.extractPages(TestPdfs.numberedPages(3), listOf(2, 0))

        assertEquals(listOf("Page 3", "Page 1"), pageTexts(extracted))
    }

    @Test
    fun extractPagesRejectsPagesThatDoNotExist() {
        assertFailsWith<IndexOutOfBoundsException> { PdfTools.extractPages(TestPdfs.numberedPages(1), listOf(1)) }
    }

    /** Not a test of PaperKnife for Windows: the documents the tests build are those the corpus stores, byte for byte. */
    @Test
    fun testDocumentsAreTheCorpusDocuments() {
        assertContentEquals(CorpusDocuments.read("documents/32a4377b16a1.pdf").span, TestPdfs.numberedPages(1).span)
        assertContentEquals(CorpusDocuments.read("documents/55142f50dbbc.pdf").span, TestPdfs.numberedPages(2).span)
        assertContentEquals(CorpusDocuments.read("documents/e6d8688f7a08.pdf").span, TestPdfs.numberedPages(3).span)
    }

    private fun pageTexts(snapshot: PdfSnapshot): List<String> =
        DocumentSession.fromSnapshot(snapshot).use { session -> (0 until session.pageCount).map { session.getTextRuns(it)[0].text } }
}
