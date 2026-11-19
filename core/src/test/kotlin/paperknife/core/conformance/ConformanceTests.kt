package paperknife.core.conformance

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import paperknife.core.DocumentSession
import paperknife.core.EditResult
import paperknife.core.PdfSnapshot
import paperknife.core.PdfTextRun
import paperknife.core.TestFiles
import paperknife.core.content.ContentPage
import paperknife.core.support.DotNetMath
import paperknife.core.syntax.CosDict
import paperknife.core.syntax.CosName
import paperknife.core.syntax.CosNumber
import paperknife.core.syntax.CosObject
import paperknife.core.syntax.CosStream
import paperknife.core.syntax.PdfFile
import paperknife.core.syntax.PdfPageNode
import java.io.File
import java.security.MessageDigest
import java.util.Arrays
import java.util.HexFormat
import kotlin.math.abs
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The conformance corpus (conformance/README.md): every stored case, run from its stored
 * documents and steps, must give the stored observations. A port of
 * `ConformanceTests.StoredCase_GivesTheStoredObservations` in PaperKnife for Windows, which
 * observes the session as its `CorpusRecorder` does: the same calls, in the same order.
 */
class ConformanceTests {
    @TestFactory
    fun storedCasesGiveTheStoredObservations(): List<DynamicTest> {
        TestFiles.useBundledFonts()
        return caseFiles().map { file -> DynamicTest.dynamicTest(file.nameWithoutExtension) { CaseRun(file.readText(Charsets.UTF_8)).check() } }
    }

    @Test
    fun theCorpusHasTheCasesItsIndexLists() {
        val listed = Regex("""\(cases/([a-z0-9-]+)\.json\)""").findAll(File(corpus, "INDEX.md").readText(Charsets.UTF_8))
            .map { match -> match.groupValues[1] }.toList()
        assertTrue(listed.isNotEmpty(), "INDEX.md lists no cases.")
        assertEquals(listed.sorted(), caseFiles().map { file -> file.nameWithoutExtension })
    }

    /** Not a test of PaperKnife for Windows: the runner compares what it should, and says where a case differs. */
    @Test
    fun aCaseThatDiffersFailsSayingWhere() {
        TestFiles.useBundledFonts()
        val stored = File(corpus, "cases/edit-basic-undo-redo.json").readText(Charsets.UTF_8)
        val changed = stored
            .replaceFirst("\"canRedo\": false", "\"canRedo\": true")
            .replaceFirst("\"bounds\": [73.92, 699.74, 194.28, 717.18]", "\"bounds\": [83.92, 699.74, 194.28, 717.18]")
            .replaceFirst("\"message\": \"Text updated. Verified: nothing else on this page changed.\"", "\"message\": \"Text updated.\"")
            .replaceFirst("\"sameAs\": 0", "\"sameAs\": null")

        val error = assertFailsWith<AssertionError> { CaseRun(changed).check() }

        // The bounds of Helvetica, which isn't embedded, follow the font PDFium finds on the computer.
        val lines = error.message!!.lines()
        assertEquals("edit-basic-undo-redo:", lines[0])
        assertEquals("$.initial.canRedo is false, not true", lines[1])
        assertTrue(lines[2].startsWith("$.initial.pages[0].runs[0].bounds[0] is 73.") && lines[2].endsWith(", not 83.92"), lines[2])
        assertEquals(
            "step 1 (replaceText “Hello World” with “Hello PaperKnife” on page 1): $.steps[0].expect.result.message is " +
                "“Text updated. Verified: nothing else on this page changed.”, not “Text updated.”",
            lines[3],
        )
        assertEquals("step 2 (undo): $.steps[1].expect.state.sameAs is 0, not null", lines[4])
        assertEquals(5, lines.size, error.message)
    }

    private fun caseFiles(): List<File> =
        File(corpus, "cases").listFiles().orEmpty().filter { file -> file.name.endsWith(".json") }.sortedBy { file -> file.nameWithoutExtension }

    /** One case: its session, the files of its earlier states, and what differs from the corpus. */
    private class CaseRun(json: String) {
        private val stored = MiniJson.parse(json) as Map<*, *>
        private val id = stored["id"] as String
        private val problems = ArrayList<String>()

        /** The files of the states so far: the document as opened, then the state after each step. */
        private val history = ArrayList<ByteArray>()

        /** The decoded page contents observed, by SHA-256, to say where they differ from those the corpus keeps. */
        private val contents = HashMap<String, ByteArray>()

        fun check() {
            assertEquals(FORMAT, stored["format"], "$id: the corpus format")
            val attachments = LinkedHashMap<String, PdfSnapshot>()
            for ((name, path) in (stored["attachments"] as Map<*, *>?).orEmpty()) {
                attachments[name as String] = load(path as String)
            }

            val document = load(stored["document"] as String)
            DocumentSession.fromSnapshot(document, stored["password"] as String?).use { session ->
                history.add(document.span)
                compare(stored["initial"], state(session, 0), "$.initial", "")
                for ((i, item) in (stored["steps"] as List<*>).withIndex()) {
                    val step = item as Map<*, *>
                    val where = "step ${i + 1} (${describe(step)})"

                    // CorpusRecorder.Describe finds the run before the step is timed, and Perform again.
                    if (step["action"] == "replaceText") {
                        findRun(session, step, where)
                    }

                    val started = System.nanoTime()
                    val result = perform(session, step, attachments, where)
                    val milliseconds = (System.nanoTime() - started) / 1_000_000
                    val limit = step["maxMilliseconds"] as Double?
                    if (limit != null && milliseconds > limit) {
                        problems.add("$where took $milliseconds ms, more than ${limit.toLong()} ms")
                    }

                    val expect = step["expect"] as Map<*, *>
                    compare(expect["result"], result, "$.steps[$i].expect.result", where)
                    compare(expect["state"], state(session, i + 1), "$.steps[$i].expect.state", where)
                }
            }

            if (problems.isNotEmpty()) {
                val shown = problems.take(MAX_PROBLEMS) + if (problems.size > MAX_PROBLEMS) listOf("… and ${problems.size - MAX_PROBLEMS} more") else emptyList()
                fail("$id:\n" + shown.joinToString("\n"))
            }
        }

        /** A document of the case, which the corpus names by the first 12 hex digits of its SHA-256 (CorpusRecorder.Keep). */
        private fun load(path: String): PdfSnapshot {
            val bytes = File(corpus, path).readBytes()
            val name = "documents/${sha256(bytes).substring(0, 12)}.pdf"
            if (name != path) {
                problems.add("$path has the bytes of $name")
            }

            return PdfSnapshot.fromBytes(bytes)
        }

        private fun perform(session: DocumentSession, step: Map<*, *>, attachments: Map<String, PdfSnapshot>, where: String): Map<String, Any?> =
            when (val action = step["action"]) {
                "replaceText" -> {
                    session.useContentEditing = step["contentEditing"] as Boolean? ?: true
                    try {
                        result(session.replaceText(int(step, "page"), findRun(session, step, where).objectIndex, step["text"] as String))
                    } finally {
                        session.useContentEditing = true
                    }
                }
                "replaceObject" -> result(session.replaceText(int(step, "page"), int(step, "object"), step["text"] as String))
                "rotatePage" -> result(session.rotatePage(int(step, "page"), int(step, "quarterTurns")))
                "deletePage" -> result(session.deletePage(int(step, "page")))
                "movePage" -> result(session.movePage(int(step, "from"), int(step, "to")))
                "appendDocument" -> result(session.appendDocument(attachments.getValue(step["document"] as String), step["password"] as String?))
                "undo" -> mapOf("done" to session.undo())
                "redo" -> mapOf("done" to session.redo())
                else -> fail("$id: $where has the unknown action $action")
            }

        /** The one run on the step's page whose text is exactly the step's `run`. */
        private fun findRun(session: DocumentSession, step: Map<*, *>, where: String): PdfTextRun {
            val page = int(step, "page")
            val runs = session.getTextRuns(page)
            val matches = runs.filter { run -> run.text == step["run"] }
            if (matches.size != 1) {
                val found = runs.joinToString(", ") { run -> "“${run.text}”" }
                fail("$id: $where: ${matches.size} runs on page ${page + 1} read “${step["run"]}”, not one (the runs are $found)\n" + problems.joinToString("\n"))
            }

            return matches[0]
        }

        /** CorpusRecorder.Result. */
        private fun result(result: EditResult): Map<String, Any?> = mapOf(
            "status" to result.status.name,
            "rejection" to result.rejection.name,
            "message" to result.message,
            "details" to result.details,
            "warnings" to result.warnings,
            "unsupportedCharacters" to result.unsupportedCharacters,
            "run" to result.textRun?.let { run -> json(run) },
        )

        /** CorpusRecorder.State: the session's state after [stepIndex] steps, and the pages as a reader sees them. */
        private fun state(session: DocumentSession, stepIndex: Int): Map<String, Any?> {
            val bytes = session.currentSnapshot.span
            val state = LinkedHashMap<String, Any?>()
            state["pageCount"] = session.pageCount.toDouble()
            state["modified"] = session.isModified
            state["canUndo"] = session.canUndo
            state["canRedo"] = session.canRedo
            if (stepIndex > 0) {
                val same = history.indexOfFirst { earlier -> earlier.contentEquals(bytes) }
                state["sameAs"] = if (same >= 0) same.toDouble() else null
                val previous = history[history.size - 1]
                state["appended"] = bytes.size >= previous.size && Arrays.equals(bytes, 0, previous.size, previous, 0, previous.size)
                history.add(bytes)
            }

            val file = PdfFile.tryOpen(bytes)?.takeIf { opened -> !opened.isEncrypted }
            val nodes = file?.getPages()
            val pages = ArrayList<Any?>()
            var page = 0
            while (page < session.pageCount) {
                val size = session.getPageSize(page)
                val json = LinkedHashMap<String, Any?>()
                json["width"] = round(size.width)
                json["height"] = round(size.height)
                json["runs"] = session.getTextRuns(page).map { run -> json(run) }
                json["blockers"] = session.getTextEditBlockers(page)
                val content = if (file != null && nodes != null && page < nodes.size) ContentPage.tryLoad(file, nodes, page)?.content else null
                json["content"] = content?.let { data -> sha256(data).also { hash -> contents[hash] = data } }
                json["fonts"] = if (file != null && nodes != null && page < nodes.size) fonts(file, nodes[page]) else null
                pages.add(json)
                page++
            }

            state["pages"] = pages
            return state
        }

        /**
         * Adds where an observation differs from the corpus's (ConformanceTests.Difference): objects
         * have the same keys, arrays the same items, numbers may differ by [TOLERANCE], and
         * everything else must be equal. A run's bounds are compared by [compareBounds], and
         * [PLATFORM_FONT_VALUES] are compared only on Windows.
         *
         * @param path Where the value is in the case, as in PaperKnife for Windows' messages ("$.initial.pageCount").
         * @param label The step the value is observed after, for messages; empty for the initial state.
         */
        private fun compare(expected: Any?, actual: Any?, path: String, label: String) {
            val excluded = if (IS_WINDOWS) null else PLATFORM_FONT_VALUES[id]?.get(path)
            if (excluded != null) {
                println("$id: $path is ${show(actual)}, not compared with the corpus's ${show(expected)} off Windows: $excluded")
                return
            }

            when {
                expected == null && actual == null -> {}
                expected is Map<*, *> && actual is Map<*, *> -> {
                    for (key in expected.keys + actual.keys) {
                        if (!expected.containsKey(key) || !actual.containsKey(key)) {
                            problem(label, "$path.$key is ${if (expected.containsKey(key)) "missing" else "unexpected"}")
                        } else if (key == "bounds") {
                            compareBounds(expected, actual, "$path.bounds", label)
                        } else {
                            compare(expected[key], actual[key], "$path.$key", label)
                        }
                    }
                }
                expected is List<*> && actual is List<*> -> {
                    if (expected.size != actual.size) {
                        problem(label, "$path has ${actual.size} items, not ${expected.size}: ${show(actual)} instead of ${show(expected)}")
                        return
                    }

                    for (i in expected.indices) {
                        compare(expected[i], actual[i], "$path[$i]", label)
                    }
                }
                expected is Number && actual is Number -> if (!(abs(expected.toDouble() - actual.toDouble()) <= TOLERANCE)) {
                    problem(label, "$path is ${show(actual)}, not ${show(expected)}")
                }
                expected != actual -> {
                    problem(label, "$path is ${show(actual)}, not ${show(expected)}")
                    if (path.endsWith(".content")) {
                        contentDifference(expected, actual)?.let { detail -> problems.add("  $detail") }
                    }
                }
            }
        }

        /** Compares a run's bounds within [boundsTolerance], and prints those it doesn't compare. */
        private fun compareBounds(expectedRun: Map<*, *>, actualRun: Map<*, *>, path: String, label: String) {
            val expected = expectedRun["bounds"]
            val actual = actualRun["bounds"]
            val tolerance = boundsTolerance(expectedRun)
            if (tolerance == null) {
                println("$id: $path is ${show(actual)}, not compared with the corpus's ${show(expected)} off Windows: “${expectedRun["font"]}” isn't embedded.")
                return
            }

            if (expected !is List<*> || actual !is List<*> || expected.size != actual.size || (expected + actual).any { value -> value !is Number }) {
                compare(expected, actual, path, label)
                return
            }

            for (i in expected.indices) {
                if (!(abs((expected[i] as Number).toDouble() - (actual[i] as Number).toDouble()) <= tolerance)) {
                    problem(label, "$path[$i] is ${show(actual[i])}, not ${show(expected[i])}")
                }
            }
        }

        private fun problem(label: String, text: String) {
            problems.add(if (label.isEmpty()) text else "$label: $text")
        }

        /** Where decoded content first differs from what the corpus keeps in contents/, when it keeps it. */
        private fun contentDifference(expectedHash: Any?, actualHash: Any?): String? {
            val content = contents[actualHash] ?: return null
            val kept = File(corpus, "contents/${(expectedHash as? String)?.take(12)}.content")
            if (expectedHash !is String || !kept.isFile) {
                return null
            }

            val expected = kept.readBytes()
            val at = (0 until minOf(expected.size, content.size)).firstOrNull { i -> expected[i] != content[i] } ?: minOf(expected.size, content.size)
            return "the content has ${content.size} bytes, not ${expected.size}; they first differ at byte $at: " +
                "“${latin1(content, at)}” instead of “${latin1(expected, at)}”"
        }

        private fun latin1(bytes: ByteArray, at: Int): String =
            String(bytes, at, minOf(60, bytes.size - at), Charsets.ISO_8859_1).replace("\n", "\\n")

        private fun describe(step: Map<*, *>): String = when (step["action"]) {
            "replaceText" -> "replaceText “${step["run"]}” with “${step["text"]}” on page ${int(step, "page") + 1}" +
                if (step["contentEditing"] == false) ", without content editing" else ""
            "replaceObject" -> "replaceObject ${int(step, "object")} with “${step["text"]}” on page ${int(step, "page") + 1}"
            "rotatePage" -> "rotatePage ${int(step, "page") + 1} by ${int(step, "quarterTurns")} quarter turns"
            "deletePage" -> "deletePage ${int(step, "page") + 1}"
            "movePage" -> "movePage ${int(step, "from") + 1} to ${int(step, "to") + 1}"
            "appendDocument" -> "appendDocument ${step["document"]}"
            else -> step["action"].toString()
        }

        private fun int(step: Map<*, *>, key: String): Int = (step[key] as Double).toInt()
    }

    private companion object {
        const val FORMAT = 1.0

        /** Numbers may differ this much between platforms (they are rounded to 0.01). */
        const val TOLERANCE = 0.011

        const val MAX_PROBLEMS = 40

        val corpus = File(TestFiles.root, "conformance")

        val IS_WINDOWS = System.getProperty("os.name").startsWith("Windows")

        val STANDARD_FONTS = setOf(
            "Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique",
            "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique",
            "Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic", "Symbol", "ZapfDingbats",
        )

        /**
         * How far each edge of a run's bounds may be from the corpus's, or null when they aren't
         * compared (the corpus README, “Comparing”). The bounds of a run whose font isn't embedded
         * follow the shapes of the font PDFium finds on the computer to draw it with, and the corpus
         * was recorded on Windows. So on other systems, a run in one of the standard 14 fonts may
         * differ by a tenth of its size, and the bounds of a run in any other font that isn't
         * embedded aren't compared. Everything else, and everything on Windows, is exact.
         */
        fun boundsTolerance(run: Map<*, *>): Double? = when {
            IS_WINDOWS || run["embedded"] == true -> TOLERANCE
            STANDARD_FONTS.contains(run["font"] as String) -> (run["size"] as Double) / 10
            else -> null
        }

        /**
         * Other values that follow the fonts PDFium finds on the computer, which aren't compared off
         * Windows, by case and path, with why. Where PDFium's page rewrite moves the rest of a line
         * to fit an edit, the old text's end is measured from PDFium's character boxes (see
         * `TextLayout.measureLastAdvance`), which take the substitute font's glyph shapes for a font
         * that isn't embedded. So the moved text's position can differ in its last digits.
         */
        val PLATFORM_FONT_VALUES: Map<String, Map<String, String>> = mapOf(
            // On Ubuntu 24.04 the third "A" lands at x = 96.01201 rather than 96.012, which changes
            // the content PDFium writes; the same edit in the content stream (runs-layout-as-saved-
            // content-stream), which measures the old text by its advance widths, matches.
            "runs-layout-as-saved-pdfium-rewrite" to mapOf(
                "$.steps[0].expect.state.pages[0].content" to
                    "PDFium's rewrite placed the moved text by the end of “A” in the font it draws Helvetica with",
            ),
        )

        /** A run as the corpus stores it (CorpusRecorder.Run). */
        fun json(run: PdfTextRun): Map<String, Any?> = mapOf(
            "text" to run.text,
            "object" to run.objectIndex.toDouble(),
            "objects" to run.objectCount.toDouble(),
            "font" to run.fontName,
            "size" to round(run.fontSize),
            "embedded" to run.isFontEmbedded,
            "subset" to run.isFontSubset,
            "invisible" to run.isInvisible,
            "bounds" to listOf(round(run.bounds.left), round(run.bounds.bottom), round(run.bounds.right), round(run.bounds.top)),
        )

        /** CorpusRecorder.Fonts: the page's fonts in a form that doesn't depend on how a writer ordered or compressed them. */
        fun fonts(file: PdfFile, page: PdfPageNode): List<Any?> {
            val fonts = ArrayList<Any?>()
            val entries = file.resolveDict(page.resources?.get("Font"))?.entries ?: emptyMap<String, CosObject>()
            for ((name, value) in entries.entries.sortedBy { e -> e.key }) {
                val font = file.resolveDict(value)
                if (font == null) {
                    fonts.add(mapOf("name" to name))
                    continue
                }

                val descriptor = file.resolveDict(font["FontDescriptor"])
                val key = listOf("FontFile", "FontFile2", "FontFile3").firstOrNull { k -> descriptor?.get(k) != null }
                val program = if (key == null) null else file.resolve(descriptor!![key]) as? CosStream
                fonts.add(
                    mapOf(
                        "name" to name,
                        "subtype" to file.resolveName(font["Subtype"]),
                        "baseFont" to file.resolveName(font["BaseFont"]),
                        "firstChar" to number(file.resolveNumber(font["FirstChar"])),
                        "lastChar" to number(file.resolveNumber(font["LastChar"])),
                        "widths" to file.resolveArray(font["Widths"])?.items?.map { w -> number(file.resolveNumber(w)) },
                        "encoding" to encoding(file, font["Encoding"]),
                        "toUnicode" to (font["ToUnicode"] != null),
                        "flags" to number(file.resolveNumber(descriptor?.get("Flags"))),
                        "fontFile" to key,
                        "programLength" to program?.let { stream -> file.decodeStream(stream)?.size?.toDouble() },
                    ),
                )
            }

            return fonts
        }

        fun encoding(file: PdfFile, entry: CosObject?): Any? = when (val value = file.resolve(entry)) {
            null -> null
            is CosName -> value.value
            is CosDict -> {
                val differences = LinkedHashMap<String, Any?>()
                var next = 0
                for (item in file.resolveArray(value["Differences"])?.items ?: emptyList()) {
                    when (val resolved = file.resolve(item)) {
                        is CosNumber -> next = resolved.value.toInt()
                        is CosName -> differences[(next++).toString()] = resolved.value
                        else -> {}
                    }
                }

                mapOf("base" to file.resolveName(value["BaseEncoding"]), "differences" to differences)
            }
            else -> "(other)"
        }

        /** Sizes and bounds rounded to 0.01, half away from zero (CorpusRecorder.Round). */
        fun round(value: Float): Double = DotNetMath.round(value.toDouble(), 2, awayFromZero = true)

        /** Widths and other font numbers rounded to 0.0001, half away from zero (CorpusRecorder.Number). */
        fun number(value: Double?): Double? = value?.let { v -> DotNetMath.round(v, 4, awayFromZero = true) }

        fun sha256(data: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data))

        /** A value as JSON-like text for messages, shortened as ConformanceTests.Shorten does. */
        fun show(value: Any?): String {
            val text = when (value) {
                null -> "null"
                is String -> "“$value”"
                is Double -> if (value == Math.rint(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()
                is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (key, item) -> "$key: ${show(item)}" }
                is List<*> -> value.joinToString(", ", "[", "]") { item -> show(item) }
                else -> value.toString()
            }

            return if (text.length > 300) text.substring(0, 300) + "…" else text
        }
    }
}
