package paperknife.core.conformance

/**
 * A small JSON reader for the conformance corpus, so the tests need no JSON library: objects
 * (as maps that keep their keys' order), arrays (lists), strings, numbers (doubles), true, false
 * and null.
 */
internal object MiniJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipWhite()
        require(reader.atEnd) { "Unexpected text at ${reader.position}." }
        return value
    }

    private class Reader(private val text: String) {
        var position = 0
            private set

        val atEnd: Boolean get() = position >= text.length

        fun skipWhite() {
            while (position < text.length && text[position] in " \t\r\n") {
                position++
            }
        }

        fun readValue(): Any? {
            skipWhite()
            require(position < text.length) { "Unexpected end of JSON." }
            return when (text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readWord("true", true)
                'f' -> readWord("false", false)
                'n' -> readWord("null", null)
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            position++
            skipWhite()
            if (text[position] == '}') {
                position++
                return map
            }

            while (true) {
                skipWhite()
                val key = readString()
                skipWhite()
                expect(':')
                map[key] = readValue()
                skipWhite()
                if (text[position] == ',') {
                    position++
                    continue
                }

                expect('}')
                return map
            }
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            position++
            skipWhite()
            if (text[position] == ']') {
                position++
                return list
            }

            while (true) {
                list.add(readValue())
                skipWhite()
                if (text[position] == ',') {
                    position++
                    continue
                }

                expect(']')
                return list
            }
        }

        private fun readString(): String {
            expect('"')
            val value = StringBuilder()
            while (true) {
                val c = text[position++]
                when (c) {
                    '"' -> return value.toString()
                    '\\' -> {
                        val escaped = text[position++]
                        when (escaped) {
                            '"', '\\', '/' -> value.append(escaped)
                            'b' -> value.append('\b')
                            'f' -> value.append('\u000C')
                            'n' -> value.append('\n')
                            'r' -> value.append('\r')
                            't' -> value.append('\t')
                            'u' -> {
                                value.append(text.substring(position, position + 4).toInt(16).toChar())
                                position += 4
                            }
                            else -> throw IllegalArgumentException("Unknown escape \\$escaped at $position.")
                        }
                    }
                    else -> value.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = position
            while (position < text.length && text[position] in "+-0123456789.eE") {
                position++
            }

            return text.substring(start, position).toDouble()
        }

        private fun readWord(word: String, value: Any?): Any? {
            require(text.startsWith(word, position)) { "Unexpected text at $position." }
            position += word.length
            return value
        }

        private fun expect(c: Char) {
            require(position < text.length && text[position] == c) { "Expected '$c' at $position." }
            position++
        }
    }
}
