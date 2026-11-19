package paperknife.core.editing

import com.sun.jna.Pointer
import paperknife.core.pdfium.PdfiumNative.FPDFFont_GetGlyphPath
import paperknife.core.pdfium.PdfiumNative.FPDFFont_GetGlyphWidth
import paperknife.core.pdfium.PdfiumNative.FPDFGlyphPath_CountGlyphSegments
import paperknife.core.pdfium.PdfiumNative.FPDFGlyphPath_GetGlyphPathSegment
import paperknife.core.pdfium.PdfiumNative.FPDFPathSegment_GetPoint
import paperknife.core.pdfium.PdfiumNative.FPDFPathSegment_GetType
import paperknife.core.support.DotNetChar
import paperknife.core.support.DotNetFloat
import paperknife.core.support.DotNetUnicodeCategory
import java.util.Locale

internal class TextSupportResult(val problem: String?, val characters: List<String>) {
    val isSupported: Boolean get() = problem == null

    companion object {
        val supported: TextSupportResult = TextSupportResult(null, emptyList())
    }
}

/**
 * Decides, before anything changes, whether new text can be drawn with the font a text object
 * already uses. PDFium's `FPDFText_SetText` silently turns characters it can't encode into
 * character code 0, so these checks (and a round-trip check after the edit) prevent garbage text.
 */
internal object TextSupport {
    // A Unicode noncharacter that fonts don't map, so it resolves to the font's .notdef glyph.
    private const val missingGlyphProbe = 0xFFFF
    private const val probeSize = 12f

    /**
     * @param checkGlyphs False to check only the layout rules. PDFium can't outline Type 3 glyphs,
     * so their characters are checked against the font's glyph procedures instead.
     */
    fun check(font: Pointer?, fontName: String, text: String, checkGlyphs: Boolean = true): TextSupportResult {
        if (font == null) {
            return TextSupportResult("This text has no usable font, so it can't be edited.", emptyList())
        }

        val invalid = notValidUnicode(text)
        if (invalid.isNotEmpty()) {
            return TextSupportResult("This text has characters that aren't valid Unicode: ${invalid.joinToString(", ")}", invalid)
        }

        val layout = distinct(text, ::isLayoutCharacter)
        if (layout.isNotEmpty()) {
            return TextSupportResult("Line breaks and tabs can't be added to a single line of text yet.", layout)
        }

        if (text.any { it.isSurrogate() }) {
            // C#'s EnumerateRunes, which gives U+FFFD (in the BMP) for an unpaired surrogate.
            val runes = ArrayList<String>()
            var i = 0
            while (i < text.length) {
                if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    runes.add(text.substring(i, i + 2))
                    i++
                }

                i++
            }

            return TextSupportResult("Emoji and other characters outside the Basic Multilingual Plane aren't supported yet.", runes.distinct())
        }

        val shaping = distinct(text, ::needsShaping)
        if (shaping.isNotEmpty()) {
            return TextSupportResult("Scripts that need text shaping (for example Hindi, Arabic or Thai) can't be edited yet.", shaping)
        }

        val missing = if (checkGlyphs) findMissingGlyphs(font, text) else emptyList()
        if (missing.isNotEmpty()) {
            return TextSupportResult(missingMessage(fontName, missing), missing)
        }

        return TextSupportResult.supported
    }

    /** The message for characters a font can't draw, naming the ones that are invisible. */
    fun missingMessage(fontName: String, characters: Iterable<String>): String =
        "The font “$fontName” in this PDF doesn't contain: ${characters.joinToString(", ") { describe(it) }}"

    private fun isLayoutCharacter(c: Char): Boolean =
        DotNetChar.isControl(c)
            || DotNetChar.category(c).let { it == DotNetUnicodeCategory.LineSeparator || it == DotNetUnicodeCategory.ParagraphSeparator }

    /**
     * U+FFFE and unpaired surrogates, named like "U+FFFE": pasted text can hold them, but no font
     * draws them and .NET can't normalize them.
     */
    private fun notValidUnicode(text: String): List<String> {
        val found = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            if (isSurrogatePair(text, i)) {
                i++
            } else if (text[i] == '\uFFFE' || text[i].isSurrogate()) {
                val name = String.format(Locale.ROOT, "U+%04X", text[i].code)
                if (!found.contains(name)) {
                    found.add(name)
                }
            }

            i++
        }

        return found
    }

    /** C#'s `char.IsSurrogatePair(text, index)`. */
    private fun isSurrogatePair(text: String, index: Int): Boolean =
        index + 1 < text.length && text[index].isHighSurrogate() && text[index + 1].isLowSurrogate()

    private fun needsShaping(c: Char): Boolean {
        val category = DotNetChar.category(c)
        if (category == DotNetUnicodeCategory.NonSpacingMark || category == DotNetUnicodeCategory.SpacingCombiningMark
            || category == DotNetUnicodeCategory.EnclosingMark || category == DotNetUnicodeCategory.Format
        ) {
            return true
        }

        return when (c) {
            in '\u0590'..'\u08FF' -> true // Hebrew, Arabic, Syriac, Thaana, NKo and related
            in '\u0900'..'\u0DFF' -> true // Devanagari through Sinhala
            in '\u0E00'..'\u0FFF' -> true // Thai, Lao, Tibetan
            in '\u1000'..'\u109F' -> true // Myanmar
            in '\u1780'..'\u18AF' -> true // Khmer, Mongolian
            in '\uFB1D'..'\uFDFF' -> true // Hebrew and Arabic presentation forms
            in '\uFE70'..'\uFEFF' -> true // Arabic presentation forms B
            else -> false
        }
    }

    private fun findMissingGlyphs(font: Pointer, text: String): List<String> {
        val missing = ArrayList<String>()
        val notDef = glyphOutline(font, missingGlyphProbe)
        val seen = HashSet<Char>()
        for (c in text) {
            if (!seen.add(c)) {
                continue
            }

            val width = FloatArray(1)
            var drawable = FPDFFont_GetGlyphWidth(font, c.code, probeSize, width) != 0 && width[0] > 0
            if (drawable) {
                val outline = glyphOutline(font, c.code)
                drawable = if (DotNetChar.isWhiteSpace(c)) {
                    // A real space draws nothing. An outline means PDFium fell back to the
                    // font's missing-glyph shape (usually a box), which takes up width too.
                    outline == null || outline.isEmpty()
                } else {
                    outline != null && outline.isNotEmpty() && (notDef == null || notDef.isEmpty() || !DotNetFloat.sequenceEqual(outline, notDef))
                }
            }

            if (!drawable) {
                missing.add(c.toString())
            }
        }

        return missing
    }

    /** A comparable signature of a glyph's outline, or null when PDFium has no glyph at all. */
    private fun glyphOutline(font: Pointer, codePoint: Int): FloatArray? {
        val path = FPDFFont_GetGlyphPath(font, codePoint, probeSize) ?: return null
        val count = FPDFGlyphPath_CountGlyphSegments(path)
        if (count <= 0) {
            return FloatArray(0)
        }

        val outline = FloatArray(count * 3)
        for (i in 0 until count) {
            val segment = FPDFGlyphPath_GetGlyphPathSegment(path, i)
            val x = FloatArray(1)
            val y = FloatArray(1)
            if (segment != null) {
                FPDFPathSegment_GetPoint(segment, x, y)
            }

            outline[i * 3] = if (segment == null) -1f else FPDFPathSegment_GetType(segment).toFloat()
            outline[(i * 3) + 1] = x[0]
            outline[(i * 3) + 2] = y[0]
        }

        return outline
    }

    private fun distinct(text: String, predicate: (Char) -> Boolean): List<String> =
        text.filter(predicate).map { describe(it.toString()) }.distinct()

    private fun describe(text: String): String = when {
        text == "\n" || text == "\r" || text == "\u2028" -> "line break"
        text == "\u2029" -> "paragraph break"
        text == "\t" -> "tab"
        text == " " -> "space"
        text == "\u00A0" -> "no-break space"
        text.length == 1 && (DotNetChar.isWhiteSpace(text[0]) || DotNetChar.isControl(text[0]) || DotNetChar.category(text[0]) == DotNetUnicodeCategory.Format) ->
            String.format(Locale.ROOT, "U+%04X", text[0].code)
        else -> text
    }
}
