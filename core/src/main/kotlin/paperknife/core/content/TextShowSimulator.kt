package paperknife.core.content

import paperknife.core.syntax.CosArray
import paperknife.core.syntax.CosDict
import paperknife.core.syntax.CosName
import paperknife.core.syntax.CosNumber
import paperknife.core.syntax.CosObject
import paperknife.core.syntax.CosRef
import paperknife.core.syntax.CosString
import paperknife.core.syntax.PdfFile

/** A string a text-showing operation shows, and the TJ adjustment that follows it, as C#'s tuple (byte[] Bytes, double KerningAfter). */
internal data class ShowSegment(val bytes: ByteArray, val kerningAfter: Double)

/** A code shown, and the TJ adjustment that follows it, as C#'s tuple (uint Code, double KerningAfter). */
internal data class ShowCode(val code: UInt, val kerningAfter: Double)

/** A text-showing operation (Tj, TJ, ' or ") and the text state it runs in. */
internal class ShowOp(
    val opIndex: Int,
    /** The BT/ET block it belongs to, or -1 outside one. */
    val block: Int,
    val ctm: PdfMatrix,
    /** The matrix last set by Tm (or BT); positions are relative to it, as in PDFium. */
    val textMatrix: PdfMatrix,
    /** Where the operation starts in the text matrix's space, before any initial TJ adjustment. */
    val x: Double,
    val y: Double,
    val positionKnown: Boolean,
    val fontName: String?,
    val font: PdfFontInfo?,
    val fontSize: Double,
    val charSpace: Double,
    val wordSpace: Double,
    val horizontalScale: Double,
    val rise: Double,
    /** The TJ adjustment before the first string, in thousandths of an em. */
    val initialKerning: Double,
    /** The non-empty strings shown, each with the TJ adjustment that follows it. */
    val segments: List<ShowSegment>,
    /** Whether marked content around it replaces its text (/ActualText) or can't be checked. */
    val inReplacedText: Boolean,
) {
    /** The index of the text object PDFium creates for it, or -1 when it creates none. */
    var ordinal = -1

    val producesObject: Boolean get() = ordinal >= 0

    /** Where the first glyph starts, after the initial TJ adjustment. */
    val glyphX: Double get() = x - (initialKerning / 1000 * fontSize * horizontalScale)

    /** The text matrix to set before repeating this operation unchanged. */
    val opMatrix: PdfMatrix get() = PdfMatrix.translation(x, y).then(textMatrix)

    /** The text matrix to set before showing new text in its place. */
    val glyphMatrix: PdfMatrix get() = PdfMatrix.translation(glyphX, y).then(textMatrix)

    /** The first glyph's origin in page space, where PDFium places the text object. */
    val pageStart: PdfPoint get() = textMatrix.then(ctm).transform(glyphX, y + rise)

    /** The matrix PDFium reports for the text object, without its translation. */
    val objectMatrix: PdfMatrix get() = PdfMatrix(horizontalScale, 0.0, 0.0, 1.0, 0.0, 0.0).then(textMatrix).then(ctm)

    /** The codes shown, in order, each with the TJ adjustment that follows it (0 inside a string). */
    fun codes(): MutableList<ShowCode> {
        val codes = ArrayList<ShowCode>()
        val font = font ?: return codes
        for ((bytes, kerning) in segments) {
            val split = font.splitCodes(bytes)
            for (i in split.indices) {
                codes.add(ShowCode(split[i], if (i == split.size - 1) kerning else 0.0))
            }
        }

        return codes
    }
}

internal class TextBlock(
    /** The index of the BT operation. */
    val begin: Int,
) {
    /** The index of the ET operation, or -1 when the block isn't closed. */
    var end = -1

    /** Whether it changes the graphics state in ways text blocks shouldn't (q, Q, cm). */
    var irregular = false
}

/** The text-showing operations of a page's content, as PDFium interprets them. */
internal class ContentTextLayout(
    val shows: List<ShowOp>,
    val blocks: List<TextBlock>,
    /** The operations that create text objects, in the order PDFium creates them. */
    val objectShows: List<ShowOp>,
)

/**
 * Follows the graphics and text state through page content the way PDFium's content parser does
 * (core/fpdfapi/page/cpdf_streamcontentparser.cpp): which text-showing operations create text
 * objects, and where each starts. Advances need glyph widths; where a width is unknown, positions
 * that depend on it are marked unknown.
 */
internal object TextShowSimulator {
    fun run(
        ops: List<ContentOp>,
        file: PdfFile,
        resources: CosDict?,
        width: ((ShowOp, UInt) -> Double?)? = null,
    ): ContentTextLayout {
        val widthOf = width ?: { show, code -> show.font?.width(code) }
        val fontResources = file.resolveDict(resources?.get("Font"))
        val properties = file.resolveDict(resources?.get("Properties"))
        val fonts = HashMap<String, PdfFontInfo?>()
        val shows = ArrayList<ShowOp>()
        val objectShows = ArrayList<ShowOp>()
        val blocks = ArrayList<TextBlock>()
        val saved = ArrayDeque<GraphicsState>()
        val marks = ArrayDeque<Boolean>()
        var state = GraphicsState()
        var textMatrix = PdfMatrix.identity

        // The start of the line in single precision, as PDFium keeps it (CFX_PointF), so text
        // rewritten with an explicit Tm (see ContentTextEdit) lands exactly where it was.
        var lineX = 0f
        var lineY = 0f
        var x = 0.0
        var y = 0.0
        var known = true
        var block = -1

        fun markIrregular() {
            if (block >= 0) {
                blocks[block].irregular = true
            }
        }

        fun moveTo(tx: Double, ty: Double) {
            lineX += tx.toFloat()
            lineY += ty.toFloat()
            x = lineX.toDouble()
            y = lineY.toDouble()
            known = true
        }

        fun nextLine() {
            lineY -= state.leading.toFloat()
            x = lineX.toDouble()
            y = lineY.toDouble()
            known = true
        }

        fun fontInfo(name: String?): PdfFontInfo? {
            if (name == null) {
                return null
            }

            if (!fonts.containsKey(name)) {
                // Read once per font, however many names resources give it.
                val entry = fontResources?.get(name)
                fun read(): PdfFontInfo? = file.resolveDict(entry)?.let { dict -> PdfFontInfo.load(file, dict) }
                val info = if (entry is CosRef) file.derived("FontInfo", entry.number, ::read) else read()
                fonts[name] = info
            }

            return fonts[name]
        }

        fun show(index: Int, operand: CosObject, isArray: Boolean) {
            if (isArray && operand !is CosArray) {
                return
            }

            val segments = ArrayList<ShowSegment>()
            var initial = 0.0
            var numbers = 0.0
            var anyString = false
            for (item in if (operand is CosArray && isArray) operand.items else listOf(operand)) {
                when {
                    item is CosString -> {
                        anyString = true
                        if (item.bytes.isNotEmpty()) {
                            segments.add(ShowSegment(item.bytes, 0.0))
                        }
                    }
                    item is CosNumber && isArray -> {
                        numbers += item.value
                        if (segments.isEmpty()) {
                            initial += item.value
                        } else {
                            val lastSegment = segments[segments.size - 1]
                            segments[segments.size - 1] = ShowSegment(lastSegment.bytes, lastSegment.kerningAfter + item.value)
                        }
                    }
                }
            }

            val font = fontInfo(state.fontName)
            val show = ShowOp(
                opIndex = index,
                block = block,
                ctm = state.ctm,
                textMatrix = textMatrix,
                x = x,
                y = y,
                positionKnown = known,
                fontName = state.fontName,
                font = font,
                fontSize = state.fontSize,
                charSpace = state.charSpace,
                wordSpace = state.wordSpace,
                horizontalScale = state.horizontalScale,
                rise = state.rise,
                initialKerning = initial,
                segments = segments,
                inReplacedText = marks.any { m -> m },
            )
            shows.add(show)
            if (isArray && !anyString) {
                // PDFium applies a TJ without strings as positioning only, with or without a font.
                x -= numbers / 1000 * state.fontSize * state.horizontalScale
                return
            }

            if (state.fontName == null) {
                return
            }

            if (segments.isNotEmpty()) {
                show.ordinal = objectShows.size
                objectShows.add(show)
            }

            x -= initial / 1000 * state.fontSize * state.horizontalScale
            var advance = 0.0
            for ((code, kerning) in show.codes()) {
                val w = widthOf(show, code)
                if (w == null) {
                    known = false
                    return
                }

                advance += (w * state.fontSize) + state.charSpace + (if (code == 32u && font!!.codeLength == 1) state.wordSpace else 0.0) -
                    (kerning / 1000 * state.fontSize)
            }

            if (font == null && segments.isNotEmpty()) {
                known = false
                return
            }

            x += advance * state.horizontalScale
        }

        for (i in ops.indices) {
            val o = ops[i].operands
            when (ops[i].operator) {
                "q" -> {
                    saved.addLast(state.copy())
                    markIrregular()
                }
                "Q" -> {
                    if (saved.isNotEmpty()) {
                        state = saved.removeLast()
                    }

                    markIrregular()
                }
                "cm" -> {
                    val m = PdfMatrix.fromOperands(o)
                    if (m != null) {
                        state = state.copy(ctm = m.then(state.ctm))
                    }

                    markIrregular()
                }
                "BT" -> {
                    block = blocks.size
                    blocks.add(TextBlock(i))
                    textMatrix = PdfMatrix.identity
                    lineX = 0f
                    lineY = 0f
                    x = 0.0
                    y = 0.0
                    known = true
                }
                "ET" -> {
                    if (block >= 0) {
                        blocks[block].end = i
                    }

                    block = -1
                }
                "Tf" -> {
                    val name = o.getOrNull(0)
                    val size = o.getOrNull(1)
                    if (o.size == 2 && name is CosName && size is CosNumber) {
                        state = state.copy(fontName = name.value, fontSize = size.value)
                    }
                }
                "Tc" -> number(o, 0)?.let { value -> state = state.copy(charSpace = value) }
                "Tw" -> number(o, 0)?.let { value -> state = state.copy(wordSpace = value) }
                "Tz" -> if (o.size == 1) {
                    number(o, 0)?.let { value -> state = state.copy(horizontalScale = value / 100) }
                }
                "TL" -> number(o, 0)?.let { value -> state = state.copy(leading = value) }
                "Ts" -> number(o, 0)?.let { value -> state = state.copy(rise = value) }
                "Tm" -> PdfMatrix.fromOperands(o)?.let { matrix ->
                    textMatrix = matrix
                    lineX = 0f
                    lineY = 0f
                    x = 0.0
                    y = 0.0
                    known = true
                }
                "Td" -> {
                    val tx = number(o, 0)
                    val ty = number(o, 1)
                    if (tx != null && ty != null) {
                        moveTo(tx, ty)
                    }
                }
                "TD" -> {
                    val tx = number(o, 0)
                    val ty = number(o, 1)
                    if (tx != null && ty != null) {
                        state = state.copy(leading = -ty)
                        moveTo(tx, ty)
                    }
                }
                "T*" -> nextLine()
                "'" -> if (o.isNotEmpty()) {
                    nextLine()
                    show(i, o[o.size - 1], isArray = false)
                }
                "\"" -> {
                    val aw = number(o, 0)
                    val ac = number(o, 1)
                    if (o.size >= 3 && aw != null && ac != null) {
                        state = state.copy(wordSpace = aw, charSpace = ac)
                        nextLine()
                        show(i, o[2], isArray = false)
                    }
                }
                "Tj" -> if (o.isNotEmpty()) {
                    show(i, o[o.size - 1], isArray = false)
                }
                "TJ" -> if (o.isNotEmpty()) {
                    show(i, o[o.size - 1], isArray = true)
                }
                "BDC" -> marks.addLast(
                    o.size < 2 || when (val tag = o[1]) {
                        is CosDict -> tag["ActualText"] != null
                        is CosName -> file.resolveDict(properties?.get(tag.value))?.let { props -> props["ActualText"] != null } ?: true
                        else -> true
                    },
                )
                "BMC" -> marks.addLast(false)
                "EMC" -> marks.removeLastOrNull()
            }
        }

        return ContentTextLayout(shows = shows, blocks = blocks, objectShows = objectShows)
    }

    private fun number(operands: Array<CosObject>, index: Int): Double? =
        if (index < operands.size) (operands[index] as? CosNumber)?.value else null

    private data class GraphicsState(
        val ctm: PdfMatrix = PdfMatrix.identity,
        val fontName: String? = null,
        val fontSize: Double = 0.0,
        val charSpace: Double = 0.0,
        val wordSpace: Double = 0.0,
        val horizontalScale: Double = 1.0,
        val leading: Double = 0.0,
        val rise: Double = 0.0,
    )
}
