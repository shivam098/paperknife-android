package paperknife.core

import paperknife.core.editing.TextEquivalence
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ContentEditTests of PaperKnife for Windows that the corpus doesn't cover: the form of the
 * incremental update (the corpus reads the file, whatever form its cross-reference data takes),
 * and saving after undo and redo.
 */
class ContentEditTests {
    @Test
    fun replaceTextInFileWithObjectStreamsAppendsACrossReferenceStream() {
        open(PdfSnapshot.fromBytes(RawPdf.build(listOf(listOf(RawPdf.BASE_CONTENT)), useObjectStreams = true))).use { session ->
            val original = session.currentSnapshot

            val first = session.replaceText(0, find(session, "Hello World").objectIndex, "Hello PaperKnife")
            val second = session.replaceText(0, find(session, "This line must not move").objectIndex, "This line stays")

            assertEquals(EditStatus.Applied, first.status)
            assertEquals(EditStatus.Applied, second.status)
            assertAppended(original, session.currentSnapshot)
            val update = String(session.currentSnapshot.span, original.length, session.currentSnapshot.length - original.length, Charsets.ISO_8859_1)
            assertTrue(update.contains("/Type /XRef"))
            assertFalse(update.contains("\nxref\n"))
            DocumentSession.fromSnapshot(session.currentSnapshot).use { reopened ->
                assertTrue(reopened.getTextRuns(0).any { r -> r.text == "Hello PaperKnife" })
                assertTrue(reopened.getTextRuns(0).any { r -> r.text == "This line stays" })
            }
        }
    }

    @Test
    fun undoAndSaveAfterContentEditRestoreAndKeepExactBytes() {
        open(PdfSnapshot.fromBytes(RawPdf.singlePage(RawPdf.BASE_CONTENT + RawPdf.TYPE3_TEXT))).use { session ->
            val original = session.currentSnapshot
            session.replaceText(0, find(session, "abc").objectIndex, "cab")
            val edited = session.currentSnapshot

            assertTrue(session.undo())
            assertSame(original, session.currentSnapshot)
            assertTrue(session.getTextRuns(0).any { r -> r.text == "abc" })
            assertTrue(session.redo())

            val folder = TestFolders.create("paperknife-tests-")
            val path = File(folder, "paperknife-${UUID.randomUUID().toString().replace("-", "")}.pdf")
            try {
                session.saveAs(path.path)
                assertContentEquals(edited.span, path.readBytes())
            } finally {
                folder.deleteRecursively()
            }
        }
    }

    private fun open(snapshot: PdfSnapshot, contentEditing: Boolean = true): DocumentSession {
        val session = DocumentSession.fromSnapshot(snapshot)
        session.useContentEditing = contentEditing
        return session
    }

    /** The edit was appended: every byte of the original file is still there, unchanged. */
    private fun assertAppended(original: PdfSnapshot, edited: PdfSnapshot) {
        assertTrue(edited.length > original.length)
        assertContentEquals(original.span, edited.span.copyOf(original.length))
    }

    private fun find(session: DocumentSession, text: String): PdfTextRun =
        session.getTextRuns(0).single { r -> TextEquivalence.areEquivalent(r.text, text) }
}
