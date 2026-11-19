package paperknife.core.fonts

import paperknife.core.content.Encodings
import paperknife.core.support.DotNetParse
import paperknife.core.support.Ref
import paperknife.core.support.finiteDouble
import paperknife.core.support.signedInt32
import java.io.ByteArrayOutputStream

/**
 * A Type 1 font program (Adobe Type 1 Font Format, 1990), read far enough to tell whether a subset
 * was made from a given full font: its builtin encoding, its decrypted glyph programs
 * (charstrings) and subroutines, and the values that change how glyphs render. Reading is strict:
 * anything outside the usual structure (a repeated key, an unexpected token among the glyphs, a
 * glyph or subroutine defined twice, a multiple master font) makes it refuse the font rather
 * than guess how a renderer would read it. It runs in linear time on bounded input.
 */
internal class Type1Font private constructor(
    val fontName: String,
    val fontMatrix: List<Double>,
    val paintType: Int,
    /** The number of random bytes before each glyph program; -1 when they aren't encrypted. */
    val lenIV: Int,
    /** The builtin encoding: glyph names by code. Empty when the font uses StandardEncoding. */
    val encoding: Map<Int, String>,
    val usesStandardEncoding: Boolean,
    /** Glyph programs by glyph name, decrypted, without the leading random bytes. */
    val charStrings: Map<String, ByteArray>,
    val subrs: Map<Int, ByteArray>,
    val renderingValues: Map<String, String>,
    /** The program the way a /FontFile stream holds it: cleartext, then the encrypted part, then the trailer. */
    val program: ByteArray,
    val length1: Int,
    val length2: Int,
    val length3: Int,
) {
    /** The scale from glyph space to text space, normally 0.001. */
    val scale: Double get() = if (fontMatrix[0] > 0) fontMatrix[0] else 0.001

    /** The builtin encoding of a program: whether it is StandardEncoding, and otherwise the glyph names by code, as C#'s tuple. */
    data class BuiltinEncoding(val standard: Boolean, val names: Map<Int, String>)

    /** The advance width of a glyph in text space units (1000 per em for most fonts), or null. */
    fun width(glyph: String): Double? {
        val program = charStrings[glyph] ?: return null

        // The width comes first, from hsbw (sbx wx) or sbw (sbx sby wx wy), possibly computed with div.
        val stack = ArrayList<Double>()
        var i = 0
        while (i < program.size) {
            val v = program[i++].toInt() and 0xFF
            if (v >= 32) {
                val at = Ref(i)
                val number = CharStringAnalyzer.readNumber(program, v, at)
                i = at.value
                if (number == null || stack.size >= 24) {
                    return null
                }

                stack.add(number)
                continue
            }

            when {
                v == 13 -> return if (stack.size >= 2) stack[1] * scale * 1000 else null
                v == 12 && i < program.size -> {
                    val escaped = program[i++].toInt() and 0xFF
                    if (escaped == 7) {
                        return if (stack.size >= 3) stack[2] * scale * 1000 else null
                    }

                    if (escaped == 12 && stack.size >= 2 && stack[stack.size - 1] != 0.0) {
                        val quotient = stack[stack.size - 2] / stack[stack.size - 1]
                        stack.subList(stack.size - 2, stack.size).clear()
                        stack.add(quotient)
                        continue
                    }

                    return null
                }
                else -> return null
            }
        }

        return null
    }

    /**
     * Why this font, a subset, isn't part of [full], or null when it is: same name, matrix, paint
     * type, glyph encryption and rendering values; every glyph it has is identical in [full], and so
     * is every subroutine those glyphs call, directly or not (subsetters replace the subroutines
     * nothing calls by stubs); the glyphs an accented glyph is built from are there too; and its
     * encoding maps codes to the same glyphs. Then every glyph it can draw renders the same with
     * [full].
     */
    fun differenceFrom(full: Type1Font): String? {
        if (baseName(fontName) != baseName(full.fontName)) {
            return "it has a different name"
        }

        if (!sameNumbers(fontMatrix, full.fontMatrix) || paintType != full.paintType) {
            return "its size or style differs"
        }

        if (lenIV != full.lenIV) {
            return "its glyphs are stored differently"
        }

        for (key in RENDERING_KEYS) {
            if (renderingValues[key] != full.renderingValues[key]) {
                return "its /$key differs"
            }
        }

        if (usesStandardEncoding != full.usesStandardEncoding || encoding.any { e -> e.value != ".notdef" && full.encoding[e.key] != e.value }) {
            return "its encoding differs"
        }

        val budget = CharStringAnalyzer.Budget(MAX_STEPS)
        for ((name, program) in charStrings) {
            val other = full.charStrings[name]
            if (other == null || !other.contentEquals(program)) {
                return "its glyph “$name” differs"
            }

            val uses = CharStringAnalyzer.analyze(program, subrs, budget) ?: return "PaperKnife couldn't follow its glyph “$name”"
            for (index in uses.subrs) {
                val subroutine = full.subrs[index]
                if (subroutine == null || !subroutine.contentEquals(subrs.getValue(index))) {
                    return "its subroutine $index differs"
                }
            }

            for (code in uses.components) {
                val component = Encodings.standardName(code)
                if (component == null || !charStrings.containsKey(component)) {
                    return "its glyph “$name” is built from a glyph it doesn't have"
                }
            }
        }

        return null
    }

    private class Cleartext(
        val name: String,
        val matrix: DoubleArray,
        val paintType: Int,
        val standardEncoding: Boolean,
        val encoding: Map<Int, String>,
    )

    companion object {
        /** The largest cleartext part and whole program PaperKnife reads; real fonts are far smaller. */
        const val MAX_CLEARTEXT = 256 * 1024

        const val MAX_PROGRAM = 4 * 1024 * 1024

        private const val EEXEC_KEY = 55665
        private const val CHAR_STRING_KEY = 4330
        private const val MAX_ENTRIES = 65536
        private const val MAX_TOKENS = 1 shl 20
        private const val MAX_STEPS = 4_000_000

        // Private dictionary values that change how glyphs render (hinting). Fonts that differ in them
        // aren't the same font, even if their outlines match.
        private val RENDERING_KEYS = linkedSetOf(
            "BlueValues", "OtherBlues", "FamilyBlues", "FamilyOtherBlues", "BlueScale", "BlueShift", "BlueFuzz",
            "StdHW", "StdVW", "StemSnapH", "StemSnapV", "ForceBold", "LanguageGroup", "RndStemUp", "ExpansionFactor",
        )

        /** Reads a font in the PFB format Type 1 fonts are distributed in. */
        fun fromPfb(pfb: ByteArray): Type1Font? {
            if (pfb.size > MAX_PROGRAM + 64) {
                return null
            }

            val clear = ByteArrayOutputStream()
            val encrypted = ByteArrayOutputStream()
            val trailer = ByteArrayOutputStream()
            var at = 0
            while (at + 6 <= pfb.size && (pfb[at].toInt() and 0xFF) == 0x80 && (pfb[at + 1].toInt() == 1 || pfb[at + 1].toInt() == 2)) {
                val length = (pfb[at + 2].toInt() and 0xFF) or ((pfb[at + 3].toInt() and 0xFF) shl 8) or
                    ((pfb[at + 4].toInt() and 0xFF) shl 16) or ((pfb[at + 5].toInt() and 0xFF) shl 24)
                if (length < 0 || length > pfb.size - at - 6) {
                    return null
                }

                val target = if (pfb[at + 1].toInt() == 2) encrypted else if (encrypted.size() == 0) clear else trailer
                target.write(pfb, at + 6, length)
                at += 6 + length
            }

            return parse(clear.toByteArray(), encrypted.toByteArray(), trailer.toByteArray())
        }

        /** Reads a font embedded in a PDF (/FontFile), whose first two parts have the lengths /Length1 and /Length2. */
        fun fromEmbedded(data: ByteArray, length1: Int, length2: Int): Type1Font? {
            if (data.size > MAX_PROGRAM || length1 <= 0 || length2 <= 0 || length1 > data.size || length2 > data.size - length1) {
                return null
            }

            return parse(
                data.copyOfRange(0, length1),
                data.copyOfRange(length1, length1 + length2),
                data.copyOfRange(length1 + length2, data.size),
            )
        }

        /**
         * The builtin encoding of a program from its cleartext part alone: whether it is StandardEncoding,
         * and otherwise the glyph names by code. Null when it can't be read.
         */
        fun readEncoding(cleartext: ByteArray): BuiltinEncoding? =
            readCleartext(cleartext)?.let { header -> BuiltinEncoding(header.standardEncoding, header.encoding) }

        /** The PostScript name a program gives itself in its cleartext part, or null when it can't be read. */
        fun readFontName(cleartext: ByteArray): String? = readCleartext(cleartext)?.name

        /** A font name without the six-letter subset tag ("ABCDEF+CMR10" is "CMR10"). */
        fun baseName(name: String): String =
            if (name.length > 7 && name[6] == '+' && name.substring(0, 6).all { c -> c in 'A'..'Z' }) name.substring(7) else name

        /**
         * Type 1 decryption (section 7.1) of [length] bytes of [data] from [offset]: eexec uses key
         * 55665, charstrings 4330; [skip] leading bytes are random.
         */
        internal fun decrypt(data: ByteArray, offset: Int, length: Int, key: Int, skip: Int): ByteArray {
            val plain = ByteArray(length)
            var r = key
            for (i in 0 until length) {
                val c = data[offset + i].toInt() and 0xFF
                plain[i] = (c xor (r shr 8)).toByte()
                r = ((c + r) * 52845 + 22719) and 0xFFFF
            }

            return if (skip >= plain.size) ByteArray(0) else plain.copyOfRange(skip, plain.size)
        }

        private fun parse(clear: ByteArray, encrypted: ByteArray, trailer: ByteArray): Type1Font? {
            if (clear.size > MAX_CLEARTEXT || clear.size + encrypted.size + trailer.size > MAX_PROGRAM) {
                return null
            }

            val header = readCleartext(clear) ?: return null
            val cipher = if (isHex(encrypted)) fromHex(encrypted) else encrypted
            val plain = decrypt(cipher, 0, cipher.size, EEXEC_KEY, 4)
            val tokens = PostScriptTokenizer.tokenize(plain, MAX_TOKENS, "closefile") ?: return null

            // Each key once, and /lenIV before the glyphs it applies to, as renderers read them in order.
            var lenIVAt = -1
            var subrsAt = -1
            var charStringsAt = -1
            var lenIV = 4
            val values = LinkedHashMap<String, String>()
            for (k in tokens.indices) {
                if (tokens[k].kind != PsTokenKind.Name) {
                    continue
                }

                when (val key = tokens[k].text(plain)) {
                    "/lenIV" -> {
                        val iv = if (k + 1 < tokens.size) signedInteger(plain, tokens[k + 1]) else null
                        if (lenIVAt >= 0 || iv == null || iv < -1 || iv > 32) {
                            return null
                        }

                        lenIVAt = k
                        lenIV = iv
                    }
                    "/Subrs" -> {
                        if (subrsAt >= 0) {
                            return null
                        }

                        subrsAt = k
                    }
                    "/CharStrings" -> {
                        if (charStringsAt >= 0) {
                            return null
                        }

                        charStringsAt = k
                    }
                    else -> if (RENDERING_KEYS.contains(key.substring(1))) {
                        if (values.containsKey(key.substring(1))) {
                            return null
                        }

                        values[key.substring(1)] = readValue(plain, tokens, k + 1)
                    }
                }
            }

            if (charStringsAt < 0 || (lenIVAt >= 0 && (lenIVAt > charStringsAt || (subrsAt >= 0 && lenIVAt > subrsAt)))) {
                return null
            }

            val subrs = if (subrsAt < 0) LinkedHashMap() else readSubrs(plain, tokens, subrsAt + 1, lenIV)
            val charStrings = readCharStrings(plain, tokens, charStringsAt + 1, lenIV)
            if (subrs == null || charStrings == null) {
                return null
            }

            return Type1Font(
                fontName = header.name,
                fontMatrix = header.matrix.toList(),
                paintType = header.paintType,
                lenIV = lenIV,
                encoding = header.encoding,
                usesStandardEncoding = header.standardEncoding,
                charStrings = charStrings,
                subrs = subrs,
                renderingValues = values,
                program = clear + encrypted + trailer,
                length1 = clear.size,
                length2 = encrypted.size,
                length3 = trailer.size,
            )
        }

        private fun readCleartext(clear: ByteArray): Cleartext? {
            if (clear.size > MAX_CLEARTEXT) {
                return null
            }

            val tokens = PostScriptTokenizer.tokenize(clear, MAX_TOKENS, "eexec") ?: return null
            var name: String? = null
            var matrix: DoubleArray? = null
            var paintType: Int? = null
            var encoding: BuiltinEncoding? = null
            for (k in tokens.indices) {
                if (tokens[k].kind != PsTokenKind.Name) {
                    continue
                }

                when (tokens[k].text(clear)) {
                    "/FontName" -> {
                        if (name != null || k + 1 >= tokens.size || tokens[k + 1].kind != PsTokenKind.Name) {
                            return null
                        }

                        name = tokens[k + 1].text(clear).substring(1)
                    }
                    "/FontMatrix" -> {
                        if (matrix != null) {
                            return null
                        }

                        matrix = readMatrix(clear, tokens, k + 1) ?: return null
                    }
                    "/PaintType" -> {
                        if (paintType != null || k + 1 >= tokens.size) {
                            return null
                        }

                        paintType = tokens[k + 1].integer(clear) ?: return null
                    }
                    "/FontType" -> if (k + 1 >= tokens.size || tokens[k + 1].integer(clear) != 1) {
                        return null
                    }
                    "/Encoding" -> {
                        if (encoding != null) {
                            return null
                        }

                        encoding = readBuiltinEncoding(clear, tokens, k + 1) ?: return null
                    }
                    "/WeightVector", "/BlendDesignPositions", "/BlendDesignMap", "/BlendAxisTypes", "/Blend" ->
                        // Multiple master fonts render through instances PaperKnife doesn't model.
                        return null
                }
            }

            return if (name == null || matrix == null || encoding == null) {
                null
            } else {
                Cleartext(name, matrix, paintType ?: 0, encoding.standard, encoding.names)
            }
        }

        // [a b c d e f]
        private fun readMatrix(data: ByteArray, tokens: List<PsToken>, k: Int): DoubleArray? {
            if (k + 7 >= tokens.size || tokens[k].kind != PsTokenKind.ArrayStart || tokens[k + 7].kind != PsTokenKind.ArrayEnd) {
                return null
            }

            val matrix = DoubleArray(6)
            for (i in 0 until 6) {
                val token = tokens[k + 1 + i]
                if (token.kind != PsTokenKind.Regular) {
                    return null
                }

                matrix[i] = DotNetParse.finiteDouble(token.text(data)) ?: return null
            }

            return matrix
        }

        // "StandardEncoding def", or "256 array ... dup <code> /<name> put ... def" (with anything that
        // isn't a name in between, such as the loop that fills the array with .notdef).
        private fun readBuiltinEncoding(data: ByteArray, tokens: List<PsToken>, k: Int): BuiltinEncoding? {
            val names = LinkedHashMap<Int, String>()
            if (k < tokens.size && tokens[k].`is`(data, "StandardEncoding")) {
                return BuiltinEncoding(true, names)
            }

            if (k + 1 >= tokens.size || tokens[k].integer(data) == null || !tokens[k + 1].`is`(data, "array")) {
                return null
            }

            var j = k + 2
            while (j < tokens.size) {
                if (tokens[j].`is`(data, "def")) {
                    return BuiltinEncoding(false, names)
                }

                val code = if (tokens[j].`is`(data, "dup") && j + 3 < tokens.size) tokens[j + 1].integer(data) else null
                if (code != null && code < 256 && tokens[j + 2].kind == PsTokenKind.Name && tokens[j + 3].`is`(data, "put")) {
                    names[code] = tokens[j + 2].text(data).substring(1)
                    j += 3
                } else if (tokens[j].kind == PsTokenKind.Name) {
                    return null
                }

                j++
            }

            return null
        }

        // "count array", then "dup <index> <length> RD <binary> NP" for each.
        private fun readSubrs(data: ByteArray, tokens: List<PsToken>, k: Int, lenIV: Int): MutableMap<Int, ByteArray>? {
            val count = if (k + 1 < tokens.size) tokens[k].integer(data) else null
            if (count == null || count > MAX_ENTRIES || !tokens[k + 1].`is`(data, "array")) {
                return null
            }

            val subrs = LinkedHashMap<Int, ByteArray>()
            var j = k + 2
            while (j < tokens.size && tokens[j].`is`(data, "dup")) {
                if (j + 5 >= tokens.size) {
                    return null
                }

                val index = tokens[j + 1].integer(data)
                if (index == null || index >= count) {
                    return null
                }

                val program = readProgram(data, tokens, j + 2, lenIV)
                if (program == null || subrs.containsKey(index)) {
                    return null
                }

                subrs[index] = program
                j += 5
                if (tokens[j].`is`(data, "NP") || tokens[j].`is`(data, "|")) {
                    j++
                } else if (j + 1 < tokens.size && tokens[j].`is`(data, "noaccess") && tokens[j + 1].`is`(data, "put")) {
                    j += 2
                } else {
                    return null
                }
            }

            return subrs
        }

        // "count dict dup begin", then "/<name> <length> RD <binary> ND" for each, then "end".
        private fun readCharStrings(data: ByteArray, tokens: List<PsToken>, k: Int, lenIV: Int): MutableMap<String, ByteArray>? {
            val count = if (k + 3 < tokens.size) tokens[k].integer(data) else null
            if (count == null || count > MAX_ENTRIES || !tokens[k + 1].`is`(data, "dict") || !tokens[k + 2].`is`(data, "dup") ||
                !tokens[k + 3].`is`(data, "begin")
            ) {
                return null
            }

            val glyphs = LinkedHashMap<String, ByteArray>()
            var j = k + 4
            while (j < tokens.size && !tokens[j].`is`(data, "end")) {
                if (tokens[j].kind != PsTokenKind.Name || j + 4 >= tokens.size) {
                    return null
                }

                val program = readProgram(data, tokens, j + 1, lenIV) ?: return null
                val name = tokens[j].text(data).substring(1)
                if (glyphs.containsKey(name)) {
                    return null
                }

                glyphs[name] = program
                if (glyphs.size > count) {
                    return null
                }

                j += 4
                if (tokens[j].`is`(data, "ND") || tokens[j].`is`(data, "|-")) {
                    j++
                } else if (j + 1 < tokens.size && tokens[j].`is`(data, "noaccess") && tokens[j + 1].`is`(data, "def")) {
                    j += 2
                } else {
                    return null
                }
            }

            return if (j < tokens.size && glyphs.isNotEmpty()) glyphs else null
        }

        // "<length> RD <binary>", decrypted.
        private fun readProgram(data: ByteArray, tokens: List<PsToken>, k: Int, lenIV: Int): ByteArray? {
            val length = tokens[k].integer(data)
            val binary = tokens[k + 2]
            if (length == null || !(tokens[k + 1].`is`(data, "RD") || tokens[k + 1].`is`(data, "-|")) ||
                binary.kind != PsTokenKind.Binary || binary.length != length
            ) {
                return null
            }

            return if (lenIV < 0) {
                data.copyOfRange(binary.start, binary.start + binary.length)
            } else {
                decrypt(data, binary.start, binary.length, CHAR_STRING_KEY, lenIV)
            }
        }

        // The tokens of a value up to the operator that stores it, such as "[ -22 0 683 705 ]".
        private fun readValue(data: ByteArray, tokens: List<PsToken>, k: Int): String {
            val parts = ArrayList<String>()
            var j = k
            while (j < tokens.size && parts.size < 64) {
                val token = tokens[j]
                if (token.kind == PsTokenKind.Name ||
                    (token.kind == PsTokenKind.Regular && token.text(data) in VALUE_ENDS)
                ) {
                    break
                }

                parts.add(token.text(data))
                j++
            }

            return parts.joinToString(" ")
        }

        private val VALUE_ENDS = setOf("def", "ND", "|-", "readonly", "noaccess", "executeonly", "put")

        private fun signedInteger(data: ByteArray, token: PsToken): Int? =
            if (token.kind == PsTokenKind.Regular && token.length <= 10) DotNetParse.signedInt32(token.text(data)) else null

        private fun isHex(data: ByteArray): Boolean =
            data.size >= 4 && (0 until 4).all { i -> isHexDigit(data[i].toInt() and 0xFF) }

        private fun fromHex(data: ByteArray): ByteArray {
            val digits = data.filter { b -> isHexDigit(b.toInt() and 0xFF) }
            val bytes = ByteArray(digits.size / 2)
            for (i in bytes.indices) {
                bytes[i] = ((hexValue(digits[2 * i].toInt()) shl 4) or hexValue(digits[(2 * i) + 1].toInt())).toByte()
            }

            return bytes
        }

        private fun isHexDigit(b: Int): Boolean = b in '0'.code..'9'.code || b in 'a'.code..'f'.code || b in 'A'.code..'F'.code

        private fun hexValue(b: Int): Int = when (b) {
            in '0'.code..'9'.code -> b - '0'.code
            in 'a'.code..'f'.code -> b - 'a'.code + 10
            else -> b - 'A'.code + 10
        }

        /** Compares numbers as C#'s SequenceEqual does (double.Equals): 0 equals -0. */
        private fun sameNumbers(a: List<Double>, b: List<Double>): Boolean {
            if (a.size != b.size) {
                return false
            }

            for (i in a.indices) {
                val x: Double = a[i]
                val y: Double = b[i]
                if (!(x == y || (x.isNaN() && y.isNaN()))) {
                    return false
                }
            }

            return true
        }
    }
}
