package paperknife.core.conformance

import paperknife.core.DocumentSession
import paperknife.core.EditResult
import paperknife.core.PdfSnapshot
import paperknife.core.PdfTextRun
import paperknife.core.content.ContentPage
import paperknife.core.support.DotNetMath
import paperknife.core.syntax.CosDict
import paperknife.core.syntax.CosName
import paperknife.core.syntax.CosNumber
import paperknife.core.syntax.CosObject
import paperknife.core.syntax.CosStream
import paperknife.core.syntax.PdfFile
import paperknife.core.syntax.PdfLexer
import paperknife.core.syntax.PdfPageNode
import paperknife.core.syntax.PdfToken
import paperknife.core.syntax.PdfTokenKind
import java.security.MessageDigest
import kotlin.math.abs

/**
 * What running a case found: each difference from the corpus, the exception the case threw, if
 * any, and how long it took.
 */
class CaseOutcome(val id: String, val problems: List<String>, val error: Throwable?, val milliseconds: Long) {
    val passed: Boolean get() = problems.isEmpty() && error == null

    /** The case's id, then each difference on a line of its own (at most [ConformanceRunner.MAX_PROBLEMS]), then the exception. */
    val message: String
        get() {
            val shown = problems.take(ConformanceRunner.MAX_PROBLEMS) +
                if (problems.size > ConformanceRunner.MAX_PROBLEMS) listOf("… and ${problems.size - ConformanceRunner.MAX_PROBLEMS} more") else emptyList()
            val thrown = if (error == null) emptyList() else listOf("It threw ${error.stackTraceToString().trimEnd()}")
            return "$id:\n" + (shown + thrown).joinToString("\n")
        }
}

/**
 * Runs the cases of the conformance corpus (conformance/README.md): opens each case's document in
 * a DocumentSession, does its steps, and compares every result and state with the corpus's. It
 * observes the session as PaperKnife for Windows' CorpusRecorder does (the same calls, in the same
 * order) and compares as its ConformanceTests do: numbers within [TOLERANCE], everything else
 * exactly, steps within their maxMilliseconds. Off Windows, the bounds and content that follow the
 * fonts PDFium finds on the computer are compared as the README's “Comparing” section says
 * ([boundsTolerance], [contentMayDiffer]).
 *
 * The one implementation of the corpus's rules: the JVM's ConformanceTests (JUnit 5, a test per
 * case) and the app's instrumented test on Android (JUnit 4) both run cases through it.
 *
 * @param onWindows Whether to compare as on Windows, where the corpus was recorded.
 * @param log Where to say what off Windows was compared otherwise than exactly, and how long the
 *   steps with a time limit took.
 */
class ConformanceRunner(
    private val corpus: CorpusFiles,
    private val onWindows: Boolean = IS_WINDOWS,
    private val log: (String) -> Unit = ::println,
) {
    /** The ids of the corpus's cases, in ordinal order. */
    fun caseIds(): List<String> = corpus.list("cases").filter { name -> name.endsWith(".json") }.map { name -> name.removeSuffix(".json") }.sorted()

    /** The ids of the cases INDEX.md lists, in its order. */
    fun indexedCaseIds(): List<String> =
        Regex("""\(cases/([a-z0-9-]+)\.json\)""").findAll(text("INDEX.md")).map { match -> match.groupValues[1] }.toList()

    /** Runs a case of the corpus. */
    fun run(id: String): CaseOutcome = runJson(text("cases/$id.json"))

    /**
     * Runs a case given as the corpus stores it.
     *
     * @param keptContent The decoded content the corpus keeps for a page's content hash (contents/), or null.
     */
    fun runJson(json: String, keptContent: (String) -> ByteArray? = ::keptContent): CaseOutcome = CaseRun(json, keptContent).run()

    /** The decoded content the corpus keeps for a page's content hash, named by its first 12 hex digits, or null. */
    fun keptContent(hash: String): ByteArray? = corpus.read("contents/${hash.take(12)}.content")

    private fun text(path: String): String =
        String(corpus.read(path) ?: throw IllegalArgumentException("The corpus has no $path."), Charsets.UTF_8)

    /** A difference that ends a case: the steps after it can't be done. */
    private class CaseAborted(message: String) : Exception(message)

    /** One case: its session, the files of its earlier states, and what differs from the corpus. */
    private inner class CaseRun(json: String, private val keptContent: (String) -> ByteArray?) {
        private val stored = MiniJson.parse(json) as Map<*, *>
        private val id = stored["id"] as String
        private val problems = ArrayList<String>()

        /** The files of the states so far: the document as opened, then the state after each step. */
        private val history = ArrayList<ByteArray>()

        /** The decoded page contents observed, by SHA-256, to say where they differ from those the corpus keeps. */
        private val contents = HashMap<String, ByteArray>()

        fun run(): CaseOutcome {
            val started = System.nanoTime()
            var error: Throwable? = null
            try {
                check()
            } catch (e: CaseAborted) {
                problems.add(e.message!!)
            } catch (e: Exception) {
                error = e
            }

            return CaseOutcome(id, problems.toList(), error, (System.nanoTime() - started) / 1_000_000)
        }

        private fun check() {
            if (stored["format"] != FORMAT) {
                throw CaseAborted("$.format is ${show(stored["format"])}, not ${show(FORMAT)}")
            }

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
                    if (limit != null) {
                        log("$id: $where took $milliseconds ms, of at most ${limit.toLong()} ms.")
                        if (milliseconds > limit) {
                            problems.add("$where took $milliseconds ms, more than ${limit.toLong()} ms")
                        }
                    }

                    val expect = step["expect"] as Map<*, *>
                    compare(expect["result"], result, "$.steps[$i].expect.result", where)
                    compare(expect["state"], state(session, i + 1), "$.steps[$i].expect.state", where)
                }
            }
        }

        /** A document of the case, which the corpus names by the first 12 hex digits of its SHA-256 (CorpusRecorder.Keep). */
        private fun load(path: String): PdfSnapshot {
            val bytes = corpus.read(path) ?: throw CaseAborted("The corpus has no $path.")
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
                else -> throw CaseAborted("$where has the unknown action $action")
            }

        /** The one run on the step's page whose text is exactly the step's `run`. */
        private fun findRun(session: DocumentSession, step: Map<*, *>, where: String): PdfTextRun {
            val page = int(step, "page")
            val runs = session.getTextRuns(page)
            val matches = runs.filter { run -> run.text == step["run"] }
            if (matches.size != 1) {
                val found = runs.joinToString(", ") { run -> "“${run.text}”" }
                throw CaseAborted("$where: ${matches.size} runs on page ${page + 1} read “${step["run"]}”, not one (the runs are $found)")
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
                state["appended"] = bytes.size >= previous.size && previous.indices.all { i -> bytes[i] == previous[i] }
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
         * everything else must be equal. A run's bounds are compared by [compareBounds], and a page's
         * content that may differ off Windows ([contentMayDiffer]) by [compareContentTokens].
         *
         * @param path Where the value is in the case, as in PaperKnife for Windows' messages ("$.initial.pageCount").
         * @param label The step the value is observed after, for messages; empty for the initial state.
         */
        private fun compare(expected: Any?, actual: Any?, path: String, label: String) {
            when {
                expected == null && actual == null -> {}
                expected is Map<*, *> && actual is Map<*, *> -> {
                    for (key in expected.keys + actual.keys) {
                        if (!expected.containsKey(key) || !actual.containsKey(key)) {
                            problem(label, "$path.$key is ${if (expected.containsKey(key)) "missing" else "unexpected"}")
                        } else if (key == "bounds") {
                            compareBounds(expected, actual, "$path.bounds", label)
                        } else if (key == "content" && expected[key] != actual[key] && contentMayDiffer(expected, onWindows)) {
                            compareContentTokens(expected[key], actual[key], "$path.content", label)
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

        /** Compares a run's bounds within [boundsTolerance], and logs those it doesn't compare. */
        private fun compareBounds(expectedRun: Map<*, *>, actualRun: Map<*, *>, path: String, label: String) {
            val expected = expectedRun["bounds"]
            val actual = actualRun["bounds"]
            val tolerance = boundsTolerance(expectedRun, onWindows)
            if (tolerance == null) {
                log("$id: $path is ${show(actual)}, not compared with the corpus's ${show(expected)} off Windows: “${expectedRun["font"]}” isn't embedded.")
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

        /**
         * Compares a page's decoded content with the corpus's by its tokens ([contentTokenDifference]),
         * where its hash differs and it may ([contentMayDiffer]), and logs when they match.
         */
        private fun compareContentTokens(expectedHash: Any?, actualHash: Any?, path: String, label: String) {
            val actual = contents[actualHash]
            val kept = (expectedHash as? String)?.let { hash -> keptContent(hash) }
            if (actual == null || kept == null) {
                compare(expectedHash, actualHash, path, label)
                return
            }

            val difference = contentTokenDifference(actual, kept)
            if (difference != null) {
                problem(label, "$path is ${show(actualHash)}, not ${show(expectedHash)}: $difference")
            } else {
                log("$id: $path is ${show(actualHash)}, not ${show(expectedHash)}, with the same tokens but for numbers within ${DotNetMath.formatUpTo10Decimals(CONTENT_NUMBER_TOLERANCE)}.")
            }
        }

        /** Where decoded content first differs from what the corpus keeps in contents/, when it keeps it. */
        private fun contentDifference(expectedHash: Any?, actualHash: Any?): String? {
            val content = contents[actualHash] ?: return null
            val expected = (expectedHash as? String)?.let { hash -> keptContent(hash) } ?: return null
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

    companion object {
        /** The corpus's format, which its cases give as `format`. */
        const val FORMAT = 1.0

        /** Numbers may differ this much between platforms (they are rounded to 0.01). */
        const val TOLERANCE = 0.011

        /** The most differences a case's message lists. */
        const val MAX_PROBLEMS = 40

        val IS_WINDOWS: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

        val STANDARD_FONTS: Set<String> = setOf(
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
        fun boundsTolerance(run: Map<*, *>, onWindows: Boolean = IS_WINDOWS): Double? = when {
            onWindows || run["embedded"] == true -> TOLERANCE
            STANDARD_FONTS.contains(run["font"] as String) -> (run["size"] as Double) / 10
            else -> null
        }

        /**
         * Whether a page's content may differ from the corpus's (the corpus README, “Comparing”): off
         * Windows, when the page has a run whose font isn't embedded. Where PaperKnife measures such a
         * run to move the text after it, it measures with the font PDFium finds on the computer, so
         * the text can land slightly elsewhere (on Ubuntu, runs-layout-as-saved-pdfium-rewrite writes
         * 96.01201 for 96.012). Such content must still have the corpus's tokens ([contentTokenDifference]).
         */
        fun contentMayDiffer(page: Map<*, *>, onWindows: Boolean = IS_WINDOWS): Boolean =
            !onWindows && (page["runs"] as List<*>).any { run -> (run as Map<*, *>)["embedded"] == false }

        /** How far a number in content that may differ ([contentMayDiffer]) may be from the corpus's. */
        const val CONTENT_NUMBER_TOLERANCE = 0.0001

        /**
         * Where decoded content first differs from [expected], both read with PaperKnife's lexer, or
         * null when they have the same tokens: as many, of the same kinds, with the same text (and
         * bytes, for strings), except that numbers may differ by up to [CONTENT_NUMBER_TOLERANCE].
         */
        fun contentTokenDifference(actual: ByteArray, expected: ByteArray): String? {
            val actualTokens = PdfLexer(actual)
            val expectedTokens = PdfLexer(expected)
            var index = 1
            while (true) {
                val a = actualTokens.next()
                val e = expectedTokens.next()
                if (a.kind == PdfTokenKind.End && e.kind == PdfTokenKind.End) {
                    return null
                }

                val same = a.kind == e.kind && if (a.kind == PdfTokenKind.Number) {
                    abs(a.number - e.number) <= CONTENT_NUMBER_TOLERANCE
                } else {
                    a.text == e.text && a.bytes.contentEquals(e.bytes)
                }

                if (!same) {
                    return "token $index, at byte ${a.start}, is ${token(a)}, not ${token(e)}"
                }

                index++
            }
        }

        private fun token(token: PdfToken): String = when (token.kind) {
            PdfTokenKind.End -> "the end"
            PdfTokenKind.String -> "“<${hex(token.bytes!!).uppercase()}>”"
            PdfTokenKind.Name -> "“/${token.text}”"
            else -> "“${token.text}”"
        }

        /** A run as the corpus stores it (CorpusRecorder.Run). */
        private fun json(run: PdfTextRun): Map<String, Any?> = mapOf(
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
        private fun fonts(file: PdfFile, page: PdfPageNode): List<Any?> {
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

        private fun encoding(file: PdfFile, entry: CosObject?): Any? = when (val value = file.resolve(entry)) {
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
        private fun round(value: Float): Double = DotNetMath.round(value.toDouble(), 2, awayFromZero = true)

        /** Widths and other font numbers rounded to 0.0001, half away from zero (CorpusRecorder.Number). */
        private fun number(value: Double?): Double? = value?.let { v -> DotNetMath.round(v, 4, awayFromZero = true) }

        private fun sha256(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

        /** Lowercase hexadecimal digits (Android's HexFormat needs API 34). */
        private fun hex(bytes: ByteArray): String {
            val text = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                text.append(HEX_DIGITS[(b.toInt() shr 4) and 0xF]).append(HEX_DIGITS[b.toInt() and 0xF])
            }

            return text.toString()
        }

        private const val HEX_DIGITS = "0123456789abcdef"

        /** A value as JSON-like text for messages, shortened as ConformanceTests.Shorten does. */
        private fun show(value: Any?): String {
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
