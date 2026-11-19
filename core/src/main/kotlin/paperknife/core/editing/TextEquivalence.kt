package paperknife.core.editing

import paperknife.core.support.DotNetChar
import paperknife.core.support.DotNetNormalization
import paperknife.core.support.DotNetUnicodeCategory

/**
 * Compares text the way a reader sees it. Fonts often share one glyph between several code points
 * (for example U+0020 SPACE and U+00A0 NO-BREAK SPACE), so text read back from a PDF can differ
 * from what was written without any visible difference. Only canonically equivalent characters
 * (NFC, which also maps U+2126 OHM SIGN to U+03A9) and space separators are treated as equal;
 * compatibility variants such as "²" and "2" or "…" and "..." stay different because they look
 * different.
 */
internal object TextEquivalence {
    fun areEquivalent(a: String, b: String): Boolean = normalize(a) == normalize(b)

    /**
     * Like [areEquivalent], but ignores how much whitespace separates words and any at the ends.
     * PDFium's text extraction skips text objects that draw only spaces and reports gaps as single
     * generated spaces, so the spacing of rewritten text can't be read back exactly. Every space is
     * checked to be drawable before it is written (see [TextSupport]).
     */
    fun areEquivalentIgnoringSpacing(a: String, b: String): Boolean =
        collapseSpaces(normalize(a)) == collapseSpaces(normalize(b))

    fun normalize(text: String): String {
        val normalized = tryNormalize(text) ?: text
        val chars = CharArray(normalized.length)
        for (i in normalized.indices) {
            chars[i] = if (DotNetChar.category(normalized[i]) == DotNetUnicodeCategory.SpaceSeparator) ' ' else normalized[i]
        }

        return String(chars)
    }

    /**
     * The text in NFC, or null when .NET can't normalize it because it has U+FFFE or an unpaired
     * surrogate, as pasted text or a PDF's character map can. Such text compares as it is.
     */
    private fun tryNormalize(text: String): String? = try {
        DotNetNormalization.formC(text)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Characters of [expected] that are missing from [actual]. */
    fun lost(expected: String, actual: String): List<String> {
        val have = normalize(actual)
        return expected
            .filter { c -> !have.contains(normalize(c.toString())) }
            .map { c -> c.toString() }
            .distinct()
    }

    /** C#'s `string.Join(' ', text.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries))`: words split at white space. */
    private fun collapseSpaces(text: String): String {
        val words = ArrayList<String>()
        var start = -1
        for (i in text.indices) {
            if (DotNetChar.isWhiteSpace(text[i])) {
                if (start >= 0) {
                    words.add(text.substring(start, i))
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
        }

        if (start >= 0) {
            words.add(text.substring(start))
        }

        return words.joinToString(" ")
    }
}
