package paperknife.core

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import paperknife.core.editing.TextEquivalence
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TextEditTests of PaperKnife for Windows that the corpus doesn't cover: the corpus compares the
 * files' bytes, and undo and redo must restore the very snapshots, which saving compares; and it
 * leaves out text with unpaired surrogates, which Swift strings can't hold.
 */
class TextEditTests {
    @Test
    fun undoAndRedoRestoreExactSnapshots() {
        openRaw(RawPdf.BASE_CONTENT).use { session ->
            val original = session.currentSnapshot
            session.replaceText(0, find(session, "Hello World").objectIndex, "Hello PaperKnife")
            val edited = session.currentSnapshot

            assertTrue(session.undo())
            assertSame(original, session.currentSnapshot)
            assertFalse(session.isModified)
            assertTrue(session.getTextRuns(0).any { r -> r.text == "Hello World" })

            assertTrue(session.redo())
            assertSame(edited, session.currentSnapshot)
            assertTrue(session.getTextRuns(0).any { r -> r.text == "Hello PaperKnife" })
            assertFalse(session.redo())
        }
    }

    /** `ReplaceText_WithTextThatIsNotValidUnicode_IsRejected`, a test for each of its rows. */
    @TestFactory
    fun replaceTextWithTextThatIsNotValidUnicodeIsRejected(): List<DynamicTest> =
        listOf(0xFFFE to true, 0xFFFE to false, 0xD800 to true, 0xD800 to false, 0xDC00 to true).map { (codeUnit, contentEditing) ->
            DynamicTest.dynamicTest(String.format(Locale.ROOT, "U+%04X, content editing %s", codeUnit, contentEditing)) {
                // .NET can't normalize U+FFFE or an unpaired surrogate: comparing such text threw, which
                // would have ended the app.
                openRaw(RawPdf.BASE_CONTENT, contentEditing).use { session ->
                    val character = String.format(Locale.ROOT, "U+%04X", codeUnit)

                    val result = session.replaceText(0, find(session, "Hello World").objectIndex, "Hello ${codeUnit.toChar()} again")

                    assertEquals(EditRejection.UnsupportedCharacters, result.rejection)
                    assertEquals("This text has characters that aren't valid Unicode: $character", result.message)
                    assertEquals(listOf(character), result.unsupportedCharacters)
                    assertFalse(session.isModified)
                }
            }
        }

    private fun openRaw(content: String, contentEditing: Boolean = true): DocumentSession {
        val session = DocumentSession.fromSnapshot(PdfSnapshot.fromBytes(RawPdf.singlePage(content)))
        session.useContentEditing = contentEditing
        return session
    }

    private fun find(session: DocumentSession, text: String): PdfTextRun =
        session.getTextRuns(0).single { r -> TextEquivalence.areEquivalent(r.text, text) }
}
