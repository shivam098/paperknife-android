package paperknife.core.syntax

import java.io.ByteArrayOutputStream

/** A new version of an object, written by [IncrementalUpdate]. */
internal sealed class ObjectUpdate {
    abstract val number: Int
    abstract val generation: Int
}

/**
 * A new version of a stream object, with its decoded data. It is written compressed, with
 * [entries] (for example a font program's /Length1) added to its dictionary.
 */
internal data class StreamUpdate(
    override val number: Int,
    override val generation: Int,
    val data: ByteArray,
    val entries: Map<String, CosObject>? = null,
) : ObjectUpdate()

/** A new version of an object that isn't a stream, such as a dictionary. */
internal data class ValueUpdate(override val number: Int, override val generation: Int, val value: CosObject) : ObjectUpdate()

/**
 * Appends an incremental update to a PDF (ISO 32000-1, 7.5.6): new versions of some objects and a
 * cross-reference section pointing to them. Every byte of the original file stays as it was.
 */
internal object IncrementalUpdate {
    fun append(file: PdfFile, updates: List<ObjectUpdate>): ByteArray {
        val output = ByteArrayOutputStream(file.data.size + updates.filterIsInstance<StreamUpdate>().sumOf { u -> u.data.size / 2 } + 1024)
        output.write(file.data)
        val last = file.data[file.data.size - 1].toInt()
        if (last != '\n'.code && last != '\r'.code) {
            write(output, "\n")
        }

        val entries = ArrayList<Entry>()
        for (update in updates.sortedBy { u -> u.number }) {
            entries.add(Entry(update.number, update.generation, output.size().toLong()))
            val text = StringBuilder()
            text.append(update.number).append(' ').append(update.generation).append(" obj\n")
            when (update) {
                is StreamUpdate -> {
                    val data = StreamFilters.deflate(update.data)
                    text.append("<</Length ").append(data.size).append("/Filter/FlateDecode")
                    for ((key, value) in update.entries ?: emptyMap()) {
                        CosWriter.writeName(text, key)
                        text.append(' ')
                        CosWriter.write(text, value)
                    }

                    text.append(">>\nstream\n")
                    write(output, text.toString())
                    output.write(data)
                    write(output, "\nendstream\nendobj\n")
                }
                is ValueUpdate -> {
                    CosWriter.write(text, update.value)
                    text.append("\nendobj\n")
                    write(output, text.toString())
                }
            }
        }

        val highest = entries.maxOfOrNull { e -> e.number } ?: throw IllegalStateException("Sequence contains no elements")
        val size = maxOf(file.size, highest + 1)
        if (file.hasXrefStream) {
            writeXrefStream(output, file, entries, size)
        } else {
            writeXrefTable(output, file, entries, size)
        }

        return output.toByteArray()
    }

    private fun writeXrefTable(output: ByteArrayOutputStream, file: PdfFile, entries: List<Entry>, size: Int) {
        val start = output.size().toLong()
        val text = StringBuilder("xref\n")
        for ((number, generation, offset) in entries) {
            text.append(number).append(" 1\n").append(padded(offset, 10)).append(' ').append(padded(generation.toLong(), 5)).append(" n\r\n")
        }

        text.append("trailer\n")
        CosWriter.write(text, trailer(file, size))
        text.append("\nstartxref\n").append(start).append("\n%%EOF\n")
        write(output, text.toString())
    }

    private fun writeXrefStream(output: ByteArrayOutputStream, file: PdfFile, entries: List<Entry>, size: Int) {
        // The cross-reference stream is a new object, listed in itself.
        val number = size
        val start = output.size().toLong()
        val all = entries + Entry(number, 0, start)
        val offsetWidth = if (all.maxOf { e -> e.offset } > 0xFFFF_FFFFL) 8 else 4
        val data = ByteArray(all.size * (1 + offsetWidth + 2))
        var at = 0
        for ((_, generation, offset) in all) {
            data[at++] = 1
            for (i in offsetWidth - 1 downTo 0) {
                data[at++] = (offset shr (8 * i)).toByte()
            }

            data[at++] = (generation shr 8).toByte()
            data[at++] = generation.toByte()
        }

        val dict = trailer(file, number + 1)
        dict.entries["Type"] = CosName("XRef")
        dict.entries["Index"] = CosArray(all.flatMap { e -> listOf(integer(e.number.toLong()), integer(1)) }.toMutableList())
        dict.entries["W"] = CosArray(mutableListOf(integer(1), integer(offsetWidth.toLong()), integer(2)))
        dict.entries["Length"] = integer(data.size.toLong())
        val text = StringBuilder()
        text.append(number).append(" 0 obj\n")
        CosWriter.write(text, dict)
        text.append("\nstream\n")
        write(output, text.toString())
        output.write(data)
        write(output, "\nendstream\nendobj\nstartxref\n$start\n%%EOF\n")
    }

    private fun trailer(file: PdfFile, size: Int): CosDict {
        val dict = CosDict(linkedMapOf("Size" to integer(size.toLong())))
        for (key in arrayOf("Root", "Info", "ID")) {
            val value = file.trailer[key]
            if (value != null) {
                dict.entries[key] = value
            }
        }

        dict.entries["Prev"] = CosNumber(file.startXref.toDouble(), file.startXref.toString())
        return dict
    }

    private fun integer(value: Long): CosNumber = CosNumber(value.toDouble(), value.toString())

    private fun write(output: ByteArrayOutputStream, text: String) = output.write(text.toByteArray(Charsets.ISO_8859_1))

    /** C#'s "D" format: at least [digits] digits, after the sign. */
    private fun padded(value: Long, digits: Int): String =
        if (value < 0) "-" + (-value).toString().padStart(digits, '0') else value.toString().padStart(digits, '0')

    private data class Entry(val number: Int, val generation: Int, val offset: Long)
}
