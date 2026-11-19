package paperknife.core.support

/**
 * `string.Normalize` exactly. [normalizedFormC] and [normalizedFormKC] normalize any string, but
 * .NET (with ICU, its default on Windows, Linux and Android) throws `ArgumentException` for a
 * string with an unpaired surrogate or U+FFFE, and C# code may rely on that: callers catch
 * `ArgumentException` to give up on text they can't read.
 */
object DotNetNormalization {
    /** `text.Normalize(NormalizationForm.FormC)`. */
    fun formC(text: String): String {
        checkNormalizable(text)
        return text.normalizedFormC
    }

    /** `text.Normalize(NormalizationForm.FormKC)`. */
    fun formKC(text: String): String {
        checkNormalizable(text)
        return text.normalizedFormKC
    }

    /** Throws `IllegalArgumentException` with .NET's message where .NET can't normalize [text]. */
    private fun checkNormalizable(text: String) {
        if (hasInvalidUnicodeSequence(text)) {
            throw IllegalArgumentException("String contains invalid Unicode code points. (Parameter 'strInput')")
        }
    }

    /** .NET's `Normalization.HasInvalidUnicodeSequence`. */
    private fun hasInvalidUnicodeSequence(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\uFFFE' || c.isLowSurrogate()) {
                return true
            }

            if (c.isHighSurrogate()) {
                if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) {
                    return true
                }

                i++
            }

            i++
        }

        return false
    }
}
