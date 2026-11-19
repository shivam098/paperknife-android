package paperknife.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolchainTest {
    @Test
    fun pdfiumOpensADocument() {
        val pdf = """
            %PDF-1.4
            1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
            2 0 obj << /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >> endobj
            3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >> endobj
            4 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >> endobj
            trailer << /Root 1 0 R >>
            %%EOF
        """.trimIndent()

        assertEquals(2, Toolchain.pdfiumPageCount(pdf.toByteArray(Charsets.ISO_8859_1)))
        assertNull(Toolchain.pdfiumPageCount("not a PDF".toByteArray(Charsets.ISO_8859_1)))
    }
}
