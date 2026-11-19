package paperknife.core.fonts

import paperknife.core.content.ContentPage
import paperknife.core.content.Encodings
import paperknife.core.content.PdfFontInfo
import paperknife.core.support.DotNetMath
import paperknife.core.support.Ref
import paperknife.core.support.strictNormalizedFormC
import paperknife.core.support.strictNormalizedFormKC
import paperknife.core.syntax.CosArray
import paperknife.core.syntax.CosDict
import paperknife.core.syntax.CosName
import paperknife.core.syntax.CosNumber
import paperknife.core.syntax.CosObject
import paperknife.core.syntax.CosRef
import paperknife.core.syntax.CosStream
import paperknife.core.syntax.CosWriter
import paperknife.core.syntax.ObjectUpdate
import paperknife.core.syntax.PdfFile
import paperknife.core.syntax.StreamUpdate
import paperknife.core.syntax.ValueUpdate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A complete copy of a subset font, for new text that needs letters the subset lacks. TeX embeds
 * only the glyphs a document uses. When PaperKnife ships the font the subset was made from (see
 * [BundledFonts]), the edited text is drawn with a copy of the PDF's font that embeds the whole,
 * unmodified font program, added to the edited page as a font of its own. The PDF's own font, and
 * everything drawn with it on any page, stays exactly as it was.
 *
 * The copy keeps the font's character map and the glyph of every code its /Differences name or
 * the page's text draws with it; the subset must be identical to PaperKnife's font for every
 * glyph it has (see [Type1Font.differenceFrom]), so the letters an edit keeps look and read the
 * same. Other codes select the glyph the character map says they stand for (see [glyphNames]),
 * and codes take the PDF's widths only where the PDF says they are those glyphs' (see
 * [copyWidths]). Only symbolic fonts whose codes select glyphs through the program's own encoding
 * (and /Differences) qualify, as TeX's fonts do: there, PDFium and the PDF specification agree on
 * which glyph each code draws.
 */
internal class FontCompletion private constructor(
    private val contentPage: ContentPage,
    val full: Type1Font,
    /** The name of the copy in the page's font resources, which the edited text selects. */
    val resourceName: String,
    /** The copy's character codes and widths. */
    val info: PdfFontInfo,
    /** Whether the page already has such a copy, from an earlier edit, so nothing needs to be added. */
    val reused: Boolean,
    private val glyphNames: Map<Int, String>,
    // The codes whose glyph is the PDF's own, which stand for what its character map says.
    private val trusted: Set<Int>,
    private val fontEntries: MutableMap<String, CosObject>,
    private val descriptorEntries: MutableMap<String, CosObject>,
    firstNumber: Int,
) {
    private val programNumber = firstNumber
    private val descriptorNumber = firstNumber + 1
    private val fontNumber = firstNumber + 2

    /** The font's name without its subset tag, for example "CMBX10". */
    val fontName: String get() = full.fontName

    /**
     * Whether the copy draws the glyph for [text] for [code]. A glyph that is the PDF's own (one it
     * names or the page draws, or any it didn't remap in a font without /Differences) stands for
     * what the PDF's character map says; any other is judged by its name.
     */
    fun draws(code: UInt, text: String): Boolean {
        val name = glyphNames[code.toInt()] ?: return false
        return full.charStrings.containsKey(name) && (trusted.contains(code.toInt()) || standsFor(name, text))
    }

    /**
     * The objects to append: the font program, the copy's descriptor and font dictionary, and a
     * new version of the page that lists the copy among its fonts. Nothing when the copy exists.
     */
    fun updates(): List<ObjectUpdate> {
        if (reused) {
            return emptyList()
        }

        val file = contentPage.file
        val page = contentPage.page
        val fonts = LinkedHashMap(file.resolveDict(page.resources?.get("Font"))!!.entries)
        fonts[resourceName] = CosRef(fontNumber, 0)

        // The page gets resources of its own, so pages that share or inherit them don't change.
        val resources = LinkedHashMap(page.resources!!.entries)
        resources["Font"] = CosDict(fonts)
        val pageEntries = LinkedHashMap(page.dict.entries)
        pageEntries["Resources"] = CosDict(resources)
        return listOf(
            StreamUpdate(
                programNumber,
                0,
                full.program,
                linkedMapOf(
                    "Length1" to integer(full.length1),
                    "Length2" to integer(full.length2),
                    "Length3" to integer(full.length3),
                ),
            ),
            ValueUpdate(descriptorNumber, 0, CosDict(descriptorEntries)),
            ValueUpdate(fontNumber, 0, CosDict(fontEntries)),
            ValueUpdate(page.number, page.generation, CosDict(pageEntries)),
        )
    }

    /** What [qualify] finds, as C#'s tuple (Type1Font Full, CosDict Descriptor, int Flags). */
    private class Qualified(val full: Type1Font, val descriptor: CosDict, val flags: Int)

    /** What [describes] finds, as C#'s tuple (CosDict Descriptor, int Flags). */
    private class Description(val descriptor: CosDict, val flags: Int)

    /** The copy's /Widths, as C#'s tuple (int First, List<CosNumber> Values). */
    private class Widths(val first: Int, val values: List<CosNumber>)

    companion object {
        private const val SYMBOLIC = 4
        private const val NONSYMBOLIC = 32
        private const val ALL_CAP = 1 shl 16
        private const val MAX_NAME_ATTEMPTS = 32

        /**
         * A complete copy of the font the page's resource [resourceName] names, or null when
         * PaperKnife can't make one. [difference] says why when PaperKnife ships a font of that name
         * but can't use it.
         */
        fun tryCreate(page: ContentPage, resourceName: String, difference: Ref<String?>): FontCompletion? {
            difference.value = null
            val file = page.file
            val fonts = file.resolveDict(page.page.resources?.get("Font"))
            val entry = fonts?.get(resourceName) ?: return null
            val font = file.resolveDict(entry) ?: return null
            val qualified = qualify(file, font, difference) ?: return null
            val full = qualified.full
            val descriptor = qualified.descriptor
            val flags = qualified.flags

            val embedded = readProgram(file, descriptor)
            if (embedded == null) {
                difference.value = "it couldn't read the font in this PDF"
                return null
            }

            val reason = embedded.differenceFrom(full)
            if (reason != null) {
                difference.value = "in this PDF $reason"
                return null
            }

            val differences = differences(file, font)
            val toUnicode = PdfFontInfo.toUnicode(file, font["ToUnicode"])
            val drawn = codesDrawn(page, resourceName)
            val glyphNames = glyphNames(full, differences, toUnicode, drawn)

            // The codes whose glyph is the PDF's own: those its /Differences name and those the page draws,
            // or, when it has no /Differences, every code the copy doesn't give another glyph.
            val trusted = if (differences.isNotEmpty()) {
                (drawn + differences.keys).toHashSet()
            } else {
                (0 until 256).filter { code -> glyphNames[code] == builtin(full, code) }.toHashSet()
            }

            val widths = copyWidths(file, font, full, embedded, glyphNames, differences, toUnicode) ?: return null
            val encoding = copyEncoding(font, full, differences, glyphNames)

            // A copy an earlier edit added to this page serves as well, if it is exactly what this one would be.
            val programs = HashMap<Int, Boolean>()
            for ((name, value) in fonts.entries) {
                if (name != resourceName) {
                    val other = file.resolveDict(value)
                    if (other != null && isSameCopy(file, other, font, encoding, flags, full, widths, programs)) {
                        return FontCompletion(
                            page, full, name, PdfFontInfo.load(file, other) { code -> builtin(full, code) }, reused = true,
                            glyphNames, trusted, LinkedHashMap(), LinkedHashMap(), 0,
                        )
                    }
                }
            }

            // The copy takes the next three object numbers, which must stay within those PDFium reads.
            if (file.size > PdfFile.MAX_OBJECT_NUMBER - 2) {
                difference.value = "the PDF has too many objects to add it"
                return null
            }

            val resource = freeName(page, fonts, "PK${full.fontName}") ?: return null
            val number = file.size
            val baseFont = "${tag(if (entry is CosRef) entry.number else 0)}+${full.fontName}"
            val descriptorEntries = LinkedHashMap(descriptor.entries)
            descriptorEntries["FontName"] = CosName(baseFont)
            descriptorEntries["FontFile"] = CosRef(number, 0)
            descriptorEntries.remove("CharSet")

            val fontEntries = linkedMapOf<String, CosObject>(
                "Type" to CosName("Font"),
                "Subtype" to CosName("Type1"),
                "BaseFont" to CosName(baseFont),
                "FirstChar" to integer(widths.first),
                "LastChar" to integer(widths.first + widths.values.size - 1),
                "Widths" to CosArray(widths.values.toMutableList()),
            )
            if (encoding != null) {
                fontEntries["Encoding"] = encoding
            }

            val map = font["ToUnicode"]
            if (map != null) {
                fontEntries["ToUnicode"] = map
            }

            // The copy as PdfFontInfo reads it, with its descriptor inline: the new objects don't exist yet.
            val readable = LinkedHashMap(fontEntries)
            readable["FontDescriptor"] = CosDict(descriptorEntries)
            fontEntries["FontDescriptor"] = CosRef(number + 1, 0)
            val info = PdfFontInfo.load(file, CosDict(readable)) { code -> builtin(full, code) }
            return FontCompletion(page, full, resource, info, reused = false, glyphNames, trusted, fontEntries, descriptorEntries, number)
        }

        /**
         * Whether the font the page's resource [resourceName] names selects its glyphs by name in a
         * way PaperKnife reads exactly: a symbolic Type 1 font, embedded as a Type 1 program, whose
         * codes select glyphs through the program's own encoding and /Differences, as pdfTeX's fonts
         * do. Its glyph names then say which glyph each code draws, in PDFium as in PaperKnife,
         * whether or not PaperKnife ships the font.
         */
        fun selectsGlyphsByName(page: ContentPage, resourceName: String): Boolean {
            val file = page.file
            val font = file.resolveDict(file.resolveDict(page.page.resources?.get("Font"))?.get(resourceName)) ?: return false
            if (file.resolveName(font["Subtype"]) != "Type1") {
                return false
            }

            val descriptor = describes(file, font)?.descriptor ?: return false
            return descriptor["FontFile"] != null && descriptor["FontFile2"] == null && descriptor["FontFile3"] == null
        }

        /** Whether a glyph name stands for [text] (see [comparable]). */
        private fun standsFor(name: String, text: String): Boolean {
            val unicode = Encodings.glyphToUnicode(name) ?: return false
            return comparable(unicode) == comparable(text)
        }

        /** Whether the font has no /Encoding, or one that only lists /Differences, so its program's encoding is the base. */
        private fun hasProgramEncoding(file: PdfFile, font: CosDict): Boolean {
            if (font["Encoding"] == null) {
                return true
            }

            val encoding = file.resolve(font["Encoding"])
            return encoding is CosDict && encoding.entries.keys.all { k -> k == "Type" || k == "Differences" }
        }

        private fun readProgram(file: PdfFile, descriptor: CosDict): Type1Font? {
            if (descriptor["FontFile2"] != null || descriptor["FontFile3"] != null) {
                return null
            }

            val stream = file.resolve(descriptor["FontFile"]) as? CosStream ?: return null
            val length1 = file.resolveNumber(stream.dict["Length1"]) ?: return null
            if (length1 < 1 || length1 > Type1Font.MAX_PROGRAM) {
                return null
            }

            val length2 = file.resolveNumber(stream.dict["Length2"]) ?: return null
            if (length2 < 1 || length2 > Type1Font.MAX_PROGRAM) {
                return null
            }

            val data = file.decodeStream(stream, null, Type1Font.MAX_PROGRAM) ?: return null
            return Type1Font.fromEmbedded(data, length1.toInt(), length2.toInt())
        }

        /** The glyph names the font's /Differences give codes, in order, as PDFium reads them. */
        private fun differences(file: PdfFile, font: CosDict): Map<Int, String> {
            val names = LinkedHashMap<Int, String>()
            var next = 0
            for (item in file.resolveArray(file.resolveDict(font["Encoding"])?.get("Differences"))?.items ?: emptyList()) {
                when (val value = file.resolve(item)) {
                    is CosNumber -> next = value.value.toInt()
                    is CosName -> if (next in 0 until 256) {
                        names[next++] = value.value
                    }
                    else -> {}
                }
            }

            return names
        }

        /**
         * The glyph each code of the copy selects. Every code the page's text draws with the font, and
         * every code its /Differences name, keeps its glyph: the copy is only for this page, and the
         * letters an edit keeps are drawn with it, with the codes they had. pdfTeX names only the codes
         * a document uses, though its character map (and its widths) follow TeX's encoding for every
         * code; at the others the full program draws the glyphs of its own encoding, which differs.
         * So when the character map says such a code stands for a character the program's glyph
         * isn't, the copy selects the program's glyph for exactly that character, if there is one.
         *
         * @param drawn The codes the page's text draws with the font.
         */
        private fun glyphNames(full: Type1Font, differences: Map<Int, String>, toUnicode: Map<UInt, String>?, drawn: Set<Int>): Map<Int, String> {
            val names = LinkedHashMap<Int, String>()
            var byText: Map<String, String>? = null
            for (code in 0 until 256) {
                var name = differences[code] ?: builtin(full, code)
                if (!differences.containsKey(code) && !drawn.contains(code)) {
                    val text = toUnicode?.get(code.toUInt())
                    if (text != null && text.isNotEmpty() && !(name != null && full.charStrings.containsKey(name) && standsFor(name, text))) {
                        val glyphs = byText ?: glyphsByText(full).also { built -> byText = built }
                        val glyph = glyphs[text.strictNormalizedFormC]
                        if (glyph != null) {
                            name = glyph
                        }
                    }
                }

                if (name != null) {
                    names[code] = name
                }
            }

            return names
        }

        /**
         * The codes the page's text draws with the font a resource names, under that name or any
         * other the page gives the same font.
         */
        private fun codesDrawn(page: ContentPage, resourceName: String): Set<Int> {
            val font = page.text.shows.firstOrNull { s -> s.fontName == resourceName }?.font
            return page.text.shows.filter { s -> s.font != null && (s.fontName == resourceName || s.font === font) }
                .flatMap { s -> s.codes() }.map { c -> c.code.toInt() }.toHashSet()
        }

        /**
         * The full program, descriptor and flags of a font PaperKnife may make a copy of: a Type 1 font
         * it ships, symbolic, whose /Encoding is absent or lists only /Differences. Null otherwise;
         * [difference] then says why when PaperKnife ships a font of that name.
         */
        private fun qualify(file: PdfFile, font: CosDict, difference: Ref<String?>): Qualified? {
            difference.value = null
            if (file.resolveName(font["Subtype"]) != "Type1") {
                return null
            }

            val full = BundledFonts.findType1(Type1Font.baseName(file.resolveName(font["BaseFont"]) ?: "")) ?: return null
            val description = describes(file, font)
            if (description == null) {
                difference.value = "the PDF describes the font in a way PaperKnife can't add letters to yet"
                return null
            }

            return Qualified(full, description.descriptor, description.flags)
        }

        /**
         * The descriptor and flags of a symbolic font whose /Encoding is absent or lists only
         * /Differences, so that its codes select glyphs through its program's own encoding. Null for
         * any other font.
         */
        private fun describes(file: PdfFile, font: CosDict): Description? {
            val descriptor = file.resolveDict(font["FontDescriptor"])
            val flags = (file.resolveNumber(descriptor?.get("Flags")) ?: 0.0).toInt()
            return if (descriptor == null || (flags and SYMBOLIC) == 0 || (flags and (NONSYMBOLIC or ALL_CAP)) != 0 || !hasProgramEncoding(file, font)) {
                null
            } else {
                Description(descriptor, flags)
            }
        }

        /**
         * The full program's glyphs by the exact text they stand for: a look-alike, such as ε for ϵ,
         * won't do. Variants (names with a suffix, such as "a.sc") are left out: a character map can't
         * ask for them. The first name in ordinal order wins, so the copy is the same every time.
         */
        private fun glyphsByText(full: Type1Font): Map<String, String> {
            val glyphs = HashMap<String, String>()
            for (name in full.charStrings.keys.filter { n -> !n.contains('.') }.sorted()) {
                val text = Encodings.glyphToUnicode(name)
                if (text != null && text.isNotEmpty()) {
                    glyphs.putIfAbsent(text.strictNormalizedFormC, name)
                }
            }

            return glyphs
        }

        /**
         * The copy's /Encoding: the font's own when the copy selects the same glyphs as it would, or
         * else its /Differences with the codes [glyphNames] gave other glyphs.
         */
        private fun copyEncoding(font: CosDict, full: Type1Font, differences: Map<Int, String>, names: Map<Int, String>): CosObject? {
            val added = names.entries.filter { n -> !differences.containsKey(n.key) && n.value != builtin(full, n.key) }
            if (added.isEmpty()) {
                return font["Encoding"]
            }

            val items = ArrayList<CosObject>()
            var previous = -2
            for ((code, name) in (differences.entries.toList() + added).sortedBy { n -> n.key }) {
                if (code != previous + 1) {
                    items.add(integer(code))
                }

                items.add(CosName(name))
                previous = code
            }

            return CosDict(linkedMapOf("Type" to CosName("Encoding"), "Differences" to CosArray(items)))
        }

        /**
         * The copy's /Widths. A code keeps the font's width when the PDF says it is that of the glyph
         * the copy selects: /Differences names the glyph, or the font has none, so its codes select
         * its program's glyphs; or the character map says the code stands for the glyph's character,
         * as pdfTeX gives every code the width of TeX's glyph for it. Other codes take the glyph's
         * width in the full program, so new letters advance as the font intends: some tools give the
         * glyphs they leave out of a subset a width of 0, and pdfTeX's widths may be for another glyph.
         */
        private fun copyWidths(
            file: PdfFile,
            font: CosDict,
            full: Type1Font,
            embedded: Type1Font,
            glyphNames: Map<Int, String>,
            differences: Map<Int, String>,
            toUnicode: Map<UInt, String>?,
        ): Widths? {
            val array = file.resolveArray(font["Widths"])
            if (array == null || array.count <= 0) {
                return null
            }

            val firstChar = file.resolveNumber(font["FirstChar"]) ?: return null
            if (firstChar < 0 || firstChar > 255) {
                return null
            }

            // The codes /Widths covers, as PdfFontInfo and PDFium read them.
            val first = firstChar.toInt()
            val lastChar = (file.resolveNumber(font["LastChar"]) ?: 0.0).toInt()
            val last = min(255, if (lastChar == 0 || lastChar >= first + array.count) first + array.count - 1 else lastChar)
            val drawn = glyphNames.entries.filter { g -> full.charStrings.containsKey(g.value) }.map { g -> g.key }
            val low = if (drawn.isNotEmpty()) min(first, drawn.min()) else first
            val high = if (drawn.isNotEmpty()) max(last, drawn.max()) else last
            val values = ArrayList<CosNumber>()
            for (code in low..high) {
                val name = glyphNames[code]
                val own = if (code >= first && code <= last) file.resolve(array[code - first]) as? CosNumber ?: integer(0) else null
                val keep = if (own == null) {
                    false
                } else if (differences.isEmpty() || differences.containsKey(code)) {
                    // A glyph the subset has keeps its width, even 0: the PDF's text is drawn with it.
                    own.value.toInt() > 0 || name == null || embedded.charStrings.containsKey(name)
                } else {
                    val text = toUnicode?.get(code.toUInt())
                    own.value.toInt() > 0 && name != null && text != null && standsFor(name, text)
                }

                if (keep) {
                    values.add(own!!)
                } else {
                    val width = if (name == null) null else full.width(name)
                    values.add(integer(if (width != null && width.isFinite()) DotNetMath.round(width).toInt() else 0))
                }
            }

            return Widths(low, values)
        }

        /**
         * Whether a font of the page is exactly the copy this completion would add: the same full
         * program, encoding, character map and flags, and the same widths for every code the copy covers.
         *
         * @param encoding The copy's /Encoding (see [copyEncoding]).
         * @param programs Font programs already compared with the full one, by object number.
         */
        private fun isSameCopy(
            file: PdfFile,
            other: CosDict,
            font: CosDict,
            encoding: CosObject?,
            flags: Int,
            full: Type1Font,
            widths: Widths,
            programs: MutableMap<Int, Boolean>,
        ): Boolean {
            val descriptor = file.resolveDict(other["FontDescriptor"])
            if (file.resolveName(other["Subtype"]) != "Type1" || Type1Font.baseName(file.resolveName(other["BaseFont"]) ?: "") != full.fontName ||
                descriptor == null || (file.resolveNumber(descriptor["Flags"]) ?: 0.0).toInt() != flags ||
                !sameEntry(file, other["Encoding"], encoding) || !sameEntry(file, other["ToUnicode"], font["ToUnicode"]) ||
                descriptor["FontFile2"] != null || descriptor["FontFile3"] != null ||
                !isFullProgram(file, descriptor["FontFile"], full, programs)
            ) {
                return false
            }

            val theirs = readWidths(file, other)
            for (i in widths.values.indices) {
                val width = theirs[widths.first + i]
                if (width == null || width != widths.values[i].value.toInt()) {
                    return false
                }
            }

            return true
        }

        /**
         * Whether an embedded program is exactly the full font's. Each stream is decoded once, and
         * no further than the full program's length.
         */
        private fun isFullProgram(file: PdfFile, entry: CosObject?, full: Type1Font, programs: MutableMap<Int, Boolean>): Boolean {
            if (entry is CosRef) {
                programs[entry.number]?.let { known -> return known }
            }

            val stream = file.resolve(entry) as? CosStream
            val same = stream != null &&
                file.resolveNumber(stream.dict["Length1"]) == full.length1.toDouble() &&
                file.resolveNumber(stream.dict["Length2"]) == full.length2.toDouble() &&
                file.decodeStream(stream, null, full.program.size)?.contentEquals(full.program) == true
            if (entry is CosRef) {
                programs[entry.number] = same
            }

            return same
        }

        // Whole-number widths by code, as PDFium reads /Widths.
        private fun readWidths(file: PdfFile, font: CosDict): Map<Int, Int> {
            val widths = HashMap<Int, Int>()
            val array = file.resolveArray(font["Widths"]) ?: return widths
            val first = file.resolveNumber(font["FirstChar"])
            if (first != null && first >= 0 && first <= 255) {
                val lastChar = (file.resolveNumber(font["LastChar"]) ?: 0.0).toInt()
                val last = min(255, if (lastChar == 0 || lastChar >= first + array.count) first.toInt() + array.count - 1 else lastChar)
                for (code in first.toInt()..last) {
                    widths[code] = (file.resolveNumber(array[code - first.toInt()]) ?: 0.0).toInt()
                }
            }

            return widths
        }

        // The same object, or the same value.
        private fun sameEntry(file: PdfFile, a: CosObject?, b: CosObject?): Boolean {
            if (a == null || b == null) {
                return a == null && b == null
            }

            if (a is CosRef && b is CosRef) {
                return a.number == b.number && a.generation == b.generation
            }

            val first = file.resolve(a)
            val second = if (first != null && first !is CosStream) file.resolve(b) else null
            return first != null && first !is CosStream && second != null && second !is CosStream &&
                CosWriter.toText(first) == CosWriter.toText(second)
        }

        /** A name for the copy that neither the page's fonts nor its content use yet. */
        private fun freeName(page: ContentPage, fonts: CosDict, stem: String): String? {
            val used = page.ops.flatMap { op -> op.operands.asList() }.filterIsInstance<CosName>().map { n -> n.value }.toHashSet()
            for (attempt in 0 until MAX_NAME_ATTEMPTS) {
                val name = if (attempt == 0) stem else "${stem}_$attempt"
                if (fonts[name] == null && !used.contains(name)) {
                    return name
                }
            }

            return null
        }

        /** A subset tag for the copy, "PK" and four letters from the original font's object number. */
        private fun tag(objectNumber: Int): String {
            var number = objectNumber
            val letters = CharArray(4)
            for (i in 3 downTo 0) {
                letters[i] = ('A'.code + (abs(number) % 26)).toChar()
                number /= 26
            }

            return "PK" + String(letters)
        }

        /** The glyph a font program's own encoding gives a code. */
        private fun builtin(font: Type1Font, code: Int): String? =
            if (font.usesStandardEncoding) Encodings.fullStandardName(code) else font.encoding[code]

        private fun comparable(text: String): String = text.strictNormalizedFormKC.replace('\u00A0', ' ')

        private fun integer(value: Int): CosNumber = CosNumber(value.toDouble(), value.toString())
    }
}
