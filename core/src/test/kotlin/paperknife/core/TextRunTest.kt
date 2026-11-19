package paperknife.core

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import paperknife.core.editing.TextRunEdit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Text drawn one character per text object, as in PDFs made from web pages by wkhtmltopdf. */
class TextRunTest {
    @Test
    fun textRunsJoinCharactersDrawnOneByOneIntoLinesAndCells() {
        PdfDocument.load(openInvoice()).use { document ->
            val page = document.getPage(0)

            val runs = page.getTextRuns()
            val hit = page.hitTestText(612, 792, 80, 792 - 704)

            assertEquals(listOf("Invoice 2024", "$1,250.00", "Total due"), runs.map { it.text })
            runs.forEach { assertEquals(it.text.length, it.objectCount) } // spaces are objects too
            assertEquals("Invoice 2024", hit?.text)
        }
    }

    @TestFactory
    fun planChangesTheFewestObjects(): List<DynamicTest> = listOf(
        PlanCase("₹| |1|1|6|8|8", "", "₹ 12688", 3, 3, "2"),
        PlanCase("I|n|v|o|i|c|e| |2|0|2|4", "", "Invoice No. 2024", 7, 7, " No. "), // typing after a space joins the space
        PlanCase("T|o|t|a|l| |d|u|e", "", "Total", 5, 8, ""), // the word and the space before it go
        PlanCase("Single|Pvt|room", " | ", "Single room", 1, 2, "room"), // one object per word, gaps between
        PlanCase("t|r|u|s|t|e|d| ", "", "trusty", 5, 6, "y"), // trailing whitespace stays as it is
    ).map { case ->
        DynamicTest.dynamicTest("${case.pieces} -> ${case.newText}") {
            val texts = case.pieces.split('|')
            val gaps = if (case.separators.isNotEmpty()) case.separators.split('|') else List(texts.size - 1) { "" }

            val edit = TextRunEdit.plan(texts, gaps, case.newText)

            assertEquals(TextRunEdit(case.first, case.last, case.replacement), edit)
        }
    }

    private class PlanCase(val pieces: String, val separators: String, val newText: String, val first: Int, val last: Int, val replacement: String)

    private fun openInvoice(): PdfSnapshot = TestPdfs.characterByCharacter(
        Triple("Invoice 2024", 72.0, 700.0),
        Triple("$1,250.00", 400.0, 700.0),
        Triple("Total due", 72.0, 650.0),
    )
}
