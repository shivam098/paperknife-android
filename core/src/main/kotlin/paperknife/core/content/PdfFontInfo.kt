package paperknife.core.content

import paperknife.core.fonts.Type1Font
import paperknife.core.support.DotNetEncoding
import paperknife.core.support.DotNetMath
import paperknife.core.support.Ref
import paperknife.core.support.strictNormalizedFormKC
import paperknife.core.syntax.CosArray
import paperknife.core.syntax.CosDict
import paperknife.core.syntax.CosName
import paperknife.core.syntax.CosNumber
import paperknife.core.syntax.CosObject
import paperknife.core.syntax.CosRef
import paperknife.core.syntax.CosStream
import paperknife.core.syntax.PdfFile
import paperknife.core.syntax.PdfLexer
import paperknife.core.syntax.PdfTokenKind
import kotlin.math.min

/**
 * What PaperKnife needs to know about a font to read and write its character codes: how codes
 * are split, their advance widths (as PDFium computes them), what text each code stands for, and,
 * for Type 3 fonts, which codes have a glyph.
 *
 * @property codeLength Bytes per character code: 1 for simple fonts, 2 for Identity-H fonts, 0 when not supported.
 */
internal class PdfFontInfo private constructor(val subtype: String, val codeLength: Int) {
    // Text by code from glyph names, for the codes the ToUnicode map lacks.
    private val namedText = LinkedHashMap<UInt, String>()
    private val glyphNames = LinkedHashMap<UInt, String>()

    // The codes the font's /Differences name.
    private val differenceCodes = LinkedHashSet<UInt>()
    private var charProcs: Set<String>? = null
    private var simpleWidths: IntArray? = null
    private var cidWidths: Map<UInt, Int>? = null
    private var defaultCidWidth = 1000

    // The ToUnicode map, shared with every font that uses it and never changed.
    private var toUnicode: Map<UInt, String>? = null

    // The codes for each text, built when an edit first needs them.
    private var codes: MutableMap<String, MutableList<UInt>>? = null

    val isType3: Boolean get() = subtype == "Type3"

    private constructor(subtype: String, codeLength: Int, defaultWidth: Int, widths: Map<UInt, Int>?) : this(subtype, codeLength) {
        defaultCidWidth = defaultWidth
        cidWidths = widths
    }

    private constructor(subtype: String, codeLength: Int, file: PdfFile, font: CosDict, builtinEncoding: ((Int) -> String?)?) :
        this(subtype, codeLength) {
        readSimpleFont(file, font, builtinEncoding)
    }

    private fun readSimpleFont(file: PdfFile, font: CosDict, builtinEncoding: ((Int) -> String?)?) {
        readGlyphNames(file, font, subtype, glyphNames, differenceCodes, builtinEncoding)
        if (subtype == "Type3") {
            charProcs = file.resolveDict(font["CharProcs"])?.entries?.keys?.toHashSet()
        }

        val widths = file.resolveArray(font["Widths"]) ?: return

        // Mirrors PDFium: Type 3 widths are scaled by the font matrix and rounded; other simple
        // fonts take whole numbers, and codes outside the array take /MissingWidth if present.
        val simpleWidths = IntArray(256) { -1 }
        this.simpleWidths = simpleWidths
        val first = (file.resolveNumber(font["FirstChar"]) ?: 0.0).toInt()
        if (first < 0 || first > 255) {
            return
        }

        if (subtype == "Type3") {
            val matrix = file.resolveArray(font["FontMatrix"])
            val scale = if (matrix != null && matrix.count == 6) file.resolveNumber(matrix[0]) ?: 0.001 else 0.001
            var i = 0
            while (i < widths.count && first + i < 256) {
                val width = DotNetMath.roundAwayFromZero((file.resolveNumber(widths[i]) ?: 0.0) * scale * 1000).toInt()
                simpleWidths[first + i] = if (width == 0) -1 else width
                i++
            }

            return
        }

        val missing = file.resolveNumber(file.resolveDict(font["FontDescriptor"])?.get("MissingWidth"))
        if (missing != null) {
            simpleWidths.fill(missing.toInt())
        }

        var last = (file.resolveNumber(font["LastChar"]) ?: 0.0).toInt()
        if (last == 0 || last >= first + widths.count) {
            last = first + widths.count - 1
        }

        for (code in first..min(last, 255)) {
            simpleWidths[code] = (file.resolveNumber(widths[code - first]) ?: 0.0).toInt()
        }
    }

    /** The advance width of a code for a font size of 1, or null when PaperKnife can't tell it. */
    fun width(code: UInt): Double? {
        val cidWidths = cidWidths
        if (cidWidths != null) {
            return (cidWidths[code] ?: defaultCidWidth) / 1000.0
        }

        val simpleWidths = simpleWidths
        return if (simpleWidths != null && code < 256u && simpleWidths[code.toInt()] >= 0) simpleWidths[code.toInt()] / 1000.0 else null
    }

    fun unicode(code: UInt): String? {
        val toUnicode = toUnicode
        return if (toUnicode != null && toUnicode.containsKey(code)) toUnicode[code] else namedText[code]
    }

    /** The name of the glyph a code selects, when the font selects glyphs by name and PaperKnife knows it. */
    fun glyphName(code: UInt): String? = glyphNames[code]

    /**
     * Whether the font itself says a code draws [text], with that glyph's width, for a font that
     * selects glyphs by name. It does for a code its /Differences name, or any code if it has none:
     * PDFium draws the glyph the PDF names. pdfTeX names only the codes a document uses, though; at
     * the others PDFium draws the glyph the program's own encoding gives them, if the subset has it
     * for another code or font, and pdfTeX's width and character map follow TeX's encoding. There
     * only the character map can vouch, when that glyph stands for the same character.
     */
    fun vouches(code: UInt, text: String): Boolean {
        if (differenceCodes.isEmpty() || differenceCodes.contains(code)) {
            return true
        }

        if (toUnicode?.containsKey(code) != true) {
            return false
        }

        val name = glyphName(code) ?: return false
        val own = Encodings.glyphToUnicode(name) ?: return false
        return comparable(own) == comparable(text)
    }

    /** Whether a code draws a glyph. Only Type 3 fonts can tell; for others it is checked elsewhere. */
    fun hasGlyph(code: UInt): Boolean {
        val charProcs = charProcs ?: return true
        val name = glyphNames[code] ?: return false
        return charProcs.contains(name)
    }

    fun splitCodes(bytes: ByteArray): MutableList<UInt> {
        val codes = ArrayList<UInt>(bytes.size)
        if (codeLength == 2) {
            var i = 0
            while (i + 1 < bytes.size) {
                codes.add((((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)).toUInt())
                i += 2
            }
        } else {
            for (b in bytes) {
                codes.add((b.toInt() and 0xFF).toUInt())
            }
        }

        return codes
    }

    fun joinCodes(codes: List<UInt>): ByteArray {
        val bytes = ByteArray(codes.size * codeLength)
        for (i in codes.indices) {
            if (codeLength == 2) {
                bytes[2 * i] = (codes[i] shr 8).toByte()
                bytes[(2 * i) + 1] = codes[i].toByte()
            } else {
                bytes[i] = codes[i].toByte()
            }
        }

        return bytes
    }

    /**
     * Character codes that draw [text], preferring codes already used nearby. Characters without
     * a code are reported in [missing].
     */
    fun encode(text: String, preferred: Set<UInt>, missing: Ref<MutableList<String>>): MutableList<UInt> {
        val codes = ArrayList<UInt>(text.length)
        val missingCharacters = ArrayList<String>()
        missing.value = missingCharacters
        for (c in text) {
            var candidates = codesFor(c.toString())
            if (candidates.isEmpty() && (c == ' ' || c == '\u00A0')) {
                // Fonts often map their one space glyph to only one of the two.
                candidates = codesFor(if (c == ' ') "\u00A0" else " ")
            }

            val usable = candidates.filter { code -> hasGlyph(code) }
            if (usable.isEmpty()) {
                if (!missingCharacters.contains(c.toString())) {
                    missingCharacters.add(c.toString())
                }

                continue
            }

            codes.add(choose(usable, preferred))
        }

        return codes
    }

    /** The lowest code that stands for [text], the one PDFium's reverse lookup finds. */
    fun firstCodeFor(text: String): UInt? = codesFor(text).firstOrNull()

    /** A code whose one glyph draws all of [text] (for example a ligature), preferring [preferred]. */
    fun codeFor(text: String, preferred: Set<UInt>): UInt? {
        val usable = codesFor(text).filter { code -> hasGlyph(code) }
        return if (usable.isEmpty()) null else choose(usable, preferred)
    }

    /**
     * Of the codes that stand for a text, one already used nearby, or else the lowest one the font
     * names itself (in /Differences or its character map) rather than through its program's own
     * encoding alone, whose width may be for another glyph (see [vouches]).
     */
    private fun choose(usable: List<UInt>, preferred: Set<UInt>): UInt {
        val named = usable.firstOrNull { c -> differenceCodes.contains(c) || toUnicode?.containsKey(c) == true } ?: usable[0]
        return usable.firstOrNull { c -> preferred.contains(c) } ?: named
    }

    private fun codesFor(text: String): List<UInt> {
        val index = codes ?: indexCodes().also { built -> codes = built }
        return index[text] ?: emptyList()
    }

    /** The codes that stand for each text, lowest first. Only edits need them, so viewing a page doesn't build them. */
    private fun indexCodes(): MutableMap<String, MutableList<UInt>> {
        val index = LinkedHashMap<String, MutableList<UInt>>()
        fun add(text: String, code: UInt) {
            index.getOrPut(text) { ArrayList() }.add(code)
        }

        val toUnicode = toUnicode
        val texts = if (toUnicode == null) namedText.entries.toList() else toUnicode.entries.toList() + namedText.entries
        for ((code, text) in texts.sortedBy { e -> e.key }) {
            add(text, code)

            // PDFium reads the ligatures U+FB00 to U+FB06 as their letters, for example "fi".
            val expanded = Encodings.expandLigatures(text)
            if (expanded != text) {
                add(expanded, code)
            }
        }

        return index
    }

    companion object {
        private const val MAX_MAPPED_CODES = 1 shl 17

        /**
         * @param builtinEncoding The glyph names of the font program's own encoding, when the caller
         *   knows them; otherwise they are read from an embedded Type 1 program when PDFium uses them.
         */
        fun load(file: PdfFile, font: CosDict, builtinEncoding: ((Int) -> String?)? = null): PdfFontInfo {
            val subtype = file.resolveName(font["Subtype"]) ?: ""
            var info: PdfFontInfo
            if (subtype == "Type0") {
                val encoding = file.resolve(font["Encoding"])
                val identity = encoding is CosName && encoding.value == "Identity-H"
                info = PdfFontInfo(subtype, if (identity) 2 else 0)
                val descendants = file.resolveArray(font["DescendantFonts"])
                val descendant = file.resolveDict(if (descendants != null && descendants.count > 0) descendants[0] else null)
                if (descendant != null) {
                    info = PdfFontInfo(
                        subtype,
                        if (identity) 2 else 0,
                        (file.resolveNumber(descendant["DW"]) ?: 1000.0).toInt(),
                        cidWidths(file, file.resolveArray(descendant["W"])),
                    )
                }
            } else {
                info = PdfFontInfo(subtype, 1, file, font, builtinEncoding)
            }

            info.toUnicode = toUnicode(file, font["ToUnicode"])
            for ((code, name) in info.glyphNames) {
                if (info.toUnicode?.containsKey(code) != true) {
                    val text = Encodings.glyphToUnicode(name)
                    if (text != null) {
                        info.namedText[code] = text
                    }
                }
            }

            return info
        }

        private fun readGlyphNames(
            file: PdfFile,
            font: CosDict,
            subtype: String,
            names: MutableMap<UInt, String>,
            differenceCodes: MutableSet<UInt>,
            builtin: ((Int) -> String?)?,
        ) {
            var baseEncoding: String? = null
            var differences: CosArray? = null
            val encoding = file.resolve(font["Encoding"])
            when (encoding) {
                is CosName -> baseEncoding = encoding.value
                is CosDict -> {
                    baseEncoding = file.resolveName(encoding["BaseEncoding"])
                    differences = file.resolveArray(encoding["Differences"])
                }
                else -> {}
            }

            val baseFont = file.resolveName(font["BaseFont"]) ?: ""
            val table = if (subtype == "Type1" || subtype == "MMType1") {
                type1BaseEncoding(file, font, encoding, baseFont, builtin)
            } else {
                predefined(baseEncoding) ?: if (baseEncoding == "StandardEncoding") Encodings::standardName else null
            }

            if (table != null) {
                for (code in 0 until 256) {
                    val name = table(code)
                    if (name != null) {
                        names[code.toUInt()] = name
                    }
                }
            }

            var next = 0
            for (item in differences?.items ?: emptyList()) {
                when (item) {
                    is CosNumber -> next = item.value.toInt()
                    is CosName -> if (next in 0 until 256) {
                        differenceCodes.add(next.toUInt())
                        names[(next++).toUInt()] = item.value
                    }
                    else -> {}
                }
            }
        }

        /**
         * The encoding PDFium starts from for a Type 1 font (cpdf_simplefont.cpp, LoadCommon and
         * LoadPDFEncoding): StandardEncoding for a nonsymbolic font, and for a symbolic one the
         * encoding of its embedded program, or WinAnsiEncoding when it isn't embedded. A named
         * encoding replaces that, except StandardEncoding, which PDFium ignores. Null when unknown.
         */
        private fun type1BaseEncoding(
            file: PdfFile,
            font: CosDict,
            encoding: CosObject?,
            baseFont: String,
            builtin: ((Int) -> String?)?,
        ): ((Int) -> String?)? {
            val descriptor = file.resolveDict(font["FontDescriptor"])
            if (baseFont.contains("Symbol") || baseFont.contains("Dingbats")) {
                // PDFium has encodings of its own for these.
                return predefined((encoding as? CosName)?.value)
            }

            val symbolic = ((file.resolveNumber(descriptor?.get("Flags")) ?: 0.0).toInt() and 4) != 0
            val embedded = descriptor != null && (descriptor["FontFile"] ?: descriptor["FontFile2"] ?: descriptor["FontFile3"]) != null
            when (encoding) {
                null -> if (!embedded && symbolic) {
                    return Encodings::winAnsiName
                }
                is CosName -> if (encoding.value in programEncodings) {
                    // PDFium reads MacExpertEncoding here as WinAnsiEncoding; it has no table of PDFDocEncoding names.
                    return if (encoding.value == "MacExpertEncoding") Encodings::winAnsiName else predefined(encoding.value)
                }
                is CosDict -> {
                    if (file.resolveName(encoding["BaseEncoding"]) in programEncodings) {
                        return predefined(file.resolveName(encoding["BaseEncoding"]))
                    }

                    if (!embedded && symbolic) {
                        return Encodings::standardName
                    }
                }
                else -> {}
            }

            // Fonts whose program PaperKnife can't read are taken to use StandardEncoding.
            return if (symbolic) builtin ?: embeddedEncoding(file, descriptor) ?: Encodings::standardName else Encodings::standardName
        }

        private val programEncodings = setOf("WinAnsiEncoding", "MacRomanEncoding", "MacExpertEncoding", "PDFDocEncoding")

        // Text as glyph names and character maps are compared (as FontCompletion does).
        private fun comparable(text: String): String = text.strictNormalizedFormKC.replace('\u00A0', ' ')

        private fun predefined(name: String?): ((Int) -> String?)? = when (name) {
            "WinAnsiEncoding" -> Encodings::winAnsiName
            "MacRomanEncoding" -> Encodings::macRomanName
            else -> null
        }

        /**
         * The builtin encoding of an embedded Type 1 program, from its cleartext part alone. Read once
         * per program, however many fonts share it.
         */
        private fun embeddedEncoding(file: PdfFile, descriptor: CosDict?): ((Int) -> String?)? {
            val entry = descriptor?.get("FontFile")
            fun read(): ((Int) -> String?)? {
                val stream = file.resolve(entry) as? CosStream ?: return null
                val length1 = file.resolveNumber(stream.dict["Length1"]) ?: return null
                if (length1 < 1 || length1 > Type1Font.MAX_CLEARTEXT) {
                    return null
                }

                val cleartext = file.decodeStreamPrefix(stream, length1.toInt(), Type1Font.MAX_PROGRAM) ?: return null
                val encoding = Type1Font.readEncoding(cleartext) ?: return null

                // A program that says it uses StandardEncoding gets every code of it, as in PDFium.
                return if (encoding.standard) Encodings::fullStandardName else { code -> encoding.names[code] }
            }

            return if (entry is CosRef) file.derived("Type1Encoding", entry.number, ::read) else read()
        }

        /** A ToUnicode map by code, read once per map, however many fonts share it. */
        fun toUnicode(file: PdfFile, entry: CosObject?): Map<UInt, String>? {
            fun read(): Map<UInt, String>? {
                val stream = file.resolve(entry) as? CosStream ?: return null
                val data = file.decodeStream(stream) ?: return null
                val map = LinkedHashMap<UInt, String>()
                readToUnicode(data, map)
                return map
            }

            return if (entry is CosRef) file.derived("ToUnicode", entry.number, ::read) else read()
        }

        /** The widths in a /W array, or null when it is too large to read, so they count as unknown. */
        private fun cidWidths(file: PdfFile, array: CosArray?): Map<UInt, Int>? {
            val widths = LinkedHashMap<UInt, Int>()
            if (array == null) {
                return widths
            }

            // Counts steps rather than entries, so repeated ranges can't make this run for minutes.
            var budget = 2 * MAX_MAPPED_CODES
            var i = 0
            while (i + 1 < array.count) {
                if (budget <= 0) {
                    return null
                }

                val first = (file.resolveNumber(array[i]) ?: 0.0).toUInt()
                val list = file.resolve(array[i + 1])
                if (list is CosArray) {
                    var j = 0
                    while (j < list.count && budget-- > 0) {
                        widths.putIfAbsent(first + j.toUInt(), (file.resolveNumber(list[j]) ?: 0.0).toInt())
                        j++
                    }

                    i += 2
                } else if (i + 2 < array.count) {
                    val last = (file.resolveNumber(array[i + 1]) ?: 0.0).toUInt()
                    val width = (file.resolveNumber(array[i + 2]) ?: 0.0).toInt()
                    if (last >= first && last - first < MAX_MAPPED_CODES.toUInt()) {
                        var k = 0u
                        while (k <= last - first && budget-- > 0) {
                            widths.putIfAbsent(first + k, width)
                            k++
                        }
                    }

                    i += 3
                } else {
                    break
                }
            }

            return widths
        }

        /** Reads the bfchar and bfrange sections of a ToUnicode CMap (ISO 32000-1, 9.10.3). */
        private fun readToUnicode(data: ByteArray, map: MutableMap<UInt, String>) {
            val lexer = PdfLexer(data)
            var budget = 2 * MAX_MAPPED_CODES
            while (budget > 0) {
                val token = lexer.next()
                if (token.kind == PdfTokenKind.End) {
                    return
                }

                if (token.isKeyword("beginbfchar")) {
                    while (true) {
                        val source = lexer.next()
                        if (source.kind != PdfTokenKind.String) {
                            break
                        }

                        val target = lexer.next()
                        if (target.kind == PdfTokenKind.String && source.bytes!!.size in 1..4) {
                            map.putIfAbsent(code(source.bytes), text(target.bytes!!))
                        }
                    }
                } else if (token.isKeyword("beginbfrange")) {
                    while (true) {
                        val low = lexer.next()
                        if (low.kind != PdfTokenKind.String) {
                            break
                        }

                        val high = lexer.next()
                        val target = lexer.next()
                        val from = code(low.bytes!!)
                        val to = if (high.kind == PdfTokenKind.String) code(high.bytes!!) else from
                        val valid = to >= from && to - from < MAX_MAPPED_CODES.toUInt() && low.bytes.size <= 4
                        if (target.kind == PdfTokenKind.String) {
                            val units = text(target.bytes!!).toCharArray()
                            var k = 0u
                            while (valid && units.isNotEmpty() && k <= to - from && budget-- > 0) {
                                val copy = units.clone()
                                copy[copy.size - 1] = (units[units.size - 1].code.toUInt() + k).toInt().toChar()
                                map.putIfAbsent(from + k, String(copy))
                                k++
                            }
                        } else if (target.kind == PdfTokenKind.ArrayStart) {
                            var code = from
                            var item = lexer.next()
                            while (item.kind != PdfTokenKind.ArrayEnd && item.kind != PdfTokenKind.End) {
                                if (valid && item.kind == PdfTokenKind.String && code <= to) {
                                    map.putIfAbsent(code, text(item.bytes!!))
                                }

                                code++
                                item = lexer.next()
                            }
                        }
                    }
                }
            }
        }

        private fun code(bytes: ByteArray): UInt {
            var code = 0u
            for (b in bytes) {
                code = (code shl 8) or (b.toInt() and 0xFF).toUInt()
            }

            return code
        }

        private fun text(utf16: ByteArray): String = DotNetEncoding.bigEndianUnicode(utf16)
    }
}
