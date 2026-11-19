package paperknife.core.syntax

import paperknife.core.support.Ref
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.max

/**
 * .NET's ZLibException, which its zlib streams throw for errors other than damaged data, such as
 * data that asks for a preset dictionary, which a PDF can't give. Like it, this is an IOException,
 * and inflating treats it as damaged data, as PaperKnife for Windows does.
 */
internal class ZLibException(message: String) : IOException(message)

/** Decodes stream data (ISO 32000-1, 7.4) and encodes the streams PaperKnife writes. */
internal object StreamFilters {
    /** The most a single stream may decode to; larger streams are left to PDFium. */
    const val MAX_DECODED_LENGTH = 64 * 1024 * 1024

    /**
     * The decoded data of [length] bytes of [data] from [offset], or null when a filter isn't
     * supported, the data is damaged or it decodes to more than [limit] bytes.
     */
    fun decode(
        data: ByteArray,
        offset: Int,
        length: Int,
        filters: List<String>,
        parameters: List<CosDict?>,
        problem: Ref<String?>?,
        limit: Int = MAX_DECODED_LENGTH,
    ): ByteArray? {
        var current = data.copyOfRange(offset, offset + length)
        for (i in filters.indices) {
            val p = if (i < parameters.size) parameters[i] else null
            val next = when (filters[i]) {
                "FlateDecode", "Fl" -> predict(inflate(current, limit), p)
                "LZWDecode", "LZW" -> predict(lzw(current, integer(p, "EarlyChange", 1), limit), p)
                "ASCIIHexDecode", "AHx" -> asciiHex(current)
                "ASCII85Decode", "A85" -> ascii85(current, limit)
                "RunLengthDecode", "RL" -> runLength(current, limit)
                else -> null
            }
            if (next == null || next.size > limit) {
                problem?.value = "The stream's ${filters[i]} data can't be decoded."
                return null
            }

            current = next
        }

        problem?.value = null
        return current
    }

    fun deflate(data: ByteArray): ByteArray {
        // .NET's ZLibStream writes nothing at all when it is given no data.
        if (data.isEmpty()) {
            return ByteArray(0)
        }

        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            deflater.setInput(data)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                output.write(buffer, 0, deflater.deflate(buffer))
            }

            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun integer(parameters: CosDict?, key: String, fallback: Int): Int {
        val n = parameters?.get(key)
        return if (n is CosNumber && n.isInteger) n.value.toInt() else fallback
    }

    /**
     * The first [length] bytes of the Flate data in [count] bytes of [data] from [offset] (fewer
     * when it is shorter), or null when it is damaged.
     */
    fun inflatePrefix(data: ByteArray, offset: Int, count: Int, length: Int): ByteArray? {
        val z = ZLibReader(data, offset, count)
        try {
            val output = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = z.read(output, read, length - read)
                if (n <= 0) {
                    break
                }

                read += n
            }

            return if (read == length) output else output.copyOf(read)
        } catch (e: DataFormatException) {
            return null
        } catch (e: IOException) {
            // Data that asks for a preset dictionary (see ZLibException).
            return null
        } finally {
            z.close()
        }
    }

    private fun inflate(data: ByteArray, limit: Int): ByteArray? {
        val z = ZLibReader(data, 0, data.size)
        try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(81920)
            while (true) {
                val read = z.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    break
                }

                if (output.size().toLong() + read > limit) {
                    return null
                }

                output.write(buffer, 0, read)
            }

            return output.toByteArray()
        } catch (e: DataFormatException) {
            return null
        } catch (e: IOException) {
            // Data that asks for a preset dictionary (see ZLibException).
            return null
        } finally {
            z.close()
        }
    }

    /**
     * Reads zlib data as .NET's ZLibStream reads it from a MemoryStream (DeflateStream.ReadCore):
     * input goes to zlib 8 KB at a time, damaged data throws DataFormatException (where .NET throws
     * InvalidDataException), and data that ends early ends the output without an error, as .NET
     * doesn't validate truncated streams unless asked to.
     */
    private class ZLibReader(private val data: ByteArray, offset: Int, count: Int) {
        private val inflater = Inflater()
        private var position = offset
        private val end = offset + count

        fun read(output: ByteArray, at: Int, length: Int): Int {
            var bytesRead: Int
            while (true) {
                bytesRead = inflate(output, at, length)
                if (bytesRead != 0 || inflater.finished()) {
                    break
                }

                if (inflater.needsInput()) {
                    val n = minOf(INPUT_BUFFER, end - position)
                    if (n <= 0) {
                        break
                    }

                    inflater.setInput(data, position, n)
                    position += n
                }

                if (length == 0) {
                    break
                }
            }

            return bytesRead
        }

        private fun inflate(output: ByteArray, at: Int, length: Int): Int {
            if (length == 0) {
                return 0
            }

            val n = inflater.inflate(output, at, length)
            if (n == 0 && inflater.needsDictionary()) {
                throw ZLibException("The underlying compression routine returned an unexpected error code.")
            }

            return n
        }

        fun close() = inflater.end()

        private companion object {
            /** DeflateStream's input buffer. */
            const val INPUT_BUFFER = 8192
        }
    }

    private fun predict(data: ByteArray?, parameters: CosDict?): ByteArray? {
        val predictor = integer(parameters, "Predictor", 1)
        if (data == null || predictor == 1) {
            return data
        }

        val colors = integer(parameters, "Colors", 1)
        val bits = integer(parameters, "BitsPerComponent", 8)
        val columns = integer(parameters, "Columns", 1)
        if (colors < 1 || colors > 32 || (bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 16) ||
            columns < 1 || columns > 1_000_000
        ) {
            return null
        }

        val bytesPerPixel = max(1, ((colors * bits) + 7) / 8)
        val rowLength = ((colors * bits * columns) + 7) / 8
        if (rowLength > data.size) {
            return if (data.isEmpty()) data else null
        }

        if (predictor == 2) {
            if (bits != 8) {
                return null
            }

            val result = data.clone()
            var row = 0
            while (row + rowLength <= result.size) {
                for (i in row + bytesPerPixel until row + rowLength) {
                    result[i] = (result[i] + result[i - bytesPerPixel]).toByte()
                }

                row += rowLength
            }

            return result
        }

        if (predictor < 10) {
            return null
        }

        val rows = data.size / (rowLength + 1)
        val output = ByteArray(rows * rowLength)
        val prior = ByteArray(rowLength)
        for (r in 0 until rows) {
            val type = data[r * (rowLength + 1)].toInt() and 0xFF
            if (type > 4) {
                return null
            }

            val source = (r * (rowLength + 1)) + 1
            val target = r * rowLength
            for (i in 0 until rowLength) {
                val left = if (i >= bytesPerPixel) output[target + i - bytesPerPixel].toInt() and 0xFF else 0
                val up = prior[i].toInt() and 0xFF
                val upLeft = if (i >= bytesPerPixel) prior[i - bytesPerPixel].toInt() and 0xFF else 0
                output[target + i] = ((data[source + i].toInt() and 0xFF) + when (type) {
                    1 -> left
                    2 -> up
                    3 -> (left + up) shr 1
                    4 -> paeth(left, up, upLeft)
                    else -> 0
                }).toByte()
            }

            output.copyInto(prior, 0, target, target + rowLength)
        }

        return output
    }

    private fun paeth(left: Int, up: Int, upLeft: Int): Int {
        val p = left + up - upLeft
        val pa = abs(p - left)
        val pb = abs(p - up)
        val pc = abs(p - upLeft)
        return if (pa <= pb && pa <= pc) left else if (pb <= pc) up else upLeft
    }

    private fun asciiHex(data: ByteArray): ByteArray? {
        val output = ByteArrayOutputStream(data.size / 2)
        var high = -1
        for (byte in data) {
            val b = byte.toInt() and 0xFF
            if (b == '>'.code) {
                break
            }

            if (PdfLexer.isWhitespace(b)) {
                continue
            }

            if (!PdfLexer.isHexDigit(b)) {
                return null
            }

            if (high < 0) {
                high = PdfLexer.hexValue(b)
            } else {
                output.write((high shl 4) or PdfLexer.hexValue(b))
                high = -1
            }
        }

        if (high >= 0) {
            output.write(high shl 4)
        }

        return output.toByteArray()
    }

    private fun ascii85(data: ByteArray, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream(data.size)
        var value = 0L
        var count = 0
        for (byte in data) {
            val b = byte.toInt() and 0xFF
            if (PdfLexer.isWhitespace(b)) {
                continue
            }

            if (b == '~'.code) {
                break
            }

            if (b == 'z'.code && count == 0) {
                if (output.size() + 4 > limit) {
                    return null
                }

                output.write(ByteArray(4))
                continue
            }

            if (b < '!'.code || b > 'u'.code) {
                return null
            }

            value = (value * 85) + (b - '!'.code)
            if (++count == 5) {
                if (value > 0xFFFF_FFFFL) {
                    return null
                }

                output.write((value shr 24).toInt())
                output.write((value shr 16).toInt())
                output.write((value shr 8).toInt())
                output.write(value.toInt())
                value = 0
                count = 0
            }
        }

        if (count == 1) {
            return null
        }

        if (count > 1) {
            repeat(5 - count) {
                value = (value * 85) + 84
            }

            for (i in 0 until count - 1) {
                output.write((value shr (24 - (8 * i))).toInt())
            }
        }

        return output.toByteArray()
    }

    private fun runLength(data: ByteArray, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream(data.size * 2)
        var i = 0
        while (i < data.size) {
            val n = data[i++].toInt() and 0xFF
            if (n == 128) {
                break
            }

            if (output.size() + 257 > limit) {
                return null
            }

            if (n < 128) {
                if (i + n + 1 > data.size) {
                    return null
                }

                output.write(data, i, n + 1)
                i += n + 1
            } else {
                if (i >= data.size) {
                    return null
                }

                val repeated = data[i++].toInt()
                repeat(257 - n) {
                    output.write(repeated)
                }
            }
        }

        return output.toByteArray()
    }

    private fun lzw(data: ByteArray, earlyChange: Int, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val table = ArrayList<ByteArray>(4096)
        fun reset() {
            table.clear()
            for (i in 0 until 256) {
                table.add(byteArrayOf(i.toByte()))
            }

            table.add(ByteArray(0))
            table.add(ByteArray(0))
        }

        reset()
        var width = 9
        var buffer = 0
        var bits = 0
        var position = 0
        var previous: ByteArray? = null
        while (true) {
            while (bits < width) {
                if (position >= data.size) {
                    return output.toByteArray()
                }

                buffer = ((buffer shl 8) or (data[position++].toInt() and 0xFF)) and 0xFFFFFF
                bits += 8
            }

            val code = (buffer shr (bits - width)) and ((1 shl width) - 1)
            bits -= width
            if (code == 256) {
                reset()
                width = 9
                previous = null
                continue
            }

            if (code == 257) {
                return output.toByteArray()
            }

            val entry: ByteArray
            if (code < table.size && code > 257) {
                entry = table[code]
            } else if (code < 256) {
                entry = table[code]
            } else if (code == table.size && previous != null) {
                entry = previous + previous[0]
            } else {
                return null
            }

            output.write(entry)
            if (output.size() > limit) {
                return null
            }

            if (previous != null && table.size < 4096) {
                table.add(previous + entry[0])
            }

            previous = entry
            if (table.size + earlyChange >= (1 shl width) && width < 12) {
                width++
            }
        }
    }
}
