package paperknife.core.conformance

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import paperknife.core.TestFiles
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The conformance corpus (conformance/README.md) on the JVM, a test per case: a port of
 * `ConformanceTests.StoredCase_GivesTheStoredObservations` in PaperKnife for Windows. The cases run
 * through [ConformanceRunner], which the app's instrumented test runs them through on Android.
 */
class ConformanceTests {
    private val folder = File(TestFiles.root, "conformance")
    private val runner = ConformanceRunner(CorpusFiles.fromDirectory(folder))

    @TestFactory
    fun storedCasesGiveTheStoredObservations(): List<DynamicTest> {
        TestFiles.useBundledFonts()
        return runner.caseIds().map { id -> DynamicTest.dynamicTest(id) { check(runner.run(id)) } }
    }

    @Test
    fun theCorpusHasTheCasesItsIndexLists() {
        val listed = runner.indexedCaseIds()
        assertTrue(listed.isNotEmpty(), "INDEX.md lists no cases.")
        assertEquals(listed.sorted(), runner.caseIds())
    }

    /** Not a test of PaperKnife for Windows: the runner compares what it should, and says where a case differs. */
    @Test
    fun aCaseThatDiffersFailsSayingWhere() {
        TestFiles.useBundledFonts()
        val stored = File(folder, "cases/edit-basic-undo-redo.json").readText(Charsets.UTF_8)
        val changed = stored
            .replaceFirst("\"canRedo\": false", "\"canRedo\": true")
            .replaceFirst("\"bounds\": [73.92, 699.74, 194.28, 717.18]", "\"bounds\": [83.92, 699.74, 194.28, 717.18]")
            .replaceFirst("\"message\": \"Text updated. Verified: nothing else on this page changed.\"", "\"message\": \"Text updated.\"")
            .replaceFirst("\"sameAs\": 0", "\"sameAs\": null")

        val outcome = runner.runJson(changed)

        // The bounds of Helvetica, which isn't embedded, follow the font PDFium finds on the computer.
        assertNull(outcome.error)
        val lines = outcome.message.lines()
        assertEquals("edit-basic-undo-redo:", lines[0])
        assertEquals("$.initial.canRedo is false, not true", lines[1])
        assertTrue(lines[2].startsWith("$.initial.pages[0].runs[0].bounds[0] is 73.") && lines[2].endsWith(", not 83.92"), lines[2])
        assertEquals(
            "step 1 (replaceText “Hello World” with “Hello PaperKnife” on page 1): $.steps[0].expect.result.message is " +
                "“Text updated. Verified: nothing else on this page changed.”, not “Text updated.”",
            lines[3],
        )
        assertEquals("step 2 (undo): $.steps[1].expect.state.sameAs is 0, not null", lines[4])
        assertEquals(5, lines.size, outcome.message)
    }

    /**
     * Not a test of PaperKnife for Windows: content that may differ off Windows must still have the
     * corpus's tokens, numbers within 0.0001. The content is runs-layout-as-saved-pdfium-rewrite's
     * after its step, which places the third “A” at 96.012.
     */
    @Test
    fun contentThatMayDifferMustHaveTheSameTokens() {
        val expected = File(folder, "contents/78ada6cb56c6.content").readBytes()
        fun changed(from: String, to: String): ByteArray {
            val text = String(expected, Charsets.ISO_8859_1)
            assertTrue(text.contains(from), from)
            return text.replaceFirst(from, to).toByteArray(Charsets.ISO_8859_1)
        }

        assertNull(ConformanceRunner.contentTokenDifference(expected, expected))
        assertNull(ConformanceRunner.contentTokenDifference(changed(" 96.012 ", " 96.01201 "), expected))
        assertEquals(
            "token 88, at byte 227, is “96.0102”, not “96.012”",
            ConformanceRunner.contentTokenDifference(changed(" 96.012 ", " 96.0102 "), expected),
        )
        assertEquals(
            "token 103, at byte 277, is “q”, not the end",
            ConformanceRunner.contentTokenDifference(expected + " q".toByteArray(Charsets.ISO_8859_1), expected),
        )
        assertEquals(
            "token 62, at byte 161, is “Td”, not “Tm”",
            ConformanceRunner.contentTokenDifference(changed(" 700 Tm /FXF1 12 Tf 0 Tr [<41>]", " 700 Td /FXF1 12 Tf 0 Tr [<41>]"), expected),
        )
    }

    /**
     * Not a test of PaperKnife for Windows: off Windows, a page's content whose hash differs from
     * the corpus's is compared by its tokens, here runs-layout-as-saved-pdfium-rewrite's after its
     * step, against content kept under another hash whose third “A” is at 96.01201 (as on Ubuntu,
     * where the step places it there) or 96.0102.
     */
    @Test
    fun offWindowsContentThatDiffersIsComparedByItsTokens() {
        TestFiles.useBundledFonts()
        val hash = "78ada6cb56c6c9b07e5d6f864a8c4a2bd2c38b17a603072fb41f57572c4e4622"
        val other = "0".repeat(64)
        val stored = File(folder, "cases/runs-layout-as-saved-pdfium-rewrite.json").readText(Charsets.UTF_8)
        val json = stored.replace("\"content\": \"$hash\"", "\"content\": \"$other\"")
        assertNotEquals(stored, json)
        val offWindows = ConformanceRunner(CorpusFiles.fromDirectory(folder), onWindows = false)
        val kept = String(offWindows.keptContent(hash)!!, Charsets.ISO_8859_1)
        fun runWith(x: String): CaseOutcome {
            val content = kept.replace(" 96.012 ", " $x ").toByteArray(Charsets.ISO_8859_1)
            return offWindows.runJson(json) { h -> if (h == other) content else offWindows.keptContent(h) }
        }

        check(runWith("96.01201"))
        val outcome = runWith("96.0102")

        assertNull(outcome.error)
        val lines = outcome.message.lines()
        assertEquals(2, lines.size, outcome.message)
        val step = "step 1 (replaceText “AAA” with “ABAA” on page 1, without content editing): $.steps[0].expect.state.pages[0].content is “"
        assertTrue(lines[1].startsWith(step), lines[1])
        assertTrue(lines[1].contains("”, not “$other”: token 88, at byte 227, is “96.01") && lines[1].endsWith("”, not “96.0102”"), lines[1])
    }

    /** Fails with the case's differences and the exception it threw, if any. */
    private fun check(outcome: CaseOutcome) {
        val error = outcome.error
        if (error != null && outcome.problems.isEmpty()) {
            throw error
        }

        if (!outcome.passed) {
            fail(outcome.message, error)
        }
    }
}
