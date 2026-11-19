package paperknife.core.content

import paperknife.core.fonts.BundledFonts
import paperknife.core.support.DotNetChar
import paperknife.core.support.DotNetParse
import paperknife.core.support.dotNetTrim
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

/**
 * Character encodings of simple fonts (ISO 32000-1, Annex D) and glyph names mapped to Unicode,
 * for fonts without a ToUnicode map. Covers WinAnsiEncoding fully, and the ASCII range of
 * StandardEncoding and MacRomanEncoding; glyph names also by the Adobe Glyph List.
 */
internal object Encodings {
    // WinAnsiEncoding glyph names for codes 32 to 255; empty where the code is unused.
    private val winAnsi = arrayOf(
        "space", "exclam", "quotedbl", "numbersign", "dollar", "percent", "ampersand", "quotesingle",
        "parenleft", "parenright", "asterisk", "plus", "comma", "hyphen", "period", "slash",
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "colon", "semicolon", "less", "equal", "greater", "question", "at",
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "bracketleft", "backslash", "bracketright", "asciicircum", "underscore", "grave",
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
        "braceleft", "bar", "braceright", "asciitilde", "",
        "Euro", "", "quotesinglbase", "florin", "quotedblbase", "ellipsis", "dagger", "daggerdbl",
        "circumflex", "perthousand", "Scaron", "guilsinglleft", "OE", "", "Zcaron", "",
        "", "quoteleft", "quoteright", "quotedblleft", "quotedblright", "bullet", "endash", "emdash",
        "tilde", "trademark", "scaron", "guilsinglright", "oe", "", "zcaron", "Ydieresis",
        "space", "exclamdown", "cent", "sterling", "currency", "yen", "brokenbar", "section",
        "dieresis", "copyright", "ordfeminine", "guillemotleft", "logicalnot", "hyphen", "registered", "macron",
        "degree", "plusminus", "twosuperior", "threesuperior", "acute", "mu", "paragraph", "periodcentered",
        "cedilla", "onesuperior", "ordmasculine", "guillemotright", "onequarter", "onehalf", "threequarters", "questiondown",
        "Agrave", "Aacute", "Acircumflex", "Atilde", "Adieresis", "Aring", "AE", "Ccedilla",
        "Egrave", "Eacute", "Ecircumflex", "Edieresis", "Igrave", "Iacute", "Icircumflex", "Idieresis",
        "Eth", "Ntilde", "Ograve", "Oacute", "Ocircumflex", "Otilde", "Odieresis", "multiply",
        "Oslash", "Ugrave", "Uacute", "Ucircumflex", "Udieresis", "Yacute", "Thorn", "germandbls",
        "agrave", "aacute", "acircumflex", "atilde", "adieresis", "aring", "ae", "ccedilla",
        "egrave", "eacute", "ecircumflex", "edieresis", "igrave", "iacute", "icircumflex", "idieresis",
        "eth", "ntilde", "ograve", "oacute", "ocircumflex", "otilde", "odieresis", "divide",
        "oslash", "ugrave", "uacute", "ucircumflex", "udieresis", "yacute", "thorn", "ydieresis",
    )

    // Windows-1252 characters for codes 128 to 159, which differ from Latin-1.
    private const val CP1252_HIGH =
        "\u20AC\u0000\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\u0000\u017D\u0000" +
            "\u0000\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\u0000\u017E\u0178"

    // StandardEncoding glyph names for codes above the ASCII range.
    private val standardHigh = mapOf(
        0xA1 to "exclamdown", 0xA2 to "cent", 0xA3 to "sterling", 0xA4 to "fraction", 0xA5 to "yen", 0xA6 to "florin",
        0xA7 to "section", 0xA8 to "currency", 0xA9 to "quotesingle", 0xAA to "quotedblleft", 0xAB to "guillemotleft",
        0xAC to "guilsinglleft", 0xAD to "guilsinglright", 0xAE to "fi", 0xAF to "fl", 0xB1 to "endash", 0xB2 to "dagger",
        0xB3 to "daggerdbl", 0xB4 to "periodcentered", 0xB6 to "paragraph", 0xB7 to "bullet", 0xB8 to "quotesinglbase",
        0xB9 to "quotedblbase", 0xBA to "quotedblright", 0xBB to "guillemotright", 0xBC to "ellipsis", 0xBD to "perthousand",
        0xBF to "questiondown", 0xC1 to "grave", 0xC2 to "acute", 0xC3 to "circumflex", 0xC4 to "tilde", 0xC5 to "macron",
        0xC6 to "breve", 0xC7 to "dotaccent", 0xC8 to "dieresis", 0xCA to "ring", 0xCB to "cedilla", 0xCD to "hungarumlaut",
        0xCE to "ogonek", 0xCF to "caron", 0xD0 to "emdash", 0xE1 to "AE", 0xE3 to "ordfeminine", 0xE8 to "Lslash",
        0xE9 to "Oslash", 0xEA to "OE", 0xEB to "ordmasculine", 0xF1 to "ae", 0xF5 to "dotlessi", 0xF8 to "lslash",
        0xF9 to "oslash", 0xFA to "oe", 0xFB to "germandbls",
    )

    // Built when first needed, as C#'s static field is, and again after the app sets another font
    // source, which supplies the Adobe Glyph List (see forgetGlyphList).
    @Volatile
    private var cachedGlyphUnicode: Map<String, String>? = null

    fun winAnsiName(code: Int): String? =
        if (code in 32..255 && winAnsi[code - 32].isNotEmpty()) winAnsi[code - 32] else null

    /** StandardEncoding, for the ASCII range only. */
    fun standardName(code: Int): String? = when (code) {
        39 -> "quoteright"
        96 -> "quoteleft"
        in 32..126 -> winAnsiName(code)
        else -> null
    }

    /** StandardEncoding for every code (ISO 32000-1, D.2), for font programs that say they use it. */
    fun fullStandardName(code: Int): String? = if (code <= 126) standardName(code) else standardHigh[code]

    /** MacRomanEncoding, for the ASCII range only. */
    fun macRomanName(code: Int): String? = if (code in 32..126) winAnsiName(code) else null

    /**
     * Text with the Latin ligatures U+FB00 to U+FB06 replaced by their letters, the way PDFium's
     * text extraction reports them (cpdf_textpage.cpp, AddCharInfo).
     */
    fun expandLigatures(text: String): String {
        if (!text.any { c -> c in '\uFB00'..'\uFB06' }) {
            return text
        }

        val builder = StringBuilder(text.length + 4)
        for (c in text) {
            builder.append(
                when (c) {
                    '\uFB00' -> "ff"
                    '\uFB01' -> "fi"
                    '\uFB02' -> "fl"
                    '\uFB03' -> "ffi"
                    '\uFB04' -> "ffl"
                    '\uFB05' -> "\u017Ft"
                    '\uFB06' -> "st"
                    else -> c.toString()
                },
            )
        }

        return builder.toString()
    }

    /** The text a glyph name stands for (Adobe Glyph List conventions), or null. */
    fun glyphToUnicode(glyphName: String): String? {
        var name = glyphName
        val dot = name.indexOf('.')
        if (dot > 0) {
            name = name.substring(0, dot)
        }

        if (name.contains('_')) {
            val parts = name.split('_').map { part -> glyphToUnicode(part) }
            return if (parts.all { p -> p != null }) parts.joinToString("") else null
        }

        glyphUnicode()[name]?.let { known -> return known }

        if (name.length == 7 && name.startsWith("uni")) {
            val bmp = DotNetParse.hexInt32(name.subSequence(3, name.length))
            if (bmp != null) {
                return bmp.toChar().toString()
            }
        }

        if (name.length in 5..7 && name[0] == 'u') {
            val scalar = DotNetParse.hexInt32(name.subSequence(1, name.length))
            if (scalar != null && scalar <= 0x10FFFF && (scalar < 0xD800 || scalar > 0xDFFF)) {
                return convertFromUtf32(scalar)
            }
        }

        return null
    }

    /** Forgets the glyph names read from the Adobe Glyph List, which a new font source supplies again. */
    fun forgetGlyphList() {
        synchronized(this) {
            cachedGlyphUnicode = null
        }
    }

    private fun glyphUnicode(): Map<String, String> =
        cachedGlyphUnicode ?: synchronized(this) { cachedGlyphUnicode ?: buildGlyphUnicode().also { map -> cachedGlyphUnicode = map } }

    private fun buildGlyphUnicode(): Map<String, String> {
        val map = HashMap<String, String>()
        for (code in 32..255) {
            val name = winAnsiName(code) ?: continue
            val c = if (code in 128..159) CP1252_HIGH[code - 128] else code.toChar()
            map.putIfAbsent(name, c.toString())
        }

        for ((name, text) in listOf(
            "quoteright" to "\u2019", "quoteleft" to "\u2018", "nbspace" to "\u00A0", "sfthyphen" to "\u00AD",
            "minus" to "\u2212", "dotlessi" to "\u0131", "Lslash" to "\u0141", "lslash" to "\u0142",
            "fi" to "\uFB01", "fl" to "\uFB02", "ff" to "\uFB00", "ffi" to "\uFB03", "ffl" to "\uFB04",
            "fraction" to "\u2044", "ring" to "\u02DA", "ogonek" to "\u02DB", "caron" to "\u02C7",
            "dotaccent" to "\u02D9", "hungarumlaut" to "\u02DD", "breve" to "\u02D8", "Delta" to "\u2206",
            "Omega" to "\u2126", "pi" to "\u03C0", "mu" to "\u00B5", "rupee" to "\u20B9", "uni20B9" to "\u20B9",
            "Gamma" to "\u0393", "Theta" to "\u0398", "Lambda" to "\u039B", "Xi" to "\u039E", "Pi" to "\u03A0",
            "Sigma" to "\u03A3", "Upsilon" to "\u03A5", "Phi" to "\u03A6", "Psi" to "\u03A8",
        )) {
            map.putIfAbsent(name, text)
        }

        // Every other name the Adobe Glyph List has, as FreeType reads them for PDFium.
        for ((name, text) in adobeGlyphList()) {
            map.putIfAbsent(name, text)
        }

        return map
    }

    /**
     * The names in the Adobe Glyph List (third_party/agl, which the app ships with its fonts; see
     * [BundledFonts.source]) and the text each stands for. None when the source has no glyph list.
     */
    private fun adobeGlyphList(): List<Pair<String, String>> {
        val entries = ArrayList<Pair<String, String>>()
        val bytes = try {
            BundledFonts.source.openGlyphList()?.use { stream -> stream.readBytes() }
        } catch (e: IOException) {
            null
        } ?: return entries

        // ASCII, as .NET decodes it: other bytes are "?".
        val ascii = CharArray(bytes.size) { i -> if (bytes[i] >= 0) bytes[i].toInt().toChar() else '?' }
        val reader = BufferedReader(StringReader(String(ascii)))
        val text = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            val separator = line.indexOf(';')
            if (line.startsWith('#') || separator <= 0) {
                continue
            }

            // One or more scalar values, in hexadecimal, separated by spaces.
            text.clear()
            for (value in line.substring(separator + 1).split(' ').map { part -> part.dotNetTrim() }.filter { part -> part.isNotEmpty() }) {
                val scalar = DotNetParse.hexInt32(value)
                if (scalar == null || scalar > 0x10FFFF || scalar in 0xD800..0xDFFF) {
                    text.clear()
                    break
                }

                text.append(convertFromUtf32(scalar))
            }

            if (text.isNotEmpty()) {
                entries.add(line.substring(0, separator) to text.toString())
            }
        }

        return entries
    }

    /** `char.ConvertFromUtf32`, which throws where [DotNetChar.convertFromUtf32] gives null. */
    private fun convertFromUtf32(scalar: Int): String =
        DotNetChar.convertFromUtf32(scalar) ?: throw IllegalArgumentException("A valid UTF32 value is between 0x000000 and 0x10ffff, inclusive, and should not include surrogate codepoint values (0x00d800 ~ 0x00dfff).")
}
