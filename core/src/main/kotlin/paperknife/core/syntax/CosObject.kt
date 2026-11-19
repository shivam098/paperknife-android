package paperknife.core.syntax

import paperknife.core.support.DotNetMath
import kotlin.math.abs

/** A PDF object (ISO 32000-1, 7.3), as read from a file or a content stream. */
internal sealed class CosObject

internal class CosNull private constructor() : CosObject() {
    companion object {
        val instance = CosNull()
    }
}

internal class CosBool(val value: Boolean) : CosObject()

/** A number, with its original spelling so that it can be written back unchanged. */
internal class CosNumber(val value: Double, val raw: String) : CosObject() {
    val isInteger: Boolean get() = !raw.contains('.') && abs(value) <= Int.MAX_VALUE.toDouble()
}

/** A name without its slash. Escapes are decoded; each byte maps to one char. */
internal class CosName(val value: String) : CosObject()

internal class CosString(val bytes: ByteArray) : CosObject()

internal class CosArray(val items: MutableList<CosObject>) : CosObject() {
    val count: Int get() = items.size

    operator fun get(index: Int): CosObject = items[index]
}

internal class CosDict(val entries: MutableMap<String, CosObject>) : CosObject() {
    operator fun get(key: String): CosObject? = entries[key]
}

internal class CosRef(val number: Int, val generation: Int) : CosObject()

/** A stream: its dictionary and where its encoded data lies in the file. */
internal class CosStream(val dict: CosDict, val dataStart: Int, val dataLength: Int) : CosObject()

/** Writes objects in PDF syntax, as Latin-1 text. Strings are written in hexadecimal. */
internal object CosWriter {
    private const val HEX_DIGITS = "0123456789ABCDEF"

    fun toText(value: CosObject): String {
        val builder = StringBuilder()
        write(builder, value)
        return builder.toString()
    }

    fun write(builder: StringBuilder, value: CosObject) {
        when (value) {
            is CosNull -> builder.append("null")
            is CosBool -> builder.append(if (value.value) "true" else "false")
            is CosNumber -> builder.append(value.raw)
            is CosName -> writeName(builder, value.value)
            is CosString -> writeHex(builder, value.bytes)
            is CosRef -> builder.append(value.number).append(' ').append(value.generation).append(" R")
            is CosArray -> {
                builder.append('[')
                for (i in 0 until value.count) {
                    if (i > 0) {
                        builder.append(' ')
                    }

                    write(builder, value[i])
                }

                builder.append(']')
            }
            is CosDict -> {
                builder.append("<<")
                for ((key, entry) in value.entries) {
                    writeName(builder, key)
                    builder.append(' ')
                    write(builder, entry)
                }

                builder.append(">>")
            }
            is CosStream -> throw IllegalArgumentException("Streams can't be written inline.")
        }
    }

    fun writeName(builder: StringBuilder, name: String) {
        builder.append('/')
        for (c in name) {
            val b = c.code and 0xFF
            if (b < 0x21 || b > 0x7E || b == '#'.code || PdfLexer.isDelimiter(b)) {
                builder.append('#').append(HEX_DIGITS[b shr 4]).append(HEX_DIGITS[b and 0xF])
            } else {
                builder.append(c)
            }
        }
    }

    fun writeHex(builder: StringBuilder, bytes: ByteArray) {
        builder.append('<')
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            builder.append(HEX_DIGITS[b shr 4]).append(HEX_DIGITS[b and 0xF])
        }

        builder.append('>')
    }

    /** A number in plain decimal notation, with at most ten decimals. */
    fun number(value: Double): String {
        val text = DotNetMath.formatUpTo10Decimals(DotNetMath.round(value, 10))
        return if (text == "-0") "0" else text
    }
}
