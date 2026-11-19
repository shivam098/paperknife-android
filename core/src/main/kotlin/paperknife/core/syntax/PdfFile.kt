package paperknife.core.syntax

import paperknife.core.support.Ref
import kotlin.math.max
import kotlin.math.min

/** Where an object lives: free (type 0), at an offset (1), or inside an object stream (2). */
internal data class XrefEntry(val type: Int, val offset: Long, val generation: Int, val streamNumber: Int, val index: Int)

/** A page of the page tree, with the resources it inherits. */
internal data class PdfPageNode(val number: Int, val generation: Int, val dict: CosDict, val resources: CosDict?)

/**
 * A read-only view of a PDF file's structure (ISO 32000-1, 7.5): cross-reference tables and
 * streams, the trailer, indirect objects, object streams and the page tree. It reads only what it
 * is asked for, and returns null for anything it doesn't fully understand instead of guessing the
 * way PDFium's recovery does; callers then leave the file to PDFium.
 */
internal class PdfFile private constructor(val data: ByteArray) {
    private val xref = HashMap<Int, XrefEntry>()
    private val objects = HashMap<Int, CosObject?>()
    private val objectStreams = HashMap<Int, ObjectStream?>()
    internal val derivedValues = HashMap<Pair<String, Int>, Any?>()
    private val loading = HashSet<Int>()

    /** The newest trailer dictionary (for a cross-reference stream, its dictionary). */
    var trailer = CosDict(LinkedHashMap())
        private set

    /** The offset of the newest cross-reference section, which an update's /Prev names. */
    var startXref = 0L
        private set

    /** Whether the newest cross-reference section is a stream rather than a table. */
    var hasXrefStream = false
        private set

    /** One more than the highest object number in use. */
    var size = 0
        private set

    val isEncrypted: Boolean get() = trailer["Encrypt"] != null

    fun getObject(number: Int): CosObject? {
        if (objects.containsKey(number)) {
            return objects[number]
        }

        // A chain of references (a stream length, an object stream) mustn't recurse without limit.
        if (loading.size > MAX_NESTING || !loading.add(number)) {
            return null
        }

        try {
            var value: CosObject? = null
            val entry = xref[number]
            if (entry != null) {
                value = when (entry.type) {
                    1 -> readObjectAt(entry.offset)?.let { read -> if (read.number == number) read.value else null }
                    2 -> readFromObjectStream(entry.streamNumber, entry.index, number)
                    else -> null
                }
            }

            objects[number] = value
            return value
        } finally {
            loading.remove(number)
        }
    }

    /** Follows references; null for null objects, missing objects and reference loops. */
    fun resolve(value: CosObject?): CosObject? {
        var current = value
        var depth = 0
        while (current is CosRef && depth < 32) {
            current = getObject(current.number)
            depth++
        }

        return if (current is CosNull || current is CosRef) null else current
    }

    fun resolveDict(value: CosObject?): CosDict? = resolve(value) as? CosDict

    fun resolveArray(value: CosObject?): CosArray? = resolve(value) as? CosArray

    fun resolveNumber(value: CosObject?): Double? = (resolve(value) as? CosNumber)?.value

    fun resolveName(value: CosObject?): String? = (resolve(value) as? CosName)?.value

    /**
     * A value derived from an indirect object, such as a font's character map, computed once
     * however many resource names, fonts or pages refer to the object, until [forgetDerived]. A
     * reader kept open while pages are viewed calls that to bound what it keeps.
     */
    inline fun <reified T : Any> derived(kind: String, number: Int, compute: () -> T?): T? {
        val key = kind to number
        val value: Any?
        if (derivedValues.containsKey(key)) {
            value = derivedValues[key]
        } else {
            value = compute()
            derivedValues[key] = value
        }

        return value as? T
    }

    /** Forgets the values [derived] computed; they are computed again when needed. */
    fun forgetDerived() = derivedValues.clear()

    /**
     * The first [length] bytes of the stream's decoded data, or null when it is shorter or can't be
     * decoded. Flate data is decoded only that far.
     */
    fun decodeStreamPrefix(stream: CosStream, length: Int, limit: Int): ByteArray? {
        val filter = resolve(stream.dict["Filter"])
        if (stream.dict["F"] == null && stream.dict["DecodeParms"] == null &&
            (filter == null || (filter is CosName && (filter.value == "FlateDecode" || filter.value == "Fl")))
        ) {
            val prefix = if (filter == null) {
                data.copyOfRange(stream.dataStart, stream.dataStart + min(length, stream.dataLength))
            } else {
                StreamFilters.inflatePrefix(data, stream.dataStart, stream.dataLength, length)
            }

            return if (prefix != null && prefix.size == length) prefix else null
        }

        val decoded = decodeStream(stream, null, limit)
        return if (decoded != null && decoded.size >= length) decoded.copyOf(length) else null
    }

    /** The stream's decoded data, or null when it can't be decoded or would be longer than [limit] bytes. */
    fun decodeStream(stream: CosStream, problem: Ref<String?>? = null, limit: Int = StreamFilters.MAX_DECODED_LENGTH): ByteArray? {
        val filters = ArrayList<String>()
        val parameters = ArrayList<CosDict?>()
        val filter = resolve(stream.dict["Filter"])
        val decodeParameters = resolve(stream.dict["DecodeParms"])
        if (stream.dict["F"] != null) {
            problem?.value = "The stream's data is in an external file."
            return null
        }

        when (filter) {
            null -> {}
            is CosName -> {
                filters.add(filter.value)
                parameters.add(decodeParameters as? CosDict)
            }
            is CosArray -> {
                for (i in 0 until filter.count) {
                    val name = resolve(filter[i]) as? CosName
                    if (name == null) {
                        problem?.value = "The stream has an unknown filter."
                        return null
                    }

                    filters.add(name.value)
                    parameters.add(if (decodeParameters is CosArray && i < decodeParameters.count) resolveDict(decodeParameters[i]) else null)
                }
            }
            else -> {
                problem?.value = "The stream has an unknown filter."
                return null
            }
        }

        return StreamFilters.decode(data, stream.dataStart, stream.dataLength, filters, parameters, problem, limit)
    }

    /** The pages in document order, or null when the page tree isn't sound. */
    fun getPages(): List<PdfPageNode>? {
        val catalog = resolveDict(trailer["Root"]) ?: return null
        val root = catalog["Pages"] as? CosRef ?: return null
        val pages = ArrayList<PdfPageNode>()
        return if (collectPages(root, null, pages, HashSet(), 0)) pages else null
    }

    /** The page's content streams, in order, or null when /Contents isn't a stream or an array of streams. */
    fun getContentStreams(page: CosDict): List<CosRef>? {
        var contents = page["Contents"] ?: return emptyList()
        if (contents is CosRef) {
            when (val target = getObject(contents.number)) {
                is CosStream -> return listOf(contents)
                is CosArray -> contents = target
                else -> return null
            }
        }

        if (contents !is CosArray) {
            return null
        }

        val streams = ArrayList<CosRef>()
        for (item in contents.items) {
            if (item !is CosRef || getObject(item.number) !is CosStream) {
                return null
            }

            streams.add(item)
        }

        return streams
    }

    private fun collectPages(node: CosRef, inherited: CosDict?, pages: MutableList<PdfPageNode>, visited: MutableSet<Int>, depth: Int): Boolean {
        if (depth > 64 || !visited.add(node.number)) {
            return false
        }

        val dict = getObject(node.number) as? CosDict ?: return false
        val own = dict["Resources"]
        val resources = if (own != null) resolveDict(own) else inherited
        val kids = resolveArray(dict["Kids"])
        val type = resolveName(dict["Type"])
        if (type == "Pages" || (type != "Page" && kids != null)) {
            return kids != null && kids.items.all { kid -> kid is CosRef && collectPages(kid, resources, pages, visited, depth + 1) }
        }

        pages.add(PdfPageNode(node.number, node.generation, dict, resources))
        return true
    }

    private fun readCrossReferences(): String? {
        val tail = max(0, data.size - 4096)
        val at = lastIndexOf(data, tail, STARTXREF)
        if (at < 0) {
            return "The file has no cross-reference offset."
        }

        val offset = PdfLexer(data, tail + at + 9).next()
        if (offset.kind != PdfTokenKind.Number || offset.number < 0) {
            return "The file's cross-reference offset is damaged."
        }

        startXref = offset.number.toLong()
        val pending = ArrayDeque<Long>()
        pending.addLast(startXref)
        val visited = HashSet<Long>()
        var newest = true
        while (pending.isNotEmpty()) {
            val position = pending.removeFirst()
            if (position < 0 || position >= data.size || !visited.add(position) || visited.size > MAX_SECTIONS) {
                return "The file's cross-reference sections are damaged."
            }

            val isStream = !PdfLexer(data, position.toInt()).peek().isKeyword("xref")
            val read = Ref<CosDict?>(null)
            var problem = if (isStream) readXrefStream(position, read) else readXrefTable(position, read)
            if (problem != null) {
                return problem
            }

            val trailer = read.value!!
            if (newest) {
                this.trailer = trailer
                hasXrefStream = isStream
                newest = false
            }

            val hybrid = trailer["XRefStm"]
            if (!isStream && hybrid is CosNumber) {
                problem = readXrefStream(hybrid.value.toLong(), null)
                if (problem != null) {
                    return problem
                }
            }

            val prev = trailer["Prev"]
            if (prev is CosNumber) {
                pending.addLast(prev.value.toLong())
            }
        }

        val s = trailer["Size"]
        val size = if (s is CosNumber && s.isInteger && s.value >= 0 && s.value <= Int.MAX_VALUE) s.value.toLong() else 0L
        this.size = min(Int.MAX_VALUE.toLong(), max(size, if (xref.isEmpty()) 1L else xref.keys.max() + 1L)).toInt()
        return if (trailer["Root"] is CosRef) null else "The file has no catalog."
    }

    private fun readXrefTable(position: Long, trailer: Ref<CosDict?>): String? {
        trailer.value = null
        val lexer = PdfLexer(data, position.toInt())
        lexer.next()
        while (true) {
            val first = lexer.next()
            if (first.isKeyword("trailer")) {
                break
            }

            val count = lexer.next()
            if (!isCount(first) || !isCount(count)) {
                return "The file's cross-reference table is damaged."
            }

            var i = 0
            while (i < count.number.toInt()) {
                val offset = lexer.next()
                val generation = lexer.next()
                val kind = lexer.next()
                if (!isCount(offset) || !isCount(generation) || kind.kind != PdfTokenKind.Keyword || (kind.text != "n" && kind.text != "f")) {
                    return "The file's cross-reference table is damaged."
                }

                xref.putIfAbsent(
                    first.number.toInt() + i,
                    if (kind.text == "n") {
                        XrefEntry(1, offset.number.toLong(), generation.number.toInt(), 0, 0)
                    } else {
                        XrefEntry(0, 0, generation.number.toInt(), 0, 0)
                    },
                )
                i++
            }
        }

        trailer.value = PdfObjectParser.read(lexer) as? CosDict
        return if (trailer.value == null) "The file's trailer is damaged." else null
    }

    private fun readXrefStream(position: Long, trailer: Ref<CosDict?>?): String? {
        trailer?.value = null
        val stream = if (position < 0 || position >= data.size) null else readObjectAt(position)?.value as? CosStream
        if (stream == null || resolveName(stream.dict["Type"]) != "XRef") {
            return "The file's cross-reference stream is damaged."
        }

        val problem = Ref<String?>(null)
        val decoded = decodeStream(stream, problem) ?: return problem.value

        val w = stream.dict["W"]
        if (w !is CosArray || w.count != 3 || w.items.any { x -> !(x is CosNumber && x.value >= 0 && x.value <= 8) }) {
            return "The file's cross-reference stream is damaged."
        }

        val widths = w.items.map { x -> (x as CosNumber).value.toInt() }.toIntArray()
        val s = stream.dict["Size"]
        val size = if (s is CosNumber) s.value.toInt() else 0
        val i = stream.dict["Index"]
        val index = if (i is CosArray) i.items.map { x -> if (x is CosNumber) x.value.toInt() else -1 }.toIntArray() else intArrayOf(0, size)
        val entrySize = widths.sum()
        if (entrySize == 0) {
            return "The file's cross-reference stream is damaged."
        }

        var at = 0
        var pair = 0
        while (pair + 1 < index.size) {
            if (index[pair] < 0 || index[pair + 1] < 0) {
                return "The file's cross-reference stream is damaged."
            }

            for (n in 0 until index[pair + 1]) {
                if (at + entrySize > decoded.size) {
                    return "The file's cross-reference stream is damaged."
                }

                val type = if (widths[0] == 0) 1L else field(decoded, at, widths[0])
                val second = field(decoded, at + widths[0], widths[1])
                val third = field(decoded, at + widths[0] + widths[1], widths[2])
                at += entrySize
                val number = index[pair] + n
                when (type) {
                    0L -> xref.putIfAbsent(number, XrefEntry(0, 0, third.toInt(), 0, 0))
                    1L -> xref.putIfAbsent(number, XrefEntry(1, second, third.toInt(), 0, 0))
                    2L -> xref.putIfAbsent(number, XrefEntry(2, 0, 0, second.toInt(), third.toInt()))
                }
            }

            pair += 2
        }

        trailer?.value = stream.dict
        return null
    }

    /** Reads "n g obj ... endobj" at an offset; null when it isn't one or a stream's length is wrong. */
    private fun readObjectAt(position: Long): ReadObject? {
        if (position < 0 || position >= data.size) {
            return null
        }

        val lexer = PdfLexer(data, position.toInt())
        val number = lexer.next()
        val generation = lexer.next()
        if (!isCount(number) || !isCount(generation) || !lexer.next().isKeyword("obj")) {
            return null
        }

        var value = PdfObjectParser.read(lexer) ?: return null
        if (value is CosDict && lexer.peek().isKeyword("stream")) {
            var start = lexer.next().end
            if (start < data.size && data[start].toInt() == '\r'.code) {
                start++
            }

            if (start < data.size && data[start].toInt() == '\n'.code) {
                start++
            }

            val length = resolveNumber(value["Length"])
            if (length == null || length < 0 || start + length > data.size ||
                !PdfLexer(data, start + length.toInt()).next().isKeyword("endstream")
            ) {
                return null
            }

            value = CosStream(value, start, length.toInt())
        }

        return ReadObject(number.number.toInt(), generation.number.toInt(), value)
    }

    private fun readFromObjectStream(streamNumber: Int, index: Int, number: Int): CosObject? {
        val parsed: ObjectStream?
        if (objectStreams.containsKey(streamNumber)) {
            parsed = objectStreams[streamNumber]
        } else {
            parsed = parseObjectStream(streamNumber)
            objectStreams[streamNumber] = parsed
        }

        val objects = parsed ?: return null
        val i = if (index >= 0 && index < objects.numbers.size && objects.numbers[index] == number) index else objects.numbers.indexOf(number)
        return if (i < 0) null else PdfObjectParser.read(PdfLexer(objects.data, objects.first + objects.offsets[i]))
    }

    private fun parseObjectStream(number: Int): ObjectStream? {
        // Like PDFium, an object stream must itself be an ordinary object, not inside another one.
        val entry = xref[number]
        if (entry == null || entry.type != 1) {
            return null
        }

        val stream = getObject(number) as? CosStream ?: return null
        if (resolveName(stream.dict["Type"]) != "ObjStm") {
            return null
        }

        val count = resolveNumber(stream.dict["N"]) ?: return null
        val first = resolveNumber(stream.dict["First"]) ?: return null
        if (count < 0 || first < 0) {
            return null
        }

        val decoded = decodeStream(stream) ?: return null
        if (count > decoded.size / 4) {
            return null
        }

        val lexer = PdfLexer(decoded)
        val numbers = IntArray(count.toInt())
        val offsets = IntArray(count.toInt())
        for (i in numbers.indices) {
            val objectNumber = lexer.next()
            val offset = lexer.next()
            if (!isCount(objectNumber) || !isCount(offset) || first + offset.number >= decoded.size) {
                return null
            }

            numbers[i] = objectNumber.number.toInt()
            offsets[i] = offset.number.toInt()
        }

        return ObjectStream(decoded, first.toInt(), numbers, offsets)
    }

    private class ReadObject(val number: Int, val generation: Int, val value: CosObject)

    private class ObjectStream(val data: ByteArray, val first: Int, val numbers: IntArray, val offsets: IntArray)

    companion object {
        /** The highest object number PDFium accepts (cpdf_parser.h, kMaxObjectNumber). */
        const val MAX_OBJECT_NUMBER = 24 * 1024 * 1024

        private const val MAX_SECTIONS = 1000
        private const val MAX_NESTING = 32
        private val STARTXREF = "startxref".toByteArray(Charsets.ISO_8859_1)

        fun tryOpen(data: ByteArray, problem: Ref<String?>? = null): PdfFile? {
            val file = PdfFile(data)
            val found = file.readCrossReferences()
            problem?.value = found
            return if (found == null) file else null
        }

        private fun field(data: ByteArray, start: Int, width: Int): Long {
            var value = 0L
            for (i in 0 until width) {
                value = (value shl 8) or (data[start + i].toLong() and 0xFF)
            }

            return value
        }

        private fun isCount(token: PdfToken): Boolean =
            token.kind == PdfTokenKind.Number && token.number >= 0 && !token.text.contains('.')

        /** Where [pattern] last occurs in [data] from [from] on, relative to [from], or -1. */
        private fun lastIndexOf(data: ByteArray, from: Int, pattern: ByteArray): Int {
            var i = data.size - pattern.size
            while (i >= from) {
                var j = 0
                while (j < pattern.size && data[i + j] == pattern[j]) {
                    j++
                }

                if (j == pattern.size) {
                    return i - from
                }

                i--
            }

            return -1
        }
    }
}
