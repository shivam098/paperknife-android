package paperknife.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Where the document layer's records must be equal as the C# records are, unlike Kotlin's data classes. */
class RecordEqualityTest {
    @Test
    fun floatsCompareAsInCSharpRecords() {
        // C#'s records compare floats with float.Equals: 0 equals -0, and NaN equals NaN.
        val rect = PdfRect(0f, 0f, 10f, Float.NaN)
        val negativeZero = PdfRect(-0f, 0f, 10f, Float.NaN)
        val run = PdfTextRun(0, 1, 1, "A", rect, "Helvetica", false, false, 0f, false)

        assertEquals(rect, negativeZero)
        assertEquals(rect.hashCode(), negativeZero.hashCode())
        assertEquals(PageSize(612f, -0f), PageSize(612f, 0f))
        assertEquals(run, run.copy(bounds = negativeZero, fontSize = -0f))
        assertEquals(run.hashCode(), run.copy(bounds = negativeZero, fontSize = -0f).hashCode())
        assertNotEquals(run, run.copy(text = "B"))
    }
}
