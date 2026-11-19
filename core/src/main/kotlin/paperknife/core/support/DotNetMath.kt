package paperknife.core.support

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.truncate

/**
 * .NET's arithmetic where Kotlin's differs: `Math.Round` (Kotlin's `roundToInt` and Java's
 * `Math.round` round halves up), and how `CosWriter` formats numbers. Casts need no helper: Kotlin's
 * `toInt()` and `toLong()` truncate, saturate and give 0 for NaN, as .NET does.
 */
object DotNetMath {
    private const val ROUND_LIMIT = 1e16
    private val powersOf10 = DoubleArray(16) { digits -> "1e$digits".toDouble() }

    /** `Math.Round(value)`: to the nearest integer, ties to even (a NaN keeps its bits, as in .NET). */
    fun round(value: Double): Double = if (value.isNaN()) value else Math.rint(value)

    /** `Math.Round(value, MidpointRounding.AwayFromZero)`. */
    fun roundAwayFromZero(value: Double): Double = round(value, 0, awayFromZero = true)

    /**
     * `Math.Round(value, digits)` or, with [awayFromZero], `Math.Round(value, digits,
     * MidpointRounding.AwayFromZero)`, computed as .NET computes it (scaled by a power of 10).
     */
    fun round(value: Double, digits: Int, awayFromZero: Boolean = false): Double {
        require(digits in 0..15) { "C# throws for these digits." }
        if (!(abs(value) < ROUND_LIMIT)) {
            return value
        }

        val power = powersOf10[digits]
        val scaled = value * power
        val rounded = if (awayFromZero) truncate(scaled + Math.copySign(Math.nextDown(0.5), scaled)) else Math.rint(scaled)
        return rounded / power
    }

    /**
     * `value.ToString("0.##########", CultureInfo.InvariantCulture)`. For custom formats .NET first
     * rounds a double to 15 significant digits, then rounds those digits half up to 10 decimals.
     */
    fun formatUpTo10Decimals(value: Double): String {
        if (value.isNaN()) {
            return "NaN"
        }

        if (value.isInfinite()) {
            return if (value < 0) "-Infinity" else "Infinity"
        }

        val negative = value < 0 || 1.0 / value < 0
        if (value == 0.0) {
            return if (negative) "-0" else "0"
        }

        val digits15 = BigDecimal(abs(value)).round(MathContext(15, RoundingMode.HALF_EVEN))
        val rounded = digits15.setScale(10, RoundingMode.HALF_UP)
        if (rounded.signum() == 0) {
            return if (negative) "-0" else "0"
        }

        val text = rounded.stripTrailingZeros().toPlainString()
        return if (negative) "-$text" else text
    }
}

/** .NET's integer parsing, which accepts less than Kotlin's `toIntOrNull` does. */
object DotNetParse {
    /**
     * `int.TryParse(text, NumberStyles.AllowHexSpecifier, ...)`: one to eight hex digits, nothing
     * else; eight digits above 7FFFFFFF give negative numbers, as in C#.
     */
    fun hexInt32(text: CharSequence): Int? {
        if (text.isEmpty()) {
            return null
        }

        var value = 0L
        for (c in text) {
            val digit = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'F' -> c - 'A' + 10
                in 'a'..'f' -> c - 'a' + 10
                else -> return null
            }

            value = value * 16 + digit
            if (value > 0xFFFF_FFFFL) {
                return null
            }
        }

        return value.toInt()
    }

    /**
     * `int.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, ...)`: optional white
     * space around an optional sign and decimal digits, within the range of a 32-bit integer.
     */
    fun int32(text: String): Int? {
        var digits = text.dotNetTrim()
        var negative = false
        if (digits.isNotEmpty() && (digits[0] == '+' || digits[0] == '-')) {
            negative = digits[0] == '-'
            digits = digits.substring(1)
        }

        if (digits.isEmpty()) {
            return null
        }

        var value = 0L
        for (c in digits) {
            if (c !in '0'..'9') {
                return null
            }

            value = value * 10 + (c - '0')
            if (value > Int.MAX_VALUE.toLong() + 1) {
                return null
            }
        }

        if (negative) {
            value = -value
        }

        return if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
    }
}
