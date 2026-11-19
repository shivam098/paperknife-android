package paperknife.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** PermissionTests of PaperKnife for Windows that need only the document layer (the others need the session). */
class PermissionTest {
    @Test
    fun pdfToolsRefuseToCopyPagesFromADocumentThatForbidsCopying() {
        val restricted = CorpusDocuments.noChangesNoCopying()

        val extract = assertFailsWith<PdfException> { PdfTools.extractPages(restricted, listOf(0)) }
        val merge = assertFailsWith<PdfException> { PdfTools.merge(listOf(TestPdfs.numberedPages(1), restricted)) }

        assertEquals(PdfErrorCode.Restricted, extract.errorCode)
        assertEquals(PdfErrorCode.Restricted, merge.errorCode)
    }
}
