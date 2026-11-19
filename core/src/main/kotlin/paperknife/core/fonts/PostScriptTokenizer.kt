package paperknife.core.fonts

internal enum class PsTokenKind {
    /** A literal name such as /FontName. */
    Name,

    /** A number, an operator or another regular token such as def or RD. */
    Regular,

    ArrayStart,

    ArrayEnd,

    /** A whole procedure, { ... }, including nested procedures. */
    Procedure,

    String,

    HexString,

    DictStart,

    DictEnd,

    /** The binary data after "n RD" or "n -|": exactly n bytes after one separator. */
    Binary,
}

/** A token's kind and where it is in the data. */
internal data class PsToken(val kind: PsTokenKind, val start: Int, val length: Int) {
    fun text(data: ByteArray): String = String(data, start, length, Charsets.ISO_8859_1)

    fun `is`(data: ByteArray, text: String): Boolean =
        (kind == PsTokenKind.Regular || kind == PsTokenKind.Name) && length == text.length && this.text(data) == text

    /** The token's value when it is a whole number written with digits only, as Type 1 lengths and indexes are. */
    fun integer(data: ByteArray): Int? {
        if (kind != PsTokenKind.Regular || length < 1 || length > 9) {
            return null
        }

        var value = 0
        for (i in start until start + length) {
            val b = data[i].toInt() and 0xFF
            if (b < '0'.code || b > '9'.code) {
                return null
            }

            value = (value * 10) + (b - '0'.code)
        }

        return value
    }
}

/**
 * Splits PostScript, the language of Type 1 font programs, into tokens in a single pass, the way
 * FreeType's Type 1 parser reads it: comments are skipped, and a procedure, a string or the binary
 * data of a glyph or subroutine is one token.
 */
internal object PostScriptTokenizer {
    /**
     * The tokens, or null when the data is malformed or has more than [maxTokens] tokens. Reading
     * stops at the operator [stopAt] (for example closefile, after which a font program may hold
     * anything).
     */
    fun tokenize(data: ByteArray, maxTokens: Int, stopAt: String? = null): MutableList<PsToken>? = Reader(data).tokenize(maxTokens, stopAt)

    fun isWhite(b: Int): Boolean = b == 0 || b == '\t'.code || b == '\n'.code || b == 0x0C || b == '\r'.code || b == ' '.code

    private fun isRegular(b: Int): Boolean =
        !isWhite(b) && b != '('.code && b != ')'.code && b != '<'.code && b != '>'.code && b != '['.code && b != ']'.code &&
            b != '{'.code && b != '}'.code && b != '/'.code && b != '%'.code

    /** The data and the position in it, which C# passes by reference between these methods. */
    private class Reader(private val data: ByteArray) {
        private var i = 0

        private fun at(index: Int): Int = data[index].toInt() and 0xFF

        fun tokenize(maxTokens: Int, stopAt: String?): MutableList<PsToken>? {
            val tokens = ArrayList<PsToken>()
            while (true) {
                skipSpaceAndComments()
                if (i >= data.size) {
                    return tokens
                }

                if (tokens.size >= maxTokens) {
                    return null
                }

                val start = i
                val b = at(i)
                when {
                    b == '/'.code -> {
                        i++
                        if (i < data.size && at(i) == '/'.code) {
                            i++
                        }

                        while (i < data.size && isRegular(at(i))) {
                            i++
                        }

                        tokens.add(PsToken(PsTokenKind.Name, start, i - start))
                    }
                    b == '['.code -> tokens.add(PsToken(PsTokenKind.ArrayStart, i++, 1))
                    b == ']'.code -> tokens.add(PsToken(PsTokenKind.ArrayEnd, i++, 1))
                    b == '{'.code -> {
                        if (!skipProcedure()) {
                            return null
                        }

                        tokens.add(PsToken(PsTokenKind.Procedure, start, i - start))
                    }
                    b == '('.code -> {
                        if (!skipString()) {
                            return null
                        }

                        tokens.add(PsToken(PsTokenKind.String, start, i - start))
                    }
                    b == '<'.code && i + 1 < data.size && at(i + 1) == '<'.code -> {
                        tokens.add(PsToken(PsTokenKind.DictStart, i, 2))
                        i += 2
                    }
                    b == '<'.code -> {
                        if (!skipHexString()) {
                            return null
                        }

                        tokens.add(PsToken(PsTokenKind.HexString, start, i - start))
                    }
                    b == '>'.code && i + 1 < data.size && at(i + 1) == '>'.code -> {
                        tokens.add(PsToken(PsTokenKind.DictEnd, i, 2))
                        i += 2
                    }
                    b == '>'.code || b == ')'.code || b == '}'.code -> return null
                    else -> {
                        while (i < data.size && isRegular(at(i))) {
                            i++
                        }

                        val token = PsToken(PsTokenKind.Regular, start, i - start)
                        if (stopAt != null && token.`is`(data, stopAt)) {
                            return tokens
                        }

                        tokens.add(token)

                        // "n RD" (or "n -|") is followed by one separator and n bytes of binary data.
                        if (token.`is`(data, "RD") || token.`is`(data, "-|")) {
                            val length = if (tokens.size >= 2) tokens[tokens.size - 2].integer(data) else null
                            if (length != null) {
                                if (length > data.size - i - 1) {
                                    return null
                                }

                                tokens.add(PsToken(PsTokenKind.Binary, i + 1, length))
                                i += 1 + length
                            }
                        }
                    }
                }
            }
        }

        private fun skipSpaceAndComments() {
            while (i < data.size) {
                if (isWhite(at(i))) {
                    i++
                } else if (at(i) == '%'.code) {
                    skipComment()
                } else {
                    return
                }
            }
        }

        private fun skipComment() {
            while (i < data.size && at(i) != '\r'.code && at(i) != '\n'.code) {
                i++
            }
        }

        private fun skipProcedure(): Boolean {
            var depth = 0
            while (i < data.size) {
                val b = at(i)
                when {
                    b == '{'.code -> {
                        depth++
                        i++
                    }
                    b == '}'.code -> {
                        i++
                        if (--depth == 0) {
                            return true
                        }
                    }
                    b == '('.code -> if (!skipString()) {
                        return false
                    }
                    b == '<'.code && i + 1 < data.size && at(i + 1) == '<'.code -> i += 2
                    b == '<'.code -> if (!skipHexString()) {
                        return false
                    }
                    b == '%'.code -> skipComment()
                    else -> i++
                }
            }

            return false
        }

        private fun skipString(): Boolean {
            var depth = 0
            while (i < data.size) {
                val b = at(i++)
                if (b == '\\'.code) {
                    i++
                } else if (b == '('.code) {
                    depth++
                } else if (b == ')'.code && --depth == 0) {
                    return true
                }
            }

            return false
        }

        // <hex digits>, or the ASCII85 form <~ ... ~>.
        private fun skipHexString(): Boolean {
            i++
            if (i < data.size && at(i) == '~'.code) {
                val end = indexOfAsciiEnd(i)
                if (end < 0) {
                    return false
                }

                i += end + 2
                return true
            }

            while (i < data.size) {
                val b = at(i++)
                if (b == '>'.code) {
                    return true
                }

                if (!isWhite(b) && !isHexDigit(b)) {
                    return false
                }
            }

            return false
        }

        /** Where "~>" first occurs from [from] on, relative to [from], or -1. */
        private fun indexOfAsciiEnd(from: Int): Int {
            for (j in from until data.size - 1) {
                if (at(j) == '~'.code && at(j + 1) == '>'.code) {
                    return j - from
                }
            }

            return -1
        }

        private fun isHexDigit(b: Int): Boolean = b in '0'.code..'9'.code || b in 'a'.code..'f'.code || b in 'A'.code..'F'.code
    }
}
