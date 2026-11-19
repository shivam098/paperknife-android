package paperknife.core

import paperknife.core.content.ContentParser
import paperknife.core.content.PdfFontInfo
import paperknife.core.support.Ref
import paperknife.core.syntax.CosArray
import paperknife.core.syntax.CosDict
import paperknife.core.syntax.CosName
import paperknife.core.syntax.CosNumber
import paperknife.core.syntax.CosObject
import paperknife.core.syntax.CosRef
import paperknife.core.syntax.CosStream
import paperknife.core.syntax.IncrementalUpdate
import paperknife.core.syntax.PdfFile
import paperknife.core.syntax.PdfLexer
import paperknife.core.syntax.PdfObjectParser
import paperknife.core.syntax.StreamFilters
import paperknife.core.syntax.StreamUpdate
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** PaperKnife's own reading and writing of PDF syntax, used for content edits. */
class SyntaxTests {
    @Test
    fun lexerReadsStringsNamesNumbersAndReferences() {
        val lexer = PdfLexer(latin1("""(a\(b\)c\\d\101(e)) <48 65 6C 6C 6F> /A#20B -.5 12 0 R"""))

        assertEquals("""a(b)c\dA(e)""", text(lexer.next().bytes!!))
        assertEquals("Hello", text(lexer.next().bytes!!))
        assertEquals("A B", lexer.next().text)
        assertEquals(-0.5, lexer.next().number)
        val reference = assertIs<CosRef>(PdfObjectParser.read(lexer))
        assertEquals(12, reference.number)
    }

    @Test
    fun pdfFileReadsObjectStreamsAndCrossReferenceStreams() {
        val problem = Ref<String?>("not read")
        val file = PdfFile.tryOpen(RawPdf.build(listOf(listOf(RawPdf.BASE_CONTENT)), useObjectStreams = true), problem)

        assertNull(problem.value)
        assertTrue(file!!.hasXrefStream)
        val page = file.getPages()!!.single()
        val content = assertIs<CosStream>(file.getObject(file.getContentStreams(page.dict)!![0].number))
        assertEquals(RawPdf.BASE_CONTENT, text(file.decodeStream(content)!!))
    }

    @Test
    fun contentParserSkipsInlineImageData() {
        val ops = ContentParser.parse(latin1(RawPdf.INLINE_IMAGE + RawPdf.KERNED_TEXT))!!

        assertEquals(listOf("q", "cm", "BI", "Q", "BT", "Tf", "Td", "TJ", "ET"), ops.map { o -> o.operator })
    }

    @Test
    fun contentParserSkipsInlineImageDataWhoseSizeOverflows() {
        // 536870910 gray bytes a row overflow 32 bits; wrapped, the size of 42 rows pointed back at
        // the first EI, so reading went back to it and read the image again, forever.
        val content = latin1("EI BI /W 536870910 /H 42 /CS /G /BPC 8 ID x EI")

        val ops = withTimeout(10) { ContentParser.parse(content) }

        assertNotNull(ops)
        assertEquals(listOf("EI", "BI"), ops.map { o -> o.operator })
        assertEquals(content.size, ops[1].end)
    }

    @Test
    fun incrementalUpdateKeepsEveryOriginalByteAndReplacesTheStream() {
        val original = RawPdf.singlePage(RawPdf.BASE_CONTENT)
        val file = PdfFile.tryOpen(original)!!
        val content = file.getContentStreams(file.getPages()!![0].dict)!![0]

        val updated = IncrementalUpdate.append(file, listOf(StreamUpdate(content.number, content.generation, latin1("BT /F1 24 Tf 72 700 Td (Updated) Tj ET"))))

        assertTrue(updated.size >= original.size && updated.copyOf(original.size).contentEquals(original))
        val reread = PdfFile.tryOpen(updated)!!
        val stream = assertIs<CosStream>(reread.getObject(content.number))
        assertEquals("BT /F1 24 Tf 72 700 Td (Updated) Tj ET", text(reread.decodeStream(stream)!!))
        DocumentSession.fromSnapshot(PdfSnapshot.fromBytes(updated)).use { session ->
            assertTrue(session.getTextRuns(0).any { r -> r.text == "Updated" })
        }
    }

    @Test
    fun streamFiltersRefuseDataThatExpandsTooFar() {
        // Each two-byte RunLength run expands to 128 bytes; Flate shrinks the runs to almost nothing.
        val runs = ByteArray(2 * ((StreamFilters.MAX_DECODED_LENGTH / 128) + 1))
        for (i in runs.indices step 2) {
            runs[i] = 129.toByte()
            runs[i + 1] = 'A'.code.toByte()
        }

        val problem = Ref<String?>(null)
        val deflated = StreamFilters.deflate(runs)
        val decoded = StreamFilters.decode(deflated, 0, deflated.size, listOf("FlateDecode", "RunLengthDecode"), emptyList(), problem)

        assertNull(decoded)
        assertNotNull(problem.value)
    }

    @Test
    fun fontInfoGivesUpQuicklyOnWidthMapsTooLargeToRead() {
        val file = PdfFile.tryOpen(RawPdf.singlePage(RawPdf.BASE_CONTENT))!!
        val widths = ArrayList<CosObject>()
        repeat(100_000) {
            widths.addAll(listOf(number(0), number(131_000), number(500)))
        }

        val font = CosDict(
            linkedMapOf(
                "Subtype" to CosName("Type0"),
                "Encoding" to CosName("Identity-H"),
                "DescendantFonts" to CosArray(mutableListOf(CosDict(linkedMapOf("W" to CosArray(widths))))),
            ),
        )
        val start = System.nanoTime()

        val info = PdfFontInfo.load(file, font)

        assertTrue(System.nanoTime() - start < 5_000_000_000L)
        assertNull(info.width(65u))
    }

    @Test
    fun pdfFileRefusesObjectStreamsInsideObjectStreams() {
        // Object n lives in object stream n + 1, 100 000 levels deep: following the chain would
        // overflow the stack. Like PDFium, PaperKnife only reads object streams that are ordinary objects.
        val count = 100_000
        val rows = ByteArrayOutputStream()
        rows.write(byteArrayOf(0, 0, 0, 0, 0))
        for (n in 1..count) {
            rows.write(byteArrayOf(2, ((n + 1) shr 16).toByte(), ((n + 1) shr 8).toByte(), (n + 1).toByte(), 0))
        }

        val header = "%PDF-1.7\n"
        val xrefNumber = count + 2
        rows.write(byteArrayOf(1, 0, (header.length shr 8).toByte(), header.length.toByte(), 0))
        val data = StreamFilters.deflate(rows.toByteArray())
        val pdf = latin1(
            header + "$xrefNumber 0 obj\n<< /Type /XRef /Size ${xrefNumber + 1} /Index [0 ${count + 1} $xrefNumber 1] /W [1 3 1] " +
                "/Root 1 0 R /Filter /FlateDecode /Length ${data.size} >>\nstream\n",
        ) + data + latin1("\nendstream\nendobj\nstartxref\n${header.length}\n%%EOF\n")

        val problem = Ref<String?>("not read")
        val file = PdfFile.tryOpen(pdf, problem)

        assertNull(problem.value)
        assertNull(file!!.getPages())
    }

    private fun number(value: Int): CosNumber = CosNumber(value.toDouble(), value.toString())

    /**
     * What [action] gives, run on a thread of its own; fails when it takes more than [seconds].
     * The thread is a daemon: should the action loop forever, it can't keep the tests from ending.
     */
    private fun <T> withTimeout(seconds: Long, action: () -> T): T {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "timed test").apply { isDaemon = true } }
        try {
            return executor.submit(Callable { action() }).get(seconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            fail("It took more than $seconds s.")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            executor.shutdownNow()
        }
    }

    private fun latin1(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun text(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)
}
