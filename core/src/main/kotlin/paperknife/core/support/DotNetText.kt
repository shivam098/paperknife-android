package paperknife.core.support

import java.text.Normalizer

/** .NET's `UnicodeCategory`, in its order. */
enum class DotNetUnicodeCategory {
    UppercaseLetter, LowercaseLetter, TitlecaseLetter, ModifierLetter, OtherLetter,
    NonSpacingMark, SpacingCombiningMark, EnclosingMark,
    DecimalDigitNumber, LetterNumber, OtherNumber,
    SpaceSeparator, LineSeparator, ParagraphSeparator,
    Control, Format, Surrogate, PrivateUse,
    ConnectorPunctuation, DashPunctuation, OpenPunctuation, ClosePunctuation,
    InitialQuotePunctuation, FinalQuotePunctuation, OtherPunctuation,
    MathSymbol, CurrencySymbol, ModifierSymbol, OtherSymbol, OtherNotAssigned,
}

/**
 * C#'s `char` predicates where Kotlin's differ. Kotlin's `Char.isWhitespace()` also counts
 * U+001C to U+001F and misses U+0085; C#'s `char.IsWhiteSpace` doesn't and does.
 */
object DotNetChar {
    /** `char.IsWhiteSpace`: U+0009 to U+000D, U+0020, U+0085, U+00A0, and the separators. */
    fun isWhiteSpace(c: Char): Boolean {
        if (c.code < 0x100) {
            return c == ' ' || c in '\u0009'..'\u000D' || c == '\u00A0' || c == '\u0085'
        }

        return when (category(c)) {
            DotNetUnicodeCategory.SpaceSeparator, DotNetUnicodeCategory.LineSeparator, DotNetUnicodeCategory.ParagraphSeparator -> true
            else -> false
        }
    }

    fun isControl(c: Char): Boolean = category(c) == DotNetUnicodeCategory.Control

    /** `CharUnicodeInfo.GetUnicodeCategory(char)`: a surrogate code unit is `Surrogate`. */
    fun category(c: Char): DotNetUnicodeCategory = category(c.code)

    /** `CharUnicodeInfo.GetUnicodeCategory(int)` for a code point. */
    fun category(codePoint: Int): DotNetUnicodeCategory = when (Character.getType(codePoint).toByte()) {
        Character.UPPERCASE_LETTER -> DotNetUnicodeCategory.UppercaseLetter
        Character.LOWERCASE_LETTER -> DotNetUnicodeCategory.LowercaseLetter
        Character.TITLECASE_LETTER -> DotNetUnicodeCategory.TitlecaseLetter
        Character.MODIFIER_LETTER -> DotNetUnicodeCategory.ModifierLetter
        Character.OTHER_LETTER -> DotNetUnicodeCategory.OtherLetter
        Character.NON_SPACING_MARK -> DotNetUnicodeCategory.NonSpacingMark
        Character.COMBINING_SPACING_MARK -> DotNetUnicodeCategory.SpacingCombiningMark
        Character.ENCLOSING_MARK -> DotNetUnicodeCategory.EnclosingMark
        Character.DECIMAL_DIGIT_NUMBER -> DotNetUnicodeCategory.DecimalDigitNumber
        Character.LETTER_NUMBER -> DotNetUnicodeCategory.LetterNumber
        Character.OTHER_NUMBER -> DotNetUnicodeCategory.OtherNumber
        Character.SPACE_SEPARATOR -> DotNetUnicodeCategory.SpaceSeparator
        Character.LINE_SEPARATOR -> DotNetUnicodeCategory.LineSeparator
        Character.PARAGRAPH_SEPARATOR -> DotNetUnicodeCategory.ParagraphSeparator
        Character.CONTROL -> DotNetUnicodeCategory.Control
        Character.FORMAT -> DotNetUnicodeCategory.Format
        Character.SURROGATE -> DotNetUnicodeCategory.Surrogate
        Character.PRIVATE_USE -> DotNetUnicodeCategory.PrivateUse
        Character.CONNECTOR_PUNCTUATION -> DotNetUnicodeCategory.ConnectorPunctuation
        Character.DASH_PUNCTUATION -> DotNetUnicodeCategory.DashPunctuation
        Character.START_PUNCTUATION -> DotNetUnicodeCategory.OpenPunctuation
        Character.END_PUNCTUATION -> DotNetUnicodeCategory.ClosePunctuation
        Character.INITIAL_QUOTE_PUNCTUATION -> DotNetUnicodeCategory.InitialQuotePunctuation
        Character.FINAL_QUOTE_PUNCTUATION -> DotNetUnicodeCategory.FinalQuotePunctuation
        Character.OTHER_PUNCTUATION -> DotNetUnicodeCategory.OtherPunctuation
        Character.MATH_SYMBOL -> DotNetUnicodeCategory.MathSymbol
        Character.CURRENCY_SYMBOL -> DotNetUnicodeCategory.CurrencySymbol
        Character.MODIFIER_SYMBOL -> DotNetUnicodeCategory.ModifierSymbol
        Character.OTHER_SYMBOL -> DotNetUnicodeCategory.OtherSymbol
        else -> DotNetUnicodeCategory.OtherNotAssigned
    }

    /** `char.ConvertFromUtf32`, or null where C# throws (surrogates and values past U+10FFFF). */
    fun convertFromUtf32(value: Int): String? =
        if (value in 0..0x10FFFF && value !in 0xD800..0xDFFF) String(Character.toChars(value)) else null
}

/** C#'s `Trim()`, which removes `char.IsWhiteSpace` characters; Kotlin's `trim()` removes others. */
fun String.dotNetTrim(): String = dotNetTrimEnd().dotNetTrimStart()

/** C#'s `TrimStart()`. */
fun String.dotNetTrimStart(): String = trimStart(DotNetChar::isWhiteSpace)

/** C#'s `TrimEnd()`. */
fun String.dotNetTrimEnd(): String = trimEnd(DotNetChar::isWhiteSpace)

/** C#'s `string.IsNullOrWhiteSpace` for a non-null string. */
val String.isDotNetWhiteSpace: Boolean get() = all(DotNetChar::isWhiteSpace)

/** C#'s `Substring(start, length)`: Kotlin's `substring` takes an end index instead of a length. */
fun String.substringOfLength(start: Int, length: Int): String = substring(start, start + length)

/** C#'s `Normalize(NormalizationForm.FormC)`. */
val String.normalizedFormC: String get() = Normalizer.normalize(this, Normalizer.Form.NFC)

/** C#'s `Normalize(NormalizationForm.FormKC)`. */
val String.normalizedFormKC: String get() = Normalizer.normalize(this, Normalizer.Form.NFKC)
