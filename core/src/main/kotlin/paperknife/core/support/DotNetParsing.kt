package paperknife.core.support

/**
 * `double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var value) &&
 * double.IsFinite(value) ? value : null`: white space (U+0009 to U+000D and U+0020) around an
 * optional sign, decimal digits with an optional point, and an optional exponent, then only NUL
 * characters, as .NET reads them. Kotlin's `toDouble` accepts more (other white space, hexadecimal,
 * a type suffix). The special values .NET reads ("Infinity", "NaN") aren't finite, so they give null.
 */
fun DotNetParse.finiteDouble(text: String): Double? {
    var i = 0
    while (i < text.length && isNumberWhite(text[i])) {
        i++
    }

    val start = i
    if (i < text.length && (text[i] == '+' || text[i] == '-')) {
        i++
    }

    var digits = 0
    while (i < text.length && text[i] in '0'..'9') {
        i++
        digits++
    }

    if (i < text.length && text[i] == '.') {
        i++
        while (i < text.length && text[i] in '0'..'9') {
            i++
            digits++
        }
    }

    if (digits == 0) {
        return null
    }

    // An exponent needs digits; otherwise the "e" is left over, and the text isn't a number.
    if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
        var j = i + 1
        if (j < text.length && (text[j] == '+' || text[j] == '-')) {
            j++
        }

        if (j < text.length && text[j] in '0'..'9') {
            while (j < text.length && text[j] in '0'..'9') {
                j++
            }

            i = j
        }
    }

    val end = i
    while (i < text.length && isNumberWhite(text[i])) {
        i++
    }

    while (i < text.length && text[i] == '\u0000') {
        i++
    }

    if (i < text.length) {
        return null
    }

    // The text is now a decimal number Java reads too, correctly rounded as .NET reads it.
    val value = text.substring(start, end).toDouble()
    return if (value.isFinite()) value else null
}

/**
 * `int.TryParse(text, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, ...)`: an
 * optional sign and decimal digits, without white space, within the range of a 32-bit integer.
 * Like .NET, it allows NUL characters after the digits.
 */
fun DotNetParse.signedInt32(text: String): Int? {
    var i = 0
    var negative = false
    if (i < text.length && (text[i] == '+' || text[i] == '-')) {
        negative = text[i] == '-'
        i++
    }

    var value = 0L
    var digits = 0
    while (i < text.length && text[i] in '0'..'9') {
        value = value * 10 + (text[i] - '0')
        if (value > Int.MAX_VALUE.toLong() + 1) {
            return null
        }

        i++
        digits++
    }

    while (i < text.length && text[i] == '\u0000') {
        i++
    }

    if (digits == 0 || i < text.length) {
        return null
    }

    if (negative) {
        value = -value
    }

    return if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
}

/** The white space .NET's number parsing allows around a number: U+0009 to U+000D and U+0020. */
private fun isNumberWhite(c: Char): Boolean = c == ' ' || c in '\u0009'..'\u000D'
