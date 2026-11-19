package paperknife.core

import com.sun.jna.Pointer
import paperknife.core.pdfium.FsMatrix
import paperknife.core.pdfium.FsRectF
import paperknife.core.pdfium.PdfiumLibrary
import paperknife.core.pdfium.PdfiumNative.FPDF_PAGEOBJ_TEXT
import paperknife.core.pdfium.PdfiumNative.FPDF_TEXTRENDERMODE_INVISIBLE
import paperknife.core.pdfium.PdfiumNative.FPDFClipPath_CountPathSegments
import paperknife.core.pdfium.PdfiumNative.FPDFClipPath_CountPaths
import paperknife.core.pdfium.PdfiumNative.FPDFClipPath_GetPathSegment
import paperknife.core.pdfium.PdfiumNative.FPDFFont_GetBaseFontName
import paperknife.core.pdfium.PdfiumNative.FPDFFont_GetGlyphWidth
import paperknife.core.pdfium.PdfiumNative.FPDFFont_GetIsEmbedded
import paperknife.core.pdfium.PdfiumNative.FPDFPage_CountObjects
import paperknife.core.pdfium.PdfiumNative.FPDFPage_GetObject
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_CountMarks
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_GetBounds
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_GetClipPath
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_GetFillColor
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_GetMarkedContentID
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_GetMatrix
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_GetStrokeColor
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_GetType
import paperknife.core.pdfium.PdfiumNative.FPDFPathSegment_GetClose
import paperknife.core.pdfium.PdfiumNative.FPDFPathSegment_GetPoint
import paperknife.core.pdfium.PdfiumNative.FPDFPathSegment_GetType
import paperknife.core.pdfium.PdfiumNative.FPDFText_ClosePage
import paperknife.core.pdfium.PdfiumNative.FPDFText_CountChars
import paperknife.core.pdfium.PdfiumNative.FPDFText_GetCharBox
import paperknife.core.pdfium.PdfiumNative.FPDFText_GetCharOrigin
import paperknife.core.pdfium.PdfiumNative.FPDFText_GetLooseCharBox
import paperknife.core.pdfium.PdfiumNative.FPDFText_GetTextObject
import paperknife.core.pdfium.PdfiumNative.FPDFText_GetUnicode
import paperknife.core.pdfium.PdfiumNative.FPDFText_IsGenerated
import paperknife.core.pdfium.PdfiumNative.FPDFText_LoadPage
import paperknife.core.pdfium.PdfiumNative.FPDFTextObj_GetFont
import paperknife.core.pdfium.PdfiumNative.FPDFTextObj_GetFontSize
import paperknife.core.pdfium.PdfiumNative.FPDFTextObj_GetTextRenderMode
import paperknife.core.pdfium.SizeT
import paperknife.core.support.DotNetChar
import paperknife.core.support.DotNetFloat
import paperknife.core.support.DotNetUtf8
import paperknife.core.support.dotNetTrimEnd
import kotlin.math.abs
import kotlin.math.sqrt

/** What PDFium's text extraction put between two characters that follow each other. */
internal enum class TextGap {
    None,
    Space,
    LineBreak,
}

/** A point in page space (C#'s `(double X, double Y)`). */
internal data class PagePoint(val x: Double, val y: Double)

/** A displacement in a text space: along the baseline (U) and across it (V) (C#'s `(double U, double V)`). */
internal data class TextSpaceOffset(val u: Double, val v: Double)

/** A page's top-level text objects and the runs they form (C#'s `(Pieces, Runs)`), valid until the page changes. */
internal data class PageTextLayout(val pieces: List<TextPiece>, val runs: List<TextRunLayout>)

/** One top-level text object, as [TextLayout] sees it. */
internal class TextPiece(val objectIndex: Int, val handle: Pointer) {
    /**
     * The object's own characters in reading order, plus the spaces PDFium found between them
     * (for example for a large kerning adjustment), but not the gap after the last one.
     */
    var text: String = ""

    /**
     * False when a character has no Unicode value, or the object's characters aren't contiguous in
     * reading order. Such objects still form a run of their own, but never join other objects.
     */
    var isWellFormed: Boolean = true

    var firstCharacter: Int = -1

    var lastCharacter: Int = -1

    var lastUnicode: UInt = 0u

    /** Origins of the first and last character, in page space. */
    var firstOrigin: PagePoint = PagePoint(0.0, 0.0)

    var lastOrigin: PagePoint = PagePoint(0.0, 0.0)

    /** Advance width of the last character, in text space units. */
    var lastAdvance: Float = 0f

    /**
     * False when [lastAdvance] may be wrong: the font's width for the last character's Unicode
     * value disagrees with the boxes PDFium measured for the glyph that was drawn. Then nothing
     * after this object can be moved to fit an edit that ends here.
     */
    var isEndKnown: Boolean = true

    /** PDFium's tight box (the glyph's ink) and loose box of the last character, in page space. */
    var lastInkBox: PdfRect? = null

    var lastLooseBox: PdfRect? = null

    /** Where the last character's advance ends, in page space. */
    val end: PagePoint get() = PagePoint(lastOrigin.x + (lastAdvance * matrix.a), lastOrigin.y + (lastAdvance * matrix.b))

    /** The object whose characters come right after this one's in reading order, and what lies between them. */
    var next: TextPiece? = null

    var gapAfter: TextGap = TextGap.None

    /** Maps text space (without the font size) to page space; the translation is the object's origin. */
    var matrix: FsMatrix = FsMatrix()

    var hasMatrix: Boolean = false

    var fontSize: Float = 0f

    var font: Pointer? = null

    /** The font's name as PDFium reports it: /BaseFont, without the subset tag for embedded fonts. */
    var fontName: String = ""

    var isEmbedded: Boolean = false

    var renderMode: Int = 0

    var bounds: PdfRect? = null

    var clipSignature: FloatArray? = null

    val hasCharacters: Boolean get() = firstCharacter >= 0
}

/** A run and the text objects it is made of, valid until the page changes. */
internal class TextRunLayout(val run: PdfTextRun, val pieces: List<TextPiece>, separators: List<String>, fullText: String) {
    /** `separators[i]` is the text between `pieces[i]` and `pieces[i + 1]`: a space PDFium found in the gap, or nothing. */
    val separators: List<String> = separators

    /** The run's text including trailing whitespace, which [PdfTextRun.text] leaves out. */
    val fullText: String = fullText

    fun contains(objectIndex: Int): Boolean =
        objectIndex >= run.objectIndex && objectIndex < run.objectIndex + run.objectCount

    /** Distance along the run's baseline from the first object's origin to a page-space point, in text space units. */
    fun distanceAlongBaseline(x: Double, y: Double): Double {
        val first = pieces[0]
        return TextLayout.toTextSpace(first.matrix, x - first.matrix.e, y - first.matrix.f).u
    }

    /** Where the last character of a piece ends, along the run's baseline. */
    fun endAlongBaseline(piece: TextPiece): Double =
        distanceAlongBaseline(piece.lastOrigin.x, piece.lastOrigin.y) + piece.lastAdvance
}

/**
 * Groups a page's top-level text objects into the runs PaperKnife edits. Many PDFs draw a line
 * with one text object, but some draw every character as its own object (for example PDFs made
 * from web pages with wkhtmltopdf), or every word. Consecutive objects join a run when they
 * follow each other in reading order on the same baseline, close together, and look the same:
 * same font, size, orientation, colors, render mode, clipping and marked content. Use only while
 * holding [PdfiumLibrary.sync]; the result is valid until the page changes.
 */
internal object TextLayout {
    // Limits for joining two objects, in ems of the font size. Gaps between table cells are
    // usually wider than maxGap; gaps between words are narrower.
    private const val maxBaselineShift = 0.05
    private const val maxGap = 1.0
    private const val maxOverlap = 0.5
    private const val relativeTolerance = 1e-4

    fun build(page: Pointer, pageIndex: Int): PageTextLayout {
        val pieces = ArrayList<TextPiece>()
        val byHandle = HashMap<Pointer, TextPiece>()
        val count = FPDFPage_CountObjects(page)
        for (i in 0 until count) {
            val obj = FPDFPage_GetObject(page, i)
            if (obj != null && FPDFPageObj_GetType(obj) == FPDF_PAGEOBJ_TEXT) {
                val piece = TextPiece(i, obj)
                pieces.add(piece)
                byHandle[obj] = piece
            }
        }

        if (pieces.isEmpty()) {
            return PageTextLayout(pieces, emptyList())
        }

        val textPage = FPDFText_LoadPage(page)
        if (textPage != null) {
            try {
                readCharacters(textPage, byHandle)
            } finally {
                FPDFText_ClosePage(textPage)
            }
        }

        for (piece in pieces) {
            readGeometry(piece)
        }

        val runs = ArrayList<TextRunLayout>()
        var next = 0
        while (next < pieces.size) {
            val current = arrayListOf(pieces[next++])
            val separators = ArrayList<String>()
            while (next < pieces.size) {
                val separator = tryJoin(current.last(), pieces[next])
                if (separator != null) {
                    separators.add(separator)
                    current.add(pieces[next++])
                    continue
                }

                // Spaces drawn as objects of their own sit between the words they separate.
                var end = next
                while (end < pieces.size && isSpacer(pieces[end]) && pieces[end].objectIndex == pieces[end - 1].objectIndex + 1) {
                    end++
                }

                if (end == next || end == pieces.size || !tryJoinAcrossSpacers(current.last(), pieces, next, end)) {
                    break
                }

                while (next <= end) {
                    separators.add("")
                    current.add(pieces[next])
                    next++
                }
            }

            runs.add(createRun(pageIndex, current, separators))
        }

        return PageTextLayout(pieces, runs)
    }

    /** Maps a page-space displacement into a text space: along the baseline (U) and across it (V). */
    fun toTextSpace(m: FsMatrix, dx: Double, dy: Double): TextSpaceOffset {
        val determinant = (m.a * m.d.toDouble()) - (m.b * m.c.toDouble())
        if (abs(determinant) < 1e-12) {
            return TextSpaceOffset(Double.NaN, Double.NaN)
        }

        return TextSpaceOffset(((m.d * dx) - (m.c * dy)) / determinant, ((m.a * dy) - (m.b * dx)) / determinant)
    }

    /** The advance width of text set with [font], in text space units, as PDFium lays it out. */
    fun measure(font: Pointer?, fontSize: Float, text: String): Double {
        var total = 0.0
        for (c in text) {
            val width = FloatArray(1)
            if (FPDFFont_GetGlyphWidth(font, c.code, fontSize, width) != 0) {
                total += width[0]
            }
        }

        return total
    }

    private fun readCharacters(textPage: Pointer, pieces: Map<Pointer, TextPiece>) {
        val texts = LinkedHashMap<TextPiece, StringBuilder>()
        val count = FPDFText_CountChars(textPage)
        var previous: TextPiece? = null
        var gap = TextGap.None
        for (i in 0 until count) {
            val unicode = FPDFText_GetUnicode(textPage, i).toUInt()
            if (FPDFText_IsGenerated(textPage, i) == 1) {
                gap = if (unicode == '\r'.code.toUInt() || unicode == '\n'.code.toUInt() || gap == TextGap.LineBreak) TextGap.LineBreak else TextGap.Space
                continue
            }

            val handle = FPDFText_GetTextObject(textPage, i)
            val piece = if (handle == null) null else pieces[handle]
            if (piece == null) {
                // Text inside a form XObject isn't editable; it separates the text around it.
                previous = null
                gap = TextGap.None
                continue
            }

            var text = texts[piece]
            if (text == null) {
                text = StringBuilder()
                texts[piece] = text
                piece.firstCharacter = i
                if (previous != null) {
                    previous.next = piece
                    previous.gapAfter = gap
                }
            } else if (piece !== previous || gap == TextGap.LineBreak) {
                // The object's characters resume after another object's, or on another line.
                piece.isWellFormed = false
            }

            if (piece === previous && gap != TextGap.None) {
                text.append(' ')
            }

            if (unicode == 0u || unicode > 0x10FFFFu) {
                piece.isWellFormed = false
            } else if (unicode <= Char.MAX_VALUE.code.toUInt()) {
                // On Windows PDFium reports characters outside the BMP as two UTF-16 surrogates.
                text.append(unicode.toInt().toChar())
            } else {
                text.append(DotNetChar.convertFromUtf32(unicode.toInt())!!)
            }

            piece.lastCharacter = i
            piece.lastUnicode = unicode
            previous = piece
            gap = TextGap.None
        }

        for ((piece, text) in texts) {
            piece.text = text.toString()
            val x = DoubleArray(1)
            val y = DoubleArray(1)
            if (FPDFText_GetCharOrigin(textPage, piece.firstCharacter, x, y) != 0) {
                piece.firstOrigin = PagePoint(x[0], y[0])
            }

            if (FPDFText_GetCharOrigin(textPage, piece.lastCharacter, x, y) != 0) {
                piece.lastOrigin = PagePoint(x[0], y[0])
            }

            val left = DoubleArray(1)
            val right = DoubleArray(1)
            val bottom = DoubleArray(1)
            val top = DoubleArray(1)
            if (FPDFText_GetCharBox(textPage, piece.lastCharacter, left, right, bottom, top) != 0) {
                piece.lastInkBox = PdfRect(left[0].toFloat(), bottom[0].toFloat(), right[0].toFloat(), top[0].toFloat())
            }

            val native = FloatArray(FsRectF.SIZE)
            if (FPDFText_GetLooseCharBox(textPage, piece.lastCharacter, native) != 0) {
                val loose = FsRectF(native)
                piece.lastLooseBox = PdfRect(loose.left, loose.bottom, loose.right, loose.top)
            }
        }
    }

    private fun readGeometry(piece: TextPiece) {
        val handle = piece.handle
        val matrix = FloatArray(FsMatrix.SIZE)
        piece.hasMatrix = FPDFPageObj_GetMatrix(handle, matrix) != 0
        piece.matrix = if (piece.hasMatrix) FsMatrix(matrix) else FsMatrix()

        val size = FloatArray(1)
        FPDFTextObj_GetFontSize(handle, size)
        piece.fontSize = size[0]
        val font = FPDFTextObj_GetFont(handle)
        piece.font = font
        piece.fontName = if (font == null) "" else rawFontName(font)
        piece.isEmbedded = font != null && FPDFFont_GetIsEmbedded(font) == 1
        piece.renderMode = FPDFTextObj_GetTextRenderMode(handle)

        val left = FloatArray(1)
        val bottom = FloatArray(1)
        val right = FloatArray(1)
        val top = FloatArray(1)
        if (FPDFPageObj_GetBounds(handle, left, bottom, right, top) != 0) {
            piece.bounds = PdfRect(left[0], bottom[0], right[0], top[0])
        }

        if (piece.hasCharacters && piece.font != null) {
            measureLastAdvance(piece)
        }
    }

    /**
     * Sets the advance width of a piece's last character. The font gives a width by Unicode
     * value, which PDFium maps back to a character code; for ligatures, alternate glyphs and some
     * encodings that isn't the code that was drawn. PDFium's loose box of a character is its
     * advance box, from the code that was drawn, united with its ink; a space's ink box is its
     * advance box. So where the boxes show the advance, it is exact. Otherwise the advance ends
     * within the ink, and the font's width must agree with that.
     */
    private fun measureLastAdvance(piece: TextPiece) {
        val widthOut = FloatArray(1)
        val hasWidth = piece.lastUnicode <= Char.MAX_VALUE.code.toUInt()
            && FPDFFont_GetGlyphWidth(piece.font, piece.lastUnicode.toInt(), piece.fontSize, widthOut) != 0
        val width = widthOut[0]
        piece.lastAdvance = width
        val ink = piece.lastInkBox
        val loose = piece.lastLooseBox
        if (!piece.hasMatrix || !isAxisAligned(piece.matrix) || ink == null || loose == null) {
            return
        }

        val (inkStart, inkEnd, inkDepth) = extentAlongBaseline(piece, ink)
        val (_, looseEnd, _) = extentAlongBaseline(piece, loose)
        if (inkEnd.isNaN() || looseEnd.isNaN()) {
            return
        }

        val tolerance = piece.fontSize * 1e-3
        val isSpace = inkDepth < piece.fontSize * 0.01 && abs(inkStart) < tolerance

        // The union copies one box's edge, so the ends differ only where the advance box sticks out.
        if (looseEnd > inkEnd || isSpace) {
            piece.lastAdvance = looseEnd.toFloat()
            return
        }

        piece.isEndKnown = hasWidth && width > inkStart + tolerance && width <= inkEnd + tolerance
    }

    /** Whether text with this matrix runs along the page's x or y axis, so its boxes are exact. */
    private fun isAxisAligned(m: FsMatrix): Boolean {
        val tolerance = maxOf(maxOf(abs(m.a), abs(m.b)), maxOf(abs(m.c), abs(m.d))) * relativeTolerance
        return (abs(m.b) <= tolerance && abs(m.c) <= tolerance) || (abs(m.a) <= tolerance && abs(m.d) <= tolerance)
    }

    /** Where a box starts and ends along a baseline, and how deep it is across it (C#'s `(Start, End, Depth)`). */
    private data class Extent(val start: Double, val end: Double, val depth: Double)

    /**
     * Where a page-space box starts and ends along a piece's baseline, measured from its last
     * character's origin, and how deep it is across the baseline, in text space units.
     */
    private fun extentAlongBaseline(piece: TextPiece, box: PdfRect): Extent {
        var minU = Double.MAX_VALUE
        var maxU = -Double.MAX_VALUE
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        val xs = floatArrayOf(box.left, box.left, box.right, box.right)
        val ys = floatArrayOf(box.bottom, box.top, box.bottom, box.top)
        for (corner in 0 until 4) {
            val (u, v) = toTextSpace(piece.matrix, xs[corner] - piece.lastOrigin.x, ys[corner] - piece.lastOrigin.y)
            if (u.isNaN()) {
                return Extent(Double.NaN, Double.NaN, Double.NaN)
            }

            minU = minOf(minU, u)
            maxU = maxOf(maxU, u)
            minV = minOf(minV, v)
            maxV = maxOf(maxV, v)
        }

        return Extent(minU, maxU, maxV - minV)
    }

    /** The separator between [previous] and [next] when they join one run (C#'s `TryJoin`), or null when they don't. */
    private fun tryJoin(previous: TextPiece, next: TextPiece): String? {
        if (next.objectIndex != previous.objectIndex + 1 || !canJoin(previous, next)) {
            return null
        }

        val value = gapAlongBaseline(previous, previous.end, next.firstOrigin)
        if (value == null || value > maxGap * previous.fontSize || value < -maxOverlap * previous.fontSize) {
            return null
        }

        // A line break PDFium reports between characters on one baseline is a misjudged
        // direction (common for lines of one-character objects); the gap decides instead.
        val space = previous.gapAfter == TextGap.Space
            || (previous.gapAfter == TextGap.LineBreak && value >= spaceWidth(previous) / 2)
        if (space && !DotNetChar.isWhiteSpace(previous.text.last()) && !DotNetChar.isWhiteSpace(next.text[0])) {
            return " "
        }

        return ""
    }

    /**
     * A text object with no characters in PDFium's text extraction and a bounding box of zero
     * size. PDFium skips objects that draw only spaces (and reports the gap as a generated space),
     * so these are usually spaces, sometimes empty strings.
     */
    private fun isSpacer(piece: TextPiece): Boolean {
        val b = piece.bounds
        return !piece.hasCharacters && piece.font != null && piece.hasMatrix
            && b != null && b.width < 0.01f && b.height < 0.01f
    }

    /**
     * Joins [previous], the spacers `pieces[first..end)` and the text object `pieces[end]` when the
     * spacers sit on the line between them. Each spacer that leaves a gap of at least half a space
     * becomes a space in the run's text.
     */
    private fun tryJoinAcrossSpacers(previous: TextPiece, pieces: List<TextPiece>, first: Int, end: Int): Boolean {
        val next = pieces[end]
        if (next.objectIndex != pieces[end - 1].objectIndex + 1 || !canJoin(previous, next)) {
            return false
        }

        val size = previous.fontSize
        var position = previous.end
        val starts = ArrayList<Double>()
        for (i in first..end) {
            val piece = pieces[i]
            val origin = if (i < end) PagePoint(piece.matrix.e.toDouble(), piece.matrix.f.toDouble()) else piece.firstOrigin
            val value = gapAlongBaseline(previous, position, origin)
            if (value == null || value < (if (i == first) -maxOverlap * size else 0.0) || value > maxGap * size
                || (i < end && !sameStyle(previous, piece))
            ) {
                return false
            }

            position = origin
            starts.add(toTextSpace(previous.matrix, origin.x - previous.lastOrigin.x, origin.y - previous.lastOrigin.y).u)
        }

        val spaceWidth = spaceWidth(previous)
        for (i in first until end) {
            // PDFium doesn't measure spacers: take a normal space, but no wider than the gap it leaves.
            val spacer = pieces[i]
            val gap = starts[i - first + 1] - starts[i - first]
            val isSpace = gap >= spaceWidth / 2
            spacer.text = if (isSpace) " " else ""
            spacer.lastOrigin = PagePoint(spacer.matrix.e.toDouble(), spacer.matrix.f.toDouble())
            spacer.firstOrigin = spacer.lastOrigin
            spacer.lastAdvance = if (isSpace) minOf(spaceWidth.toDouble(), gap).toFloat() else 0f
        }

        return true
    }

    /** The width of a space in a piece's font and size, in text space units. */
    private fun spaceWidth(piece: TextPiece): Float {
        val width = FloatArray(1)
        FPDFFont_GetGlyphWidth(piece.font, ' '.code, piece.fontSize, width)
        return if (width[0] > 0) width[0] else piece.fontSize / 4
    }

    /** Whether two text objects with characters can be in one run, apart from where they are. */
    private fun canJoin(previous: TextPiece, next: TextPiece): Boolean =
        previous.isWellFormed && next.isWellFormed && previous.hasCharacters && next.hasCharacters
            && previous.next === next && sameStyle(previous, next)

    private fun sameStyle(a: TextPiece, b: TextPiece): Boolean =
        a.font != null && (a.font == b.font || sameTypeface(a, b)) && a.renderMode == b.renderMode
            && a.fontSize > 0 && abs(a.fontSize - b.fontSize) <= a.fontSize * 1e-3
            && a.hasMatrix && b.hasMatrix && sameOrientation(a.matrix, b.matrix)
            && sameColors(a, b) && sameMarks(a, b) && sameClip(a, b)

    /**
     * Whether two embedded fonts have the same name, as two subsets of one font do, and the complete
     * copy PaperKnife adds for new letters and the font it copies (PDFium reports names without
     * subset tags). Text in them belongs together.
     */
    private fun sameTypeface(a: TextPiece, b: TextPiece): Boolean =
        a.isEmbedded && b.isEmbedded && b.font != null && a.fontName.isNotEmpty() && a.fontName == b.fontName

    /**
     * How far [to] lies beyond [from] along the baseline of [piece], in text space units; null
     * when it isn't on that baseline.
     */
    private fun gapAlongBaseline(piece: TextPiece, from: PagePoint, to: PagePoint): Double? {
        val (fromU, fromV) = toTextSpace(piece.matrix, from.x - piece.lastOrigin.x, from.y - piece.lastOrigin.y)
        val (toU, toV) = toTextSpace(piece.matrix, to.x - piece.lastOrigin.x, to.y - piece.lastOrigin.y)
        if (toU.isNaN() || abs(toV - fromV) > maxBaselineShift * piece.fontSize) {
            return null
        }

        return toU - fromU
    }

    private fun sameOrientation(a: FsMatrix, b: FsMatrix): Boolean {
        val scale = maxOf(maxOf(abs(a.a), abs(a.b)), maxOf(abs(a.c), abs(a.d)))
        val tolerance = scale * relativeTolerance
        return abs(a.a - b.a) <= tolerance && abs(a.b - b.b) <= tolerance
            && abs(a.c - b.c) <= tolerance && abs(a.d - b.d) <= tolerance
    }

    private fun sameColors(a: TextPiece, b: TextPiece): Boolean {
        val fillA = tryGetColor(a.handle, stroke = false)
        if (fillA == null) {
            return false
        }

        val fillB = tryGetColor(b.handle, stroke = false)
        if (fillB == null || fillA != fillB) {
            return false
        }

        // Render modes 1, 2, 5 and 6 stroke the glyph outlines.
        if (a.renderMode == 1 || a.renderMode == 2 || a.renderMode == 5 || a.renderMode == 6) {
            val strokeA = tryGetColor(a.handle, stroke = true) ?: return false
            val strokeB = tryGetColor(b.handle, stroke = true) ?: return false
            return strokeA == strokeB
        }

        return true
    }

    /** A color PDFium reports, its components as C# reads them (C#'s `(uint R, uint G, uint B, uint A)`). */
    private data class Color(val r: UInt, val g: UInt, val b: UInt, val a: UInt)

    /** The object's fill or stroke color (C#'s `TryGetColor`), or null when PDFium has none. */
    private fun tryGetColor(handle: Pointer, stroke: Boolean): Color? {
        val r = IntArray(1)
        val g = IntArray(1)
        val b = IntArray(1)
        val a = IntArray(1)
        val ok = if (stroke) {
            FPDFPageObj_GetStrokeColor(handle, r, g, b, a) != 0
        } else {
            FPDFPageObj_GetFillColor(handle, r, g, b, a) != 0
        }

        return if (ok) Color(r[0].toUInt(), g[0].toUInt(), b[0].toUInt(), a[0].toUInt()) else null
    }

    private fun sameMarks(a: TextPiece, b: TextPiece): Boolean =
        FPDFPageObj_CountMarks(a.handle) == FPDFPageObj_CountMarks(b.handle)
            && FPDFPageObj_GetMarkedContentID(a.handle) == FPDFPageObj_GetMarkedContentID(b.handle)

    private fun sameClip(a: TextPiece, b: TextPiece): Boolean =
        DotNetFloat.sequenceEqual(getClipSignature(a), getClipSignature(b))

    private fun getClipSignature(piece: TextPiece): FloatArray {
        val cached = piece.clipSignature
        if (cached != null) {
            return cached
        }

        val values = ArrayList<Float>()
        val clip = FPDFPageObj_GetClipPath(piece.handle)
        if (clip != null) {
            val paths = FPDFClipPath_CountPaths(clip)
            for (p in 0 until paths) {
                val segments = FPDFClipPath_CountPathSegments(clip, p)
                values.add(segments.toFloat())
                for (s in 0 until segments) {
                    val segment = FPDFClipPath_GetPathSegment(clip, p, s)
                    val x = FloatArray(1)
                    val y = FloatArray(1)
                    if (segment != null) {
                        FPDFPathSegment_GetPoint(segment, x, y)
                    }

                    values.add(if (segment == null) -1f else FPDFPathSegment_GetType(segment).toFloat())
                    values.add(x[0])
                    values.add(y[0])
                    values.add(if (segment != null && FPDFPathSegment_GetClose(segment) != 0) 1f else 0f)
                }
            }
        }

        val signature = values.toFloatArray()
        piece.clipSignature = signature
        return signature
    }

    private fun createRun(pageIndex: Int, pieces: List<TextPiece>, separators: List<String>): TextRunLayout {
        val text = StringBuilder()
        var bounds: PdfRect? = null
        for (i in pieces.indices) {
            text.append(pieces[i].text)
            if (i < separators.size) {
                text.append(separators[i])
            }

            val pieceBounds = pieces[i].bounds
            if (pieceBounds != null) {
                bounds = bounds?.union(pieceBounds) ?: pieceBounds
            }
        }

        val first = pieces[0]
        val (fontName, isSubset) = readFontName(first.font)
        val m = first.matrix
        val scale = if (first.hasMatrix) sqrt((m.c * m.c) + (m.d * m.d)) else 1f
        val fullText = text.toString()
        val font = first.font
        val run = PdfTextRun(
            pageIndex,
            first.objectIndex,
            pieces.size,
            fullText.dotNetTrimEnd(),
            bounds ?: PdfRect(0f, 0f, 0f, 0f),
            fontName,
            font != null && FPDFFont_GetIsEmbedded(font) == 1,
            isSubset,
            first.fontSize * scale,
            first.renderMode == FPDF_TEXTRENDERMODE_INVISIBLE,
        )
        return TextRunLayout(run, pieces.toList(), separators.toList(), fullText)
    }

    /** A font's name without its subset tag, and whether it had one (C#'s `(string Name, bool IsSubset)`). */
    private data class FontName(val name: String, val isSubset: Boolean)

    private fun readFontName(font: Pointer?): FontName {
        if (font == null) {
            return FontName("(no font)", false)
        }

        val name = rawFontName(font)
        if (name.isEmpty()) {
            return FontName("(unnamed font)", false)
        }

        // Subset fonts carry a six-uppercase-letter tag, e.g. "ABCDEF+Arial-BoldMT" (ISO 32000-1, 9.6.4).
        val isSubset = name.length > 7 && name[6] == '+' && (0 until 6).all { name[it] in 'A'..'Z' }
        return FontName(if (isSubset) name.substring(7) else name, isSubset)
    }

    /** The font's name as PDFium reports it, or empty. */
    private fun rawFontName(font: Pointer): String {
        val length = FPDFFont_GetBaseFontName(font, null, SizeT(0)).toLong()
        if (length <= 1) {
            return ""
        }

        val buffer = ByteArray(length.toInt())
        FPDFFont_GetBaseFontName(font, buffer, SizeT(length))
        return DotNetUtf8.getString(buffer, 0, length.toInt() - 1)
    }
}
