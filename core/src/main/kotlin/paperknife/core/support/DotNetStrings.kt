package paperknife.core.support

/** .NET's text encodings where Java's decoders differ. */
object DotNetEncoding {
    /**
     * `Encoding.BigEndianUnicode.GetString(bytes)`: UTF-16BE, where every unpaired surrogate and a
     * trailing odd byte become U+FFFD, and a byte order mark and U+FFFE stay as they are. Java's
     * UTF-16BE decoder replaces U+FFFE too.
     */
    fun bigEndianUnicode(bytes: ByteArray): String {
        val text = StringBuilder(bytes.size / 2 + 1)
        var i = 0
        while (i + 1 < bytes.size) {
            val c = unit(bytes, i)
            i += 2
            when {
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < bytes.size && Character.isLowSurrogate(unit(bytes, i))) {
                        text.append(c).append(unit(bytes, i))
                        i += 2
                    } else {
                        text.append('\uFFFD')
                    }
                }
                Character.isLowSurrogate(c) -> text.append('\uFFFD')
                else -> text.append(c)
            }
        }

        if (i < bytes.size) {
            text.append('\uFFFD')
        }

        return text.toString()
    }

    private fun unit(bytes: ByteArray, at: Int): Char =
        (((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)).toChar()
}

/**
 * C#'s `Normalize(NormalizationForm.FormC)`, refusals included: .NET throws ArgumentException for
 * text with an unpaired surrogate or U+FFFE, which [normalizedFormC] keeps. This throws
 * IllegalArgumentException there.
 */
val String.strictNormalizedFormC: String get() = requireNormalizable(this).normalizedFormC

/** C#'s `Normalize(NormalizationForm.FormKC)`, refusals included (see [strictNormalizedFormC]). */
val String.strictNormalizedFormKC: String get() = requireNormalizable(this).normalizedFormKC

private fun requireNormalizable(text: String): String {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val unpaired = when {
            Character.isHighSurrogate(c) -> i + 1 >= text.length || !Character.isLowSurrogate(text[++i])
            else -> c == '\uFFFE' || Character.isLowSurrogate(c)
        }

        require(!unpaired) { "Invalid Unicode code point found." }
        i++
    }

    return text
}
