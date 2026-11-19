package paperknife.core.support

/**
 * .NET's `Encoding.UTF8`. Java's encoder writes "?" for an unpaired surrogate where .NET writes
 * U+FFFD, and Java's and Android's decoders are separate implementations; this one replaces
 * invalid input as .NET does everywhere: every maximal subpart of an ill-formed sequence becomes
 * one U+FFFD (The Unicode Standard, section 3.9, "U+FFFD Substitution of Maximal Subparts").
 */
object DotNetUtf8 {
    private const val REPLACEMENT = '\uFFFD'

    /** `Encoding.UTF8.GetBytes(text)`: an unpaired surrogate becomes U+FFFD (EF BF BD). */
    fun getBytes(text: String): ByteArray {
        val output = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            var codePoint = c.code
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                codePoint = Character.toCodePoint(c, text[i + 1])
                i++
            } else if (c.isSurrogate()) {
                codePoint = REPLACEMENT.code
            }

            when {
                codePoint < 0x80 -> output.write(codePoint)
                codePoint < 0x800 -> {
                    output.write(0xC0 or (codePoint shr 6))
                    output.write(0x80 or (codePoint and 0x3F))
                }
                codePoint < 0x10000 -> {
                    output.write(0xE0 or (codePoint shr 12))
                    output.write(0x80 or ((codePoint shr 6) and 0x3F))
                    output.write(0x80 or (codePoint and 0x3F))
                }
                else -> {
                    output.write(0xF0 or (codePoint shr 18))
                    output.write(0x80 or ((codePoint shr 12) and 0x3F))
                    output.write(0x80 or ((codePoint shr 6) and 0x3F))
                    output.write(0x80 or (codePoint and 0x3F))
                }
            }

            i++
        }

        return output.toByteArray()
    }

    /** `Encoding.UTF8.GetString(bytes, index, count)`. */
    fun getString(bytes: ByteArray, index: Int = 0, count: Int = bytes.size - index): String {
        if (index < 0 || count < 0 || index > bytes.size - count) {
            throw IndexOutOfBoundsException("index $index and count $count are outside an array of ${bytes.size} bytes.")
        }

        val text = StringBuilder(count)
        val end = index + count
        var i = index
        while (i < end) {
            val lead = bytes[i].toInt() and 0xFF
            if (lead < 0x80) {
                text.append(lead.toChar())
                i++
                continue
            }

            // The length of the sequence and the range of its second byte (Table 3-7).
            val length: Int
            val low: Int
            val high: Int
            when (lead) {
                in 0xC2..0xDF -> { length = 2; low = 0x80; high = 0xBF }
                0xE0 -> { length = 3; low = 0xA0; high = 0xBF }
                0xED -> { length = 3; low = 0x80; high = 0x9F }
                in 0xE1..0xEF -> { length = 3; low = 0x80; high = 0xBF }
                0xF0 -> { length = 4; low = 0x90; high = 0xBF }
                0xF4 -> { length = 4; low = 0x80; high = 0x8F }
                in 0xF1..0xF3 -> { length = 4; low = 0x80; high = 0xBF }
                else -> {
                    text.append(REPLACEMENT)
                    i++
                    continue
                }
            }

            if (i + 1 >= end || (bytes[i + 1].toInt() and 0xFF) !in low..high) {
                text.append(REPLACEMENT)
                i++
                continue
            }

            var valid = 2
            while (valid < length && i + valid < end && (bytes[i + valid].toInt() and 0xFF) in 0x80..0xBF) {
                valid++
            }

            if (valid < length) {
                text.append(REPLACEMENT)
                i += valid
                continue
            }

            var codePoint = lead and (0xFF shr (length + 1))
            for (k in 1 until length) {
                codePoint = (codePoint shl 6) or (bytes[i + k].toInt() and 0x3F)
            }

            text.appendCodePoint(codePoint)
            i += length
        }

        return text.toString()
    }
}
