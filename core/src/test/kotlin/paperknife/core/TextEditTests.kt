package paperknife.core

import paperknife.core.editing.TextEquivalence
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TextEditTests of PaperKnife for Windows that the corpus doesn't cover: the corpus compares the
 * files' bytes, and undo and redo must restore the very snapshots, which saving compares.
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

    private fun openRaw(content: String, contentEditing: Boolean = true): DocumentSession {
        val session = DocumentSession.fromSnapshot(PdfSnapshot.fromBytes(RawPdf.singlePage(content)))
        session.useContentEditing = contentEditing
        return session
    }

    private fun find(session: DocumentSession, text: String): PdfTextRun =
        session.getTextRuns(0).single { r -> TextEquivalence.areEquivalent(r.text, text) }
}
