package paperknife.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PageOperationTests of PaperKnife for Windows that the corpus doesn't cover: saving. The page
 * commands are the corpus's pages- cases.
 */
class PageOperationTests {
    @Test
    fun saveAsWritesTheVerifiedSnapshotAndLeavesNoTemporaryFiles() {
        val folder = TestFolders.create("paperknife-tests-")
        try {
            DocumentSession.fromSnapshot(TestPdfs.numberedPages(2)).use { session ->
                session.rotatePage(1, 1)
                val path = File(folder, "out.pdf").absolutePath

                session.saveAs(path)

                assertFalse(session.isModified)
                assertEquals(path, session.filePath)
                assertContentEquals(session.currentSnapshot.span, File(path).readBytes())
                assertEquals(listOf("out.pdf"), folder.listFiles()!!.map { f -> f.name })
                DocumentSession.open(path).use { reopened ->
                    assertEquals(PageSize(792f, 612f), reopened.getPageSize(1))
                }
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    /** Not a test of PaperKnife for Windows: `saveTo` is PaperKnife for Android's. */
    @Test
    fun saveToWritesTheCurrentSnapshotAndRecordsItAsSavedOnlyWhenTheStreamTakesIt() {
        DocumentSession.fromSnapshot(TestPdfs.numberedPages(2)).use { session ->
            session.rotatePage(1, 1)

            // A stream that fails to write or to flush leaves the document modified.
            val failing = listOf(
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("The disk is full.")
                },
                object : OutputStream() {
                    override fun write(b: Int) {}

                    override fun flush() = throw IOException("The disk is full.")
                },
            )
            for (stream in failing) {
                assertFailsWith<IOException> { session.saveTo(stream) }
                assertTrue(session.isModified)
            }

            val output = ByteArrayOutputStream()
            session.saveTo(output)

            assertContentEquals(session.currentSnapshot.span, output.toByteArray())
            assertFalse(session.isModified)
            assertNull(session.filePath)
        }
    }
}
