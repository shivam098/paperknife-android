package paperknife.core

import paperknife.core.fonts.BundledFonts
import paperknife.core.fonts.CharStringAnalyzer
import paperknife.core.fonts.Type1Font
import paperknife.core.support.DotNetChar

/** Which bundled font [TexPdf] cuts its subset from and how, and the ways a crafted subset can differ. */
internal data class TexSubset(
    /** The bundled font: CMR10 as pdfLaTeX embeds it by default, LMRoman10-Regular with lmodern, SFRM1000 with T1 encoding. */
    val font: String = "CMR10",
    /** The font's name in the PDF, when it isn't the bundled font's: as for a font PaperKnife doesn't ship. */
    val rename: String? = null,
    val glyphs: List<String> = listOf("H", "d", "e", "l", "o", "r", "w"),
    /** A glyph whose program is replaced by "e"'s, so the subset no longer matches the full font. */
    val replaceGlyph: String? = null,
    /** A token among the glyph programs, before the last one. Renderers skip it. */
    val strayToken: Boolean = false,
    /** The first glyph defined twice: first with "e"'s program, which renderers use, then with its own. */
    val duplicateGlyph: Boolean = false,
    /** A glyph whose last subroutine becomes a stub that only returns, as subsetters do for unused ones. */
    val stubSubroutineOf: String? = null,
    /** Bytes of comments full of half-written keys in the private part (and a tenth in the cleartext). */
    val junk: Int = 0,
)

/**
 * Subsets of the Type 1 fonts PaperKnife ships, cut the way pdfTeX cuts them, from PaperKnife for
 * Windows' TexPdf. Its PDFs (TexPdf.Build) serve tests of document sessions, which the corpus runs;
 * this port has only what the tests of the font layer need, and the subset's options for them.
 */
internal object TexPdf {
    val full: Type1Font get() = font("CMR10")

    fun font(name: String): Type1Font {
        TestFiles.useBundledFonts()
        return BundledFonts.findType1(name)!!
    }

    /**
     * The three parts of a subset of a bundled font, as Latin-1 text: only some glyphs, and
     * .notdef, written with the font's own spelling of the charstring delimiters (RD and ND, or
     * -| and |-), as subsetters keep them.
     */
    fun subset(subset: TexSubset): Triple<String, String, String> {
        val full = font(subset.font)
        var clear = String(full.program, 0, full.length1, Charsets.ISO_8859_1)
            .replace("/FontName /${full.fontName}", "/FontName /AAAAAA+${subset.rename ?: full.fontName}")
        val trailer = String(full.program, full.length1 + full.length2, full.length3, Charsets.ISO_8859_1)

        // The private part keeps its random first bytes, so it encrypts back to the same start.
        val plain = String(Type1Font.decrypt(full.program, full.length1, full.length2, 55665, 0), Charsets.ISO_8859_1)
        val begin = plain.indexOf("begin", plain.indexOf("/CharStrings")) + 5
        val entries = HashMap<String, String>()
        var rd = "RD"
        var nd = "ND"
        var position = begin
        while (true) {
            while (DotNetChar.isWhiteSpace(plain[position])) {
                position++
            }

            if (plain[position] != '/') {
                break
            }

            val entry = entry(plain, position, 3)
            entries[entry.first.substring(1)] = entry.data
            rd = entry.rd
            val next = nextToken(plain, entry.end)
            nd = next.first
            position = next.second
        }

        val kept = StringBuilder("\n")
        val glyphs = subset.glyphs + ".notdef"
        if (subset.duplicateGlyph) {
            kept.append("/${glyphs[0]} ${entries.getValue("e").length} $rd ${entries.getValue("e")} $nd\n")
        }

        for (i in glyphs.indices) {
            if (subset.strayToken && i == glyphs.size - 1) {
                kept.append("0 pop\n")
            }

            val data = if (glyphs[i] == subset.replaceGlyph) entries.getValue("e") else entries.getValue(glyphs[i])
            kept.append("/${glyphs[i]} ${data.length} $rd $data $nd\n")
        }

        var result = plain.substring(0, begin) + kept + plain.substring(position)
        val glyph = subset.stubSubroutineOf
        if (glyph != null) {
            // "dup <index> <length> RD <data>" becomes a stub that only returns (after four random bytes).
            val index = CharStringAnalyzer.analyze(full.charStrings.getValue(glyph), full.subrs, CharStringAnalyzer.Budget(100_000))!!.subrs.max()
            val start = result.indexOf("dup $index ", result.indexOf("/Subrs"))
            val stubbed = entry(result, start + 4, 3)
            val stub = String(encrypt(byteArrayOf(0, 0, 0, 0, 11), 4330), Charsets.ISO_8859_1)
            result = result.substring(0, start) + "dup $index 5 ${stubbed.rd} $stub" + result.substring(stubbed.end)
        }

        if (subset.junk > 0) {
            val line = clear.indexOf('\n') + 1
            clear = clear.substring(0, line) + "%" + repeat("/FontMatrix[", subset.junk / 10) + "\n" + clear.substring(line)
            val keys = result.indexOf("/CharStrings")
            result = result.substring(0, keys) + "%" + repeat("/BlueValues[", subset.junk) + "\n" + result.substring(keys)
        }

        return Triple(clear, String(encrypt(result.toByteArray(Charsets.ISO_8859_1), 55665), Charsets.ISO_8859_1), trailer)
    }

    /** "<first> <length> <RD> <data>" at a position: the first token, the data, where the data ends and how RD is spelled. */
    private class Entry(val first: String, val data: String, val end: Int, val rd: String)

    private fun entry(text: String, position: Int, tokens: Int): Entry {
        val parts = text.substring(position, position + minOf(80, text.length - position)).split(' ', limit = tokens + 1)
        val length = parts[1].toInt()
        val dataStart = position + parts.take(tokens).sumOf { p -> p.length + 1 }
        return Entry(parts[0], text.substring(dataStart, dataStart + length), dataStart + length, parts[tokens - 1])
    }

    // The token after a position (such as ND after a glyph's data), and where it ends.
    private fun nextToken(text: String, start: Int): Pair<String, Int> {
        var position = start
        while (DotNetChar.isWhiteSpace(text[position])) {
            position++
        }

        var end = position
        while (end < text.length && !DotNetChar.isWhiteSpace(text[end])) {
            end++
        }

        return text.substring(position, end) to end
    }

    private fun repeat(text: String, bytes: Int): String = text.repeat(maxOf(1, bytes / text.length))

    private fun encrypt(plain: ByteArray, key: Int): ByteArray {
        val cipher = ByteArray(plain.size)
        var r = key
        for (i in plain.indices) {
            val c = (plain[i].toInt() xor (r shr 8)) and 0xFF
            cipher[i] = c.toByte()
            r = ((c + r) * 52845 + 22719) and 0xFFFF
        }

        return cipher
    }
}
