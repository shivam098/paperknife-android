package paperknife.core.editing

import paperknife.core.TextPiece
import paperknife.core.content.Encodings
import paperknife.core.content.PdfFontInfo
import paperknife.core.content.ShowOp
import paperknife.core.support.DotNetDouble
import paperknife.core.support.Ref
import paperknife.core.support.strictNormalizedFormC
import paperknife.core.support.substringOfLength

/**
 * A glyph to draw: its code, its advance for a font size of 1, and the TJ adjustment after it.
 * Equal like C#'s record struct: by value, with `double.Equals`.
 */
internal data class PlannedGlyph(val code: UInt, val width: Double, val kerningAfter: Double) {
    override fun equals(other: Any?): Boolean =
        other is PlannedGlyph && code == other.code && DotNetDouble.equals(width, other.width)
            && DotNetDouble.equals(kerningAfter, other.kerningAfter)

    override fun hashCode(): Int {
        var hash = code.hashCode()
        hash = (hash * 31) + DotNetDouble.hashCode(width)
        return (hash * 31) + DotNetDouble.hashCode(kerningAfter)
    }
}

/**
 * A glyph of the text being replaced: its code, width and the TJ adjustment after it, the text
 * PDFium reads for it, and whether PDFium reads that adjustment as a space. [reusable]
 * is false when the glyph was drawn in a different text state than the edited object's, so its
 * code and adjustments can't be copied. Equal like C#'s record struct: by value, with `double.Equals`.
 */
internal data class OldGlyph(
    val code: UInt,
    val width: Double,
    val kerningAfter: Double,
    val text: String,
    val show: Int,
    val spaceAfter: Boolean,
    val separatorBefore: Boolean,
    val reusable: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is OldGlyph && code == other.code && DotNetDouble.equals(width, other.width)
            && DotNetDouble.equals(kerningAfter, other.kerningAfter) && text == other.text && show == other.show
            && spaceAfter == other.spaceAfter && separatorBefore == other.separatorBefore && reusable == other.reusable

    override fun hashCode(): Int {
        var hash = code.hashCode()
        hash = (hash * 31) + DotNetDouble.hashCode(width)
        hash = (hash * 31) + DotNetDouble.hashCode(kerningAfter)
        hash = (hash * 31) + text.hashCode()
        hash = (hash * 31) + show
        hash = (hash * 31) + spaceAfter.hashCode()
        hash = (hash * 31) + separatorBefore.hashCode()
        return (hash * 31) + reusable.hashCode()
    }
}

/** A character drawn with a code chosen for it (C#'s `(string Text, uint Code)`). */
internal data class NewGlyph(val text: String, val code: UInt)

/** How a text object's new text is drawn: its glyphs and the TJ adjustments around them. */
internal class GlyphPlan(
    /** The TJ adjustment before the first glyph, in thousandths of an em (negative moves right). */
    val initialKerning: Double,
    val glyphs: List<PlannedGlyph>,
    /** The gap that spaces after the last glyph (or spaces alone) leave, as a TJ adjustment. */
    val trailingKerning: Double,
    /** The number of glyphs up to the last character that isn't a space. */
    val visibleGlyphs: Int,
    /** Characters the font has no code for. */
    val missing: List<String>,
    /** The characters drawn with codes chosen for them rather than kept from the old text, whose glyphs must be checked. */
    val newGlyphs: List<NewGlyph>,
) {
    val newCharacters: String get() = newGlyphs.joinToString("") { g -> g.text }

    val hasAdjustments: Boolean get() = initialKerning != 0.0 || glyphs.any { g -> g.kerningAfter != 0.0 }
}

/**
 * Chooses the glyphs that draw a text object's new text. Text kept from the old object keeps its
 * codes, its kerning and the gaps PDFium reads as spaces, so an edit changes only what changed.
 * Spaces the font can't draw become gaps, the way TeX sets every space: TeX fonts have no space
 * glyph, so without this no text with a space could be written in them.
 *
 * C#'s required init-only properties are the constructor's parameters.
 */
internal class GlyphPlanner(
    val font: PdfFontInfo,
    /** The advance of a code for a font size of 1, as PDFium computes it. */
    val width: (UInt) -> Double?,
    /** Codes to prefer when a character has several, normally the ones the old text used. */
    val preferred: Set<UInt>,
    /** A code that draws a space, or null when spaces have to be gaps. */
    val spaceCode: UInt?,
    /** The usual gap between words in this font, in thousandths of an em. */
    val wordGap: () -> Double,
    /** The glyphs being replaced, or null when they can't be matched to the text PDFium reads. */
    val old: List<OldGlyph>?,
) {
    fun plan(newText: String): GlyphPlan {
        val old = this.old ?: emptyList()
        val oldChars = oldCharacters(old)
        val aligned = align(oldChars, newText)
        val glyphs = ArrayList<PlannedGlyph>()
        val sources = ArrayList<Int>()
        val pending = ArrayList<Int>()
        val missing = ArrayList<String>()
        val fresh = ArrayList<NewGlyph>()
        var initial = 0.0
        var visible = 0

        fun add(code: UInt, width: Double, from: Int) {
            if (glyphs.isEmpty()) {
                initial = if (pending.isNotEmpty()) -pending.sumOf { g -> gap(g) } else 0.0
            } else {
                val previous = sources[sources.size - 1]
                var kerning = 0.0
                if (pending.size == 1 && pending[0] >= 0 && pending[0] == previous && from == previous + 1) {
                    // The same two glyphs with the same space between them: the original adjustment.
                    kerning = old[previous].kerningAfter
                } else if (pending.isNotEmpty()) {
                    // PDFium must still read the gap as a space; widen it only when it might not.
                    val amount = pending.sumOf { g -> gap(g) }
                    val needed = spaceThreshold(font, this.width, code)
                    kerning = -(if (amount < needed * 1.02) needed * 1.25 else amount)
                } else if (previous >= 0 && from == previous + 1 && old[from].show == old[previous].show && !old[previous].spaceAfter) {
                    kerning = old[previous].kerningAfter
                }

                glyphs[glyphs.size - 1] = glyphs[glyphs.size - 1].copy(kerningAfter = kerning)
            }

            glyphs.add(PlannedGlyph(code, width, 0.0))
            sources.add(from)
            pending.clear()
        }

        // An old glyph whose whole text the new text keeps at this position.
        fun reused(start: Int): Int? {
            val at = aligned[start]
            if (at < 0) {
                return null
            }

            val first = oldChars[at]
            if (first.kind != CharKind.Glyph || first.offset != 0 || !old[first.glyph].reusable) {
                return null
            }

            val text = old[first.glyph].text
            for (t in 1 until text.length) {
                if (start + t >= newText.length || aligned[start + t] != at + t) {
                    return null
                }
            }

            return first.glyph
        }

        var j = 0
        while (j < newText.length) {
            val c = newText[j]
            val at = aligned[j]
            val source = if (at >= 0) oldChars[at] else OldChar('\u0000', CharKind.Glyph, 0, 0)
            if (isSpace(c)) {
                j++
                if (at >= 0 && source.kind == CharKind.Gap) {
                    pending.add(source.glyph)
                } else if (at >= 0 && source.kind == CharKind.Glyph && old[source.glyph].text == " " && old[source.glyph].reusable) {
                    val space = old[source.glyph]
                    add(space.code, space.width, source.glyph)
                } else {
                    val code = spaceCode
                    val spaceWidth = if (code != null) width(code) else null
                    if (code != null && spaceWidth != null) {
                        add(code, spaceWidth, -1)
                    } else {
                        pending.add(-1)
                    }
                }

                continue
            }

            val kept = reused(j)
            if (kept != null) {
                add(old[kept].code, old[kept].width, kept)
                j += old[kept].text.length
                visible = glyphs.size
                continue
            }

            val (codes, length) = encode(newText, j)
            if (codes == null || codes.size != 1) {
                val text = if (isSurrogatePair(newText, j)) newText.substringOfLength(j, 2) else c.toString()
                if (!missing.contains(text)) {
                    missing.add(text)
                }

                j += text.length
                continue
            }

            val single = codes[0]
            fresh.add(NewGlyph(newText.substringOfLength(j, length), single))
            add(single, width(single) ?: Double.NaN, -1)
            j += length
            visible = glyphs.size
        }

        val trailing = if (pending.isNotEmpty()) -pending.sumOf { g -> gap(g) } else 0.0
        return GlyphPlan(
            initialKerning = initial,
            glyphs = glyphs,
            trailingKerning = trailing,
            visibleGlyphs = visible,
            missing = missing,
            newGlyphs = fresh,
        )
    }

    /** The width of a gap, in thousandths of an em: an old gap it keeps, or the font's usual word gap. */
    private fun gap(oldGlyph: Int): Double {
        val old = this.old
        return if (oldGlyph >= 0 && old != null) -old[oldGlyph].kerningAfter else wordGap()
    }

    /** Codes for the character at [start], or for a ligature starting there when the font draws the characters only together. */
    private fun encode(text: String, start: Int): Encoded {
        val length = if (isSurrogatePair(text, start)) 2 else 1
        val missing = Ref<MutableList<String>>(ArrayList())
        val codes = font.encode(text.substringOfLength(start, length), preferred, missing)
        if (missing.value.isEmpty()) {
            return Encoded(codes, length)
        }

        for (ligature in 3 downTo 2) {
            if (start + ligature <= text.length) {
                val code = font.codeFor(text.substringOfLength(start, ligature), preferred)
                if (code != null) {
                    return Encoded(listOf(code), ligature)
                }
            }
        }

        return Encoded(null, length)
    }

    /** What [encode] finds, as C#'s tuple `(List<uint>? Codes, int Length)`. */
    private data class Encoded(val codes: List<UInt>?, val length: Int)

    private enum class CharKind {
        Glyph,
        Gap,
        Separator,
    }

    private data class OldChar(val c: Char, val kind: CharKind, val glyph: Int, val offset: Int)

    companion object {
        private const val MAX_ALIGNMENT_CELLS = 4_000_000L

        /** The smallest gap before a code, in thousandths of an em, that PDFium reads as a space (cpdf_textpage.cpp). */
        fun spaceThreshold(font: PdfFontInfo, width: (UInt) -> Double?, next: UInt): Double {
            var threshold = 0.0
            val space = font.firstCodeFor(" ")
            val spaceWidth = if (space != null) width(space) else null
            if (space != null && spaceWidth != null) {
                threshold = if (spaceWidth * 1000 > 1000.0 / 3) 0.0 else spaceWidth * 1000 / 2
            }

            if (threshold <= 0) {
                val w = (width(next) ?: 0.0) * 1000
                threshold = if (w < 300) w / 2 else if (w < 500) w / 4 else if (w < 700) w / 5 else w / 6
            }

            return threshold
        }

        /**
         * Reads the glyphs of the text objects being replaced, matching them to the text PDFium reads
         * for each object ([pieces]), which includes a space wherever PDFium reads a
         * TJ adjustment as one. Returns null when the two don't match exactly.
         */
        fun readOldGlyphs(
            shows: List<ShowOp>,
            pieces: List<TextPiece>,
            separators: List<String>,
            width: (ShowOp, UInt) -> Double?,
            edited: ShowOp,
        ): MutableList<OldGlyph>? {
            val glyphs = ArrayList<OldGlyph>()
            for (s in shows.indices) {
                val show = shows[s]
                val font = show.font
                if (font == null || !pieces[s].hasCharacters) {
                    return null
                }

                val text = TextEquivalence.normalize(pieces[s].text)
                val codes = show.codes()
                val reusable = font === edited.font && show.fontSize == edited.fontSize && show.charSpace == edited.charSpace
                    && show.wordSpace == edited.wordSpace && show.horizontalScale == edited.horizontalScale && show.rise == edited.rise
                var position = 0
                for (i in codes.indices) {
                    val (code, kerning) = codes[i]
                    val unicode = font.unicode(code)
                    if (unicode == null || unicode.isEmpty()) {
                        return null
                    }

                    val w = width(show, code) ?: return null
                    val glyphText = TextEquivalence.normalize(Encodings.expandLigatures(unicode))
                    if (position + glyphText.length > text.length || !text.regionMatches(position, glyphText, 0, glyphText.length)) {
                        return null
                    }

                    position += glyphText.length

                    // PDFium adds a space for a large enough adjustment after a glyph, before the next one.
                    val spaceAfter = i < codes.size - 1 && kerning != 0.0 && position < text.length && text[position] == ' '
                        && !(font.unicode(codes[i + 1].code).let { next -> next != null && next.startsWith(' ') } &&
                            (position + 1 >= text.length || text[position + 1] != ' '))
                    if (spaceAfter) {
                        position++
                    }

                    glyphs.add(OldGlyph(code, w, kerning, comparable(unicode), s, spaceAfter, i == 0 && s > 0 && separators[s - 1] == " ", reusable))
                }

                if (position != text.length) {
                    return null
                }
            }

            return glyphs
        }

        private fun isSpace(c: Char): Boolean = c == ' ' || c == '\u00A0'

        /** C#'s `char.IsSurrogatePair(text, index)`. */
        private fun isSurrogatePair(text: String, index: Int): Boolean =
            index + 1 < text.length && text[index].isHighSurrogate() && text[index + 1].isLowSurrogate()

        /**
         * Text as alignment compares it: ligatures as their letters, as PDFium reads them, and a
         * no-break space and a space may stand in for each other, as [PdfFontInfo.encode]
         * lets them, but other spaces differ (an ideographic space is a full em wide).
         */
        private fun comparable(text: String): String =
            Encodings.expandLigatures(text).strictNormalizedFormC.replace('\u00A0', ' ')

        private fun oldCharacters(old: List<OldGlyph>): List<OldChar> {
            val chars = ArrayList<OldChar>()
            for (k in old.indices) {
                if (old[k].separatorBefore) {
                    chars.add(OldChar(' ', CharKind.Separator, -1, 0))
                }

                for (t in old[k].text.indices) {
                    chars.add(OldChar(old[k].text[t], CharKind.Glyph, k, t))
                }

                if (old[k].spaceAfter) {
                    chars.add(OldChar(' ', CharKind.Gap, k, 0))
                }
            }

            return chars
        }

        /** For each character of the new text, the old character it keeps (a longest common subsequence), or -1. */
        private fun align(old: List<OldChar>, text: String): IntArray {
            val result = IntArray(text.length) { -1 }
            val n = old.size
            val m = text.length
            if (n == 0 || m == 0 || (n + 1).toLong() * (m + 1) > MAX_ALIGNMENT_CELLS) {
                return result
            }

            val normalized = comparable(text)
            if (normalized.length != m) {
                return result
            }

            val lengths = IntArray((n + 1) * (m + 1))
            for (i in n - 1 downTo 0) {
                for (k in m - 1 downTo 0) {
                    lengths[(i * (m + 1)) + k] = if (old[i].c == normalized[k]) {
                        lengths[((i + 1) * (m + 1)) + k + 1] + 1
                    } else {
                        maxOf(lengths[((i + 1) * (m + 1)) + k], lengths[(i * (m + 1)) + k + 1])
                    }
                }
            }

            var i = 0
            var k = 0
            while (i < n && k < m) {
                if (old[i].c == normalized[k]) {
                    result[k] = i
                    k++
                    i++
                } else if (lengths[((i + 1) * (m + 1)) + k] >= lengths[(i * (m + 1)) + k + 1]) {
                    i++
                } else {
                    k++
                }
            }

            return result
        }
    }
}
