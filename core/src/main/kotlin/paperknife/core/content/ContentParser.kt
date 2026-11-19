package paperknife.core.content

import paperknife.core.syntax.CosArray
import paperknife.core.syntax.CosBool
import paperknife.core.syntax.CosName
import paperknife.core.syntax.CosNumber
import paperknife.core.syntax.CosObject
import paperknife.core.syntax.CosWriter
import paperknife.core.syntax.PdfLexer
import paperknife.core.syntax.PdfObjectParser
import paperknife.core.syntax.PdfTokenKind
import kotlin.math.abs

/** One operation of a content stream: operands, operator, and the bytes it spans. */
internal data class ContentOp(val operator: String, val operands: Array<CosObject>, val start: Int, val end: Int)

/** Splits decoded page content into operations (ISO 32000-1, 7.8.2). */
internal object ContentParser {
    /** The operations, or null when the content can't be parsed with certainty. */
    fun parse(data: ByteArray): MutableList<ContentOp>? {
        val lexer = PdfLexer(data)
        val ops = ArrayList<ContentOp>()
        val operands = ArrayList<CosObject>()
        var start = -1
        while (true) {
            val token = lexer.next()
            if (token.kind == PdfTokenKind.End) {
                return ops
            }

            if (token.kind == PdfTokenKind.Keyword && token.text != "true" && token.text != "false" && token.text != "null") {
                var end = token.end
                if (token.text == "BI") {
                    end = skipInlineImage(lexer)
                    if (end < 0) {
                        return null
                    }
                }

                ops.add(ContentOp(token.text, operands.toTypedArray(), if (start >= 0) start else token.start, end))
                operands.clear()
                start = -1
                continue
            }

            if (start < 0) {
                start = token.start
            }

            val operand = PdfObjectParser.fromToken(lexer, token, allowReferences = false, depth = 0) ?: return null
            operands.add(operand)
        }
    }

    /** Moves past an inline image's dictionary, data and EI; returns the end offset, or -1. */
    private fun skipInlineImage(lexer: PdfLexer): Int {
        val span = lexer.data
        val dict = LinkedHashMap<String, CosObject>()
        while (true) {
            val token = lexer.next()
            if (token.isKeyword("ID")) {
                var data = token.end
                if (data < span.size && PdfLexer.isWhitespace(span[data].toInt() and 0xFF)) {
                    data++
                }

                val length = unfilteredLength(dict)
                if (length != null && data + length <= span.size) {
                    val after = PdfLexer(lexer.data, data + length)
                    val end = after.next()
                    if (end.isKeyword("EI")) {
                        lexer.position = end.end
                        return end.end
                    }
                }

                for (i in data until span.size - 1) {
                    if (span[i].toInt() == 'E'.code && span[i + 1].toInt() == 'I'.code &&
                        (i == data || PdfLexer.isWhitespace(span[i - 1].toInt() and 0xFF)) &&
                        (i + 2 == span.size || !PdfLexer.isRegular(span[i + 2].toInt() and 0xFF))
                    ) {
                        lexer.position = i + 2
                        return i + 2
                    }
                }

                return -1
            }

            if (token.kind != PdfTokenKind.Name) {
                return -1
            }

            val value = PdfObjectParser.fromToken(lexer, lexer.next(), allowReferences = false, depth = 0) ?: return -1
            dict[token.text] = value
        }
    }

    private fun unfilteredLength(dict: Map<String, CosObject>): Int? {
        fun get(shortKey: String, longKey: String): CosObject? = dict[shortKey] ?: dict[longKey]

        fun number(shortKey: String, longKey: String): Int? {
            val n = get(shortKey, longKey)
            return if (n is CosNumber && n.value > 0) n.value.toInt() else null
        }

        if (get("F", "Filter") != null) {
            return null
        }

        val image = get("IM", "ImageMask")
        val mask = image is CosBool && image.value
        val components = if (mask) {
            1
        } else {
            when (val colorSpace = get("CS", "ColorSpace")) {
                is CosName -> when (colorSpace.value) {
                    "G", "DeviceGray", "CalGray" -> 1
                    "RGB", "DeviceRGB", "CalRGB" -> 3
                    "CMYK", "DeviceCMYK" -> 4
                    else -> null
                }
                is CosArray -> {
                    val family = if (colorSpace.count > 0) colorSpace[0] else null
                    if (family is CosName && (family.value == "I" || family.value == "Indexed")) 1 else null
                }
                else -> null
            }
        }

        val bits = if (mask) 1 else number("BPC", "BitsPerComponent")
        val width = number("W", "Width")
        val height = number("H", "Height")
        return if (components != null && bits != null && width != null && height != null) {
            height * (((width * components * bits) + 7) / 8)
        } else {
            null
        }
    }
}

/** A point, as C#'s tuple (double X, double Y). */
internal data class PdfPoint(val x: Double, val y: Double)

/** An affine matrix [a b c d e f], mapping (x, y) to (a·x + c·y + e, b·x + d·y + f) as in PDF. */
internal data class PdfMatrix(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    /** This transformation followed by [next]. */
    fun then(next: PdfMatrix): PdfMatrix = PdfMatrix(
        (a * next.a) + (b * next.c), (a * next.b) + (b * next.d),
        (c * next.a) + (d * next.c), (c * next.b) + (d * next.d),
        (e * next.a) + (f * next.c) + next.e, (e * next.b) + (f * next.d) + next.f,
    )

    fun transform(x: Double, y: Double): PdfPoint = PdfPoint((a * x) + (c * y) + e, (b * x) + (d * y) + f)

    fun transformVector(x: Double, y: Double): PdfPoint = PdfPoint((a * x) + (c * y), (b * x) + (d * y))

    fun invert(): PdfMatrix? {
        val determinant = (a * d) - (b * c)
        return if (abs(determinant) < 1e-12) {
            null
        } else {
            PdfMatrix(
                d / determinant, -b / determinant, -c / determinant, a / determinant,
                ((c * f) - (d * e)) / determinant, ((b * e) - (a * f)) / determinant,
            )
        }
    }

    fun toOperands(): String =
        "${CosWriter.number(a)} ${CosWriter.number(b)} ${CosWriter.number(c)} ${CosWriter.number(d)} ${CosWriter.number(e)} ${CosWriter.number(f)}"

    // Equal as C#'s record struct compares doubles (double.Equals): 0 equals -0, and NaN equals NaN.
    override fun equals(other: Any?): Boolean =
        other is PdfMatrix && same(a, other.a) && same(b, other.b) && same(c, other.c) && same(d, other.d) && same(e, other.e) && same(f, other.f)

    override fun hashCode(): Int = listOf(a, b, c, d, e, f).fold(0) { hash, value -> (hash * 31) + hash(value) }

    companion object {
        val identity = PdfMatrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun translation(x: Double, y: Double): PdfMatrix = PdfMatrix(1.0, 0.0, 0.0, 1.0, x, y)

        fun fromOperands(operands: Array<CosObject>, start: Int = 0): PdfMatrix? =
            if (operands.size >= start + 6 && operands.drop(start).take(6).all { o -> o is CosNumber }) {
                PdfMatrix(
                    value(operands[start]), value(operands[start + 1]), value(operands[start + 2]),
                    value(operands[start + 3]), value(operands[start + 4]), value(operands[start + 5]),
                )
            } else {
                null
            }

        private fun value(o: CosObject): Double = (o as CosNumber).value

        private fun same(x: Double, y: Double): Boolean = x == y || (x.isNaN() && y.isNaN())

        private fun hash(value: Double): Int = (if (value == 0.0) 0.0 else if (value.isNaN()) Double.NaN else value).hashCode()
    }
}
