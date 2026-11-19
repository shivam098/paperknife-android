package paperknife.core.conformance

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
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
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The conformance corpus (conformance/README.md) as PaperKnife's own reader sees it, without
 * PDFium: every page of every case's initial state must have the decoded content (as
 * ContentPage.tryLoad gives it) and the fonts PaperKnife for Windows recorded, as its
 * CorpusRecorder canonicalizes them. Running whole cases needs the PDFium layer too.
 */
class ReaderConformanceTests {
    @TestFactory
    fun initialPagesReadAsRecorded(): List<DynamicTest> {
        TestFiles.useBundledFonts()
        val corpus = File(TestFiles.root, "conformance")
        val cases = File(corpus, "cases").listFiles().orEmpty().filter { file -> file.name.endsWith(".json") }.sortedBy { file -> file.name }
        assertTrue(cases.isNotEmpty(), "The corpus has no cases.")
        return cases.map { file -> DynamicTest.dynamicTest(file.nameWithoutExtension) { check(corpus, file) } }
    }

    private fun check(corpus: File, caseFile: File) {
        val case = MiniJson.parse(caseFile.readText(Charsets.UTF_8)) as Map<*, *>
        val pages = (case["initial"] as Map<*, *>)["pages"] as List<*>
        val bytes = File(corpus, case["document"] as String).readBytes()

        // As CorpusRecorder.State reads the document: nothing when PaperKnife's reader can't read it or it is encrypted.
        val file = PdfFile.tryOpen(bytes)?.takeIf { opened -> !opened.isEncrypted }
        val nodes = file?.getPages()
        val problems = ArrayList<String>()
        for ((index, page) in pages.withIndex()) {
            val expected = page as Map<*, *>
            val read = file != null && nodes != null && index < nodes.size
            val content = if (read) ContentPage.tryLoad(file!!, nodes!!, index)?.content else null
            val hash = content?.let { data -> sha256(data) }
            if (hash != expected["content"]) {
                problems.add("page ${index + 1}: content ${describe(hash)}, not ${describe(expected["content"])}${contentDifference(corpus, expected["content"], content)}")
            }

            val fonts = if (read) fonts(file!!, nodes!![index]) else null
            difference(expected["fonts"], fonts, "page ${index + 1}: fonts")?.let { difference -> problems.add(difference) }
        }

        if (problems.isNotEmpty()) {
            fail("${caseFile.name}:\n" + problems.joinToString("\n"))
        }
    }

    /** The page's fonts in a form that doesn't depend on how a writer ordered or compressed them (CorpusRecorder.Fonts). */
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

    private fun number(value: Double?): Double? = value?.let { v -> DotNetMath.round(v, 4, awayFromZero = true) }

    /** Where two corpus values first differ (ConformanceTests.Difference): numbers may differ by [TOLERANCE]. */
    private fun difference(expected: Any?, actual: Any?, path: String): String? {
        if (expected == null && actual == null) {
            return null
        }

        if (expected is Map<*, *> && actual is Map<*, *>) {
            for (key in expected.keys + actual.keys) {
                if (!expected.containsKey(key) || !actual.containsKey(key)) {
                    return "$path.$key is ${if (expected.containsKey(key)) "missing" else "unexpected"}"
                }

                difference(expected[key], actual[key], "$path.$key")?.let { found -> return found }
            }

            return null
        }

        if (expected is List<*> && actual is List<*>) {
            if (expected.size != actual.size) {
                return "$path has ${actual.size} items, not ${expected.size}: $actual instead of $expected"
            }

            for (i in expected.indices) {
                difference(expected[i], actual[i], "$path[$i]")?.let { found -> return found }
            }

            return null
        }

        if (expected is Double && actual is Double) {
            return if (abs(expected - actual) <= TOLERANCE) null else "$path is $actual, not $expected"
        }

        return if (expected == actual) null else "$path is $actual, not $expected"
    }

    /** Where the decoded content first differs from what the corpus keeps in contents/, when it keeps it. */
    private fun contentDifference(corpus: File, expectedHash: Any?, content: ByteArray?): String {
        if (expectedHash !is String || content == null) {
            return ""
        }

        val kept = File(corpus, "contents/${expectedHash.substring(0, 12)}.content")
        if (!kept.isFile) {
            return ""
        }

        val expected = kept.readBytes()
        val at = (0 until minOf(expected.size, content.size)).firstOrNull { i -> expected[i] != content[i] } ?: minOf(expected.size, content.size)
        return " (${content.size} bytes, not ${expected.size}; they first differ at byte $at)"
    }

    private fun describe(value: Any?): String = value?.toString() ?: "null"

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

    private companion object {
        /** Numbers may differ this much between platforms (they are rounded to 0.01). */
        const val TOLERANCE = 0.011
    }
}
