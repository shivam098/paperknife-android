package paperknife.core

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import paperknife.core.support.DotNetMath
import java.io.File
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The document layer's part of the conformance corpus: every case's document, opened as the
 * session opens it, has the initial state's page count, page sizes and text runs, field by
 * field and in order. The session's other observations (undo state, blockers, content, fonts)
 * and the steps need the layers above. Values are taken as CorpusRecorder in PaperKnife for
 * Windows takes them: `GetPageSize` is the page's width and height, and `GetTextRuns` the page's
 * runs; sizes and bounds are rounded to 0.01, half away from zero, and compared within 0.011.
 */
class PdfiumCorpusTest {
    @TestFactory
    fun initialStatesMatchTheCorpus(): List<DynamicTest> {
        val cases = File(CorpusDocuments.folder, "cases").listFiles { file -> file.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue(cases.size >= 125, "The corpus has ${cases.size} cases.")
        return cases.map { file -> DynamicTest.dynamicTest(file.nameWithoutExtension) { check(file) } }
    }

    private fun check(file: File) {
        val case = Json.parse(file.readText(Charsets.UTF_8)).obj
        val id = case.str("id")
        val initial = case.obj("initial")
        val problems = ArrayList<String>()
        PdfDocument.load(CorpusDocuments.read(case.str("document")), case["password"] as String?).use { document ->
            val pageCount = document.pageCount
            if (pageCount != initial.int("pageCount")) {
                problems.add("pageCount is $pageCount, not ${initial.int("pageCount")}")
            }

            val pages = initial.list("pages")
            for (index in 0 until minOf(pageCount, pages.size)) {
                val expected = pages[index].obj
                val page = document.getPage(index)
                val where = "page ${index + 1}"
                val size = PageSize(page.width, page.height)
                compareNumber(problems, "$where width", expected.num("width"), size.width)
                compareNumber(problems, "$where height", expected.num("height"), size.height)

                val runs = page.getTextRuns()
                val expectedRuns = expected.list("runs")
                if (runs.size != expectedRuns.size) {
                    problems.add("$where has ${runs.size} runs, not ${expectedRuns.size}: ${runs.map { it.text }}")
                }

                for (r in 0 until minOf(runs.size, expectedRuns.size)) {
                    compareRun(problems, "$where run ${r + 1}", expectedRuns[r].obj, runs[r])
                }
            }
        }

        if (problems.isNotEmpty()) {
            fail("$id:\n" + problems.joinToString("\n"))
        }
    }

    private fun compareRun(problems: MutableList<String>, where: String, expected: Map<String, Any?>, run: PdfTextRun) {
        fun same(field: String, wanted: Any?, actual: Any?) {
            if (wanted != actual) {
                problems.add("$where $field is ${quote(actual)}, not ${quote(wanted)}")
            }
        }

        same("text", expected.str("text"), run.text)
        same("object", expected.int("object"), run.objectIndex)
        same("objects", expected.int("objects"), run.objectCount)
        same("font", expected.str("font"), run.fontName)
        compareNumber(problems, "$where size", expected.num("size"), run.fontSize)
        same("embedded", expected["embedded"], run.isFontEmbedded)
        same("subset", expected["subset"], run.isFontSubset)
        same("invisible", expected["invisible"], run.isInvisible)
        val bounds = expected.list("bounds").map { (it as Double) }
        val actual = listOf(run.bounds.left, run.bounds.bottom, run.bounds.right, run.bounds.top)
        val names = listOf("left", "bottom", "right", "top")
        for (i in 0 until 4) {
            compareNumber(problems, "$where bounds ${names[i]}", bounds[i], actual[i])
        }
    }

    private fun compareNumber(problems: MutableList<String>, what: String, expected: Double, actual: Float) {
        val rounded = DotNetMath.round(actual.toDouble(), 2, awayFromZero = true)
        if (!(abs(rounded - expected) <= TOLERANCE)) {
            problems.add("$what is $rounded ($actual), not $expected")
        }
    }

    private fun quote(value: Any?): String = if (value is String) "“$value”" else value.toString()

    private val Any?.obj: Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        get() = this as Map<String, Any?>

    private fun Map<String, Any?>.obj(key: String): Map<String, Any?> = this[key].obj

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as List<Any?>

    private fun Map<String, Any?>.str(key: String): String = this[key] as String

    private fun Map<String, Any?>.num(key: String): Double = this[key] as Double

    private fun Map<String, Any?>.int(key: String): Int = num(key).toInt()

    /** A JSON reader for the corpus: objects keep their order; numbers are doubles. */
    private class Json private constructor(private val text: String) {
        private var position = 0

        private fun value(): Any? {
            skipWhiteSpace()
            return when (val c = peek()) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') numberValue() else error("Unexpected “$c”")
            }
        }

        private fun objectValue(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            expect('{')
            skipWhiteSpace()
            if (peek() == '}') {
                position++
                return result
            }

            while (true) {
                skipWhiteSpace()
                val key = stringValue()
                skipWhiteSpace()
                expect(':')
                result[key] = value()
                skipWhiteSpace()
                if (peek() == ',') {
                    position++
                    continue
                }

                expect('}')
                return result
            }
        }

        private fun arrayValue(): List<Any?> {
            val result = ArrayList<Any?>()
            expect('[')
            skipWhiteSpace()
            if (peek() == ']') {
                position++
                return result
            }

            while (true) {
                result.add(value())
                skipWhiteSpace()
                if (peek() == ',') {
                    position++
                    continue
                }

                expect(']')
                return result
            }
        }

        private fun stringValue(): String {
            expect('"')
            val result = StringBuilder()
            while (true) {
                val c = text[position++]
                when (c) {
                    '"' -> return result.toString()
                    '\\' -> {
                        when (val escape = text[position++]) {
                            '"', '\\', '/' -> result.append(escape)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                result.append(text.substring(position, position + 4).toInt(16).toChar())
                                position += 4
                            }
                            else -> error("Unknown escape “\\$escape”")
                        }
                    }
                    else -> result.append(c)
                }
            }
        }

        private fun numberValue(): Double {
            val start = position
            while (position < text.length && (text[position] in "+-.eE" || text[position] in '0'..'9')) {
                position++
            }

            return text.substring(start, position).toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, position)) {
                error("Expected “$word”")
            }

            position += word.length
            return value
        }

        private fun expect(c: Char) {
            if (peek() != c) {
                error("Expected “$c”")
            }

            position++
        }

        private fun peek(): Char = if (position < text.length) text[position] else error("Unexpected end")

        private fun skipWhiteSpace() {
            while (position < text.length && text[position] in " \t\r\n") {
                position++
            }
        }

        private fun error(message: String): Nothing = throw IllegalArgumentException("$message at $position of the JSON.")

        companion object {
            fun parse(text: String): Any? {
                val json = Json(text)
                val value = json.value()
                json.skipWhiteSpace()
                if (json.position != text.length) {
                    json.error("Unexpected text")
                }

                return value
            }
        }
    }

    private companion object {
        const val TOLERANCE = 0.011
    }
}
