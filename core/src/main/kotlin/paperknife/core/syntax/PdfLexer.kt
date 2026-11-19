package paperknife.core.syntax

internal enum class PdfTokenKind {
    End,
    Number,
    Name,
    String,
    ArrayStart,
    ArrayEnd,
    DictStart,
    DictEnd,
    Keyword,
}

/**
 * @param text A number's spelling, a decoded name, or a keyword.
 * @param bytes A string's decoded bytes.
 */
internal data class PdfToken(
    val kind: PdfTokenKind,
    val start: Int,
    val end: Int,
    val text: String,
    val number: Double = 0.0,
    val bytes: ByteArray? = null,
) {
    fun isKeyword(keyword: String): Boolean = kind == PdfTokenKind.Keyword && text == keyword
}

/** Splits PDF syntax into tokens (ISO 32000-1, 7.2). Used for files and content streams. */
internal class PdfLexer(val data: ByteArray, var position: Int = 0) {
    fun skipWhitespaceAndComments() {
        while (position < data.size) {
            val b = data[position].toInt() and 0xFF
            if (isWhitespace(b)) {
                position++
            } else if (b == '%'.code) {
                while (position < data.size && data[position].toInt() != '\r'.code && data[position].toInt() != '\n'.code) {
                    position++
                }
            } else {
                break
            }
        }
    }

    fun peek(): PdfToken {
        val position = this.position
        val token = next()
        this.position = position
        return token
    }

    fun next(): PdfToken {
        skipWhitespaceAndComments()
        val start = position
        if (start >= data.size) {
            return PdfToken(PdfTokenKind.End, start, start, "")
        }

        val b = data[start].toInt() and 0xFF
        return when {
            b == '['.code -> {
                position++
                PdfToken(PdfTokenKind.ArrayStart, start, position, "[")
            }
            b == ']'.code -> {
                position++
                PdfToken(PdfTokenKind.ArrayEnd, start, position, "]")
            }
            b == '<'.code && start + 1 < data.size && data[start + 1].toInt() == '<'.code -> {
                position += 2
                PdfToken(PdfTokenKind.DictStart, start, position, "<<")
            }
            b == '>'.code && start + 1 < data.size && data[start + 1].toInt() == '>'.code -> {
                position += 2
                PdfToken(PdfTokenKind.DictEnd, start, position, ">>")
            }
            b == '<'.code -> readHexString()
            b == '('.code -> readLiteralString()
            b == '/'.code -> readName()
            b == '{'.code || b == '}'.code || b == ')'.code || b == '>'.code -> {
                position++
                PdfToken(PdfTokenKind.Keyword, start, position, b.toChar().toString())
            }
            else -> readWord()
        }
    }

    private fun readWord(): PdfToken {
        val start = position
        while (position < data.size && isRegular(data[position].toInt() and 0xFF)) {
            position++
        }

        val text = String(data, start, position - start, Charsets.ISO_8859_1)

        // isNumber accepts only what .NET's double.TryParse (AllowLeadingSign | AllowDecimalPoint)
        // reads, and Kotlin reads that text alike.
        return if (isNumber(text)) {
            PdfToken(PdfTokenKind.Number, start, position, text, text.toDouble())
        } else {
            PdfToken(PdfTokenKind.Keyword, start, position, text)
        }
    }

    private fun readName(): PdfToken {
        val start = position++
        val name = StringBuilder()
        while (position < data.size && isRegular(data[position].toInt() and 0xFF)) {
            val b = data[position].toInt() and 0xFF
            if (b == '#'.code && position + 2 < data.size && isHexDigit(data[position + 1].toInt() and 0xFF) &&
                isHexDigit(data[position + 2].toInt() and 0xFF)
            ) {
                name.append(((hexValue(data[position + 1].toInt() and 0xFF) shl 4) or hexValue(data[position + 2].toInt() and 0xFF)).toChar())
                position += 3
            } else {
                name.append(b.toChar())
                position++
            }
        }

        return PdfToken(PdfTokenKind.Name, start, position, name.toString())
    }

    private fun readHexString(): PdfToken {
        val start = position++
        val bytes = ByteList()
        var high = -1
        while (position < data.size) {
            val b = data[position++].toInt() and 0xFF
            if (b == '>'.code) {
                break
            }

            if (!isHexDigit(b)) {
                continue
            }

            if (high < 0) {
                high = hexValue(b)
            } else {
                bytes.add((high shl 4) or hexValue(b))
                high = -1
            }
        }

        if (high >= 0) {
            bytes.add(high shl 4)
        }

        return PdfToken(PdfTokenKind.String, start, position, "", bytes = bytes.toByteArray())
    }

    private fun readLiteralString(): PdfToken {
        // Like PDFium, line ends inside the string are kept as they are.
        val start = position++
        val bytes = ByteList()
        var depth = 1
        while (position < data.size) {
            val b = data[position++].toInt() and 0xFF
            if (b == '\\'.code) {
                if (position >= data.size) {
                    break
                }

                val e = data[position++].toInt() and 0xFF
                when (e) {
                    'n'.code -> bytes.add(10)
                    'r'.code -> bytes.add(13)
                    't'.code -> bytes.add(9)
                    'b'.code -> bytes.add(8)
                    'f'.code -> bytes.add(12)
                    '\r'.code -> {
                        if (position < data.size && data[position].toInt() == '\n'.code) {
                            position++
                        }
                    }
                    '\n'.code -> {}
                    in '0'.code..'7'.code -> {
                        var value = e - '0'.code
                        var n = 0
                        while (n < 2 && position < data.size && (data[position].toInt() and 0xFF) in '0'.code..'7'.code) {
                            value = (value * 8) + ((data[position++].toInt() and 0xFF) - '0'.code)
                            n++
                        }

                        bytes.add(value)
                    }
                    else -> bytes.add(e)
                }
            } else if (b == '('.code) {
                depth++
                bytes.add(b)
            } else if (b == ')'.code) {
                if (--depth == 0) {
                    break
                }

                bytes.add(b)
            } else {
                bytes.add(b)
            }
        }

        return PdfToken(PdfTokenKind.String, start, position, "", bytes = bytes.toByteArray())
    }

    /** A growable list of bytes, as C#'s List<byte>: [add] keeps the low 8 bits of a value, as a (byte) cast does. */
    private class ByteList {
        private var items = ByteArray(16)
        private var count = 0

        fun add(value: Int) {
            if (count == items.size) {
                items = items.copyOf(items.size * 2)
            }

            items[count++] = value.toByte()
        }

        fun toByteArray(): ByteArray = items.copyOf(count)
    }

    companion object {
        fun isWhitespace(b: Int): Boolean = b == 0 || b == 9 || b == 10 || b == 12 || b == 13 || b == 32

        fun isDelimiter(b: Int): Boolean =
            b == '('.code || b == ')'.code || b == '<'.code || b == '>'.code || b == '['.code || b == ']'.code ||
                b == '{'.code || b == '}'.code || b == '/'.code || b == '%'.code

        fun isRegular(b: Int): Boolean = !isWhitespace(b) && !isDelimiter(b)

        private fun isNumber(text: String): Boolean {
            var i = 0
            if (i < text.length && (text[i] == '+' || text[i] == '-')) {
                i++
            }

            var digits = 0
            var dots = 0
            while (i < text.length) {
                if (text[i] in '0'..'9') {
                    digits++
                } else if (text[i] == '.' && dots == 0) {
                    dots++
                } else {
                    return false
                }

                i++
            }

            return digits > 0
        }

        fun isHexDigit(b: Int): Boolean = b in '0'.code..'9'.code || b in 'a'.code..'f'.code || b in 'A'.code..'F'.code

        fun hexValue(b: Int): Int = when (b) {
            in '0'.code..'9'.code -> b - '0'.code
            in 'a'.code..'f'.code -> b - 'a'.code + 10
            else -> b - 'A'.code + 10
        }
    }
}

/** Reads objects from tokens: arrays, dictionaries and indirect references. */
internal object PdfObjectParser {
    private const val MAX_DEPTH = 100

    /** The next object, or null at the end of the data or on syntax it doesn't understand. */
    fun read(lexer: PdfLexer, allowReferences: Boolean = true): CosObject? =
        fromToken(lexer, lexer.next(), allowReferences, 0)

    fun fromToken(lexer: PdfLexer, token: PdfToken, allowReferences: Boolean, depth: Int): CosObject? {
        if (depth > MAX_DEPTH) {
            return null
        }

        when (token.kind) {
            PdfTokenKind.Number -> {
                val number = CosNumber(token.number, token.text)
                if (allowReferences && number.isInteger && number.value >= 0) {
                    val reference = tryReadReference(lexer, number.value.toInt())
                    if (reference != null) {
                        return reference
                    }
                }

                return number
            }
            PdfTokenKind.Name -> return CosName(token.text)
            PdfTokenKind.String -> return CosString(token.bytes!!)
            PdfTokenKind.ArrayStart -> {
                val items = ArrayList<CosObject>()
                while (true) {
                    val next = lexer.next()
                    if (next.kind == PdfTokenKind.ArrayEnd) {
                        return CosArray(items)
                    }

                    val item = fromToken(lexer, next, allowReferences, depth + 1) ?: return null
                    items.add(item)
                }
            }
            PdfTokenKind.DictStart -> {
                val entries = LinkedHashMap<String, CosObject>()
                while (true) {
                    val key = lexer.next()
                    if (key.kind == PdfTokenKind.DictEnd) {
                        return CosDict(entries)
                    }

                    if (key.kind != PdfTokenKind.Name) {
                        return null
                    }

                    val value = fromToken(lexer, lexer.next(), allowReferences, depth + 1) ?: return null
                    entries[key.text] = value
                }
            }
            PdfTokenKind.Keyword -> return when (token.text) {
                "true" -> CosBool(true)
                "false" -> CosBool(false)
                "null" -> CosNull.instance
                else -> null
            }
            else -> return null
        }
    }

    private fun tryReadReference(lexer: PdfLexer, number: Int): CosRef? {
        val position = lexer.position
        val generation = lexer.next()
        if (generation.kind == PdfTokenKind.Number && generation.number >= 0 && !generation.text.contains('.') &&
            lexer.next().isKeyword("R")
        ) {
            return CosRef(number, generation.number.toInt())
        }

        lexer.position = position
        return null
    }
}
