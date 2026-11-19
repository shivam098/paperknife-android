package paperknife.core.support

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The .NET semantics of the helpers the syntax, content and font layers added. Expected values
 * were computed with .NET 10, the runtime of PaperKnife for Windows; doubles are given by their bits.
 */
class DotNetHelpersTest {
    @Test
    fun finiteDoublesParseAsDoubleTryParseWithFloatStyle() {
        // (text, bits of the value double.TryParse(text, NumberStyles.Float, InvariantCulture) reads)
        val parsed = listOf(
            "1" to 4607182418800017408L,
            "-1.5" to -4613937818241073152L,
            "+.5" to 4602678819172646912L,
            "5." to 4617315517961601024L,
            "1e5" to 4681608360884174848L,
            "1E+5" to 4681608360884174848L,
            " 1" to 4607182418800017408L,
            "1 " to 4607182418800017408L,
            "\u000B1" to 4607182418800017408L,
            "-0" to Long.MIN_VALUE,
            "1.5e-3" to 4564560351926583034L,
            "1e-400" to 0L,
            "1\u0000" to 4607182418800017408L,
        )
        for ((text, bits) in parsed) {
            assertEquals(bits, DotNetParse.finiteDouble(text)?.toRawBits(), "\"$text\"")
        }

        // .NET reads none of these, or not as finite numbers.
        val refused = listOf(
            ".", "1e", "1e+", "\u00011", "1\u0001", "0x10", "1d", "1f", "1,5", "--1", "+-1", "\u00A01", "1\u0085", "e5",
            "1.2.3", "\u221E", "1_000", "Infinity", "-Infinity", "NaN", "infinity", "1e400", "+Infinity", "-nan",
        )
        for (text in refused) {
            assertNull(DotNetParse.finiteDouble(text), "\"$text\"")
        }
    }

    @Test
    fun signedIntegersParseAsIntTryParseWithAllowLeadingSign() {
        val cases = mapOf(
            "1" to 1, "-1" to -1, "+1" to 1, "-0" to 0, " 1" to null, "1 " to null, "\u000B1" to null, "" to null,
            "-" to null, "2147483647" to Int.MAX_VALUE, "2147483648" to null, "-2147483648" to Int.MIN_VALUE,
            "-2147483649" to null, "0001" to 1, "1-" to null, "1\u0000" to 1, "\u0661" to null,
        )
        for ((text, value) in cases) {
            assertEquals(value, DotNetParse.signedInt32(text), "\"$text\"")
        }
    }

    @Test
    fun bigEndianUnicodeReplacesWhatDotNetReplaces() {
        // (bytes, Encoding.BigEndianUnicode.GetString(bytes))
        val cases = listOf(
            "D8000041" to "\uFFFDA",
            "DC000041" to "\uFFFDA",
            "0041D800" to "A\uFFFD",
            "004142" to "A\uFFFD",
            "D80041" to "\uFFFD\uFFFD",
            "FEFF0041" to "\uFEFFA",
            "FFFE" to "\uFFFE",
            "D83DDE00" to "\uD83D\uDE00",
            "D83DD83DDE00" to "\uFFFD\uD83D\uDE00",
            "DE00D83D" to "\uFFFD\uFFFD",
            "41" to "\uFFFD",
            "" to "",
            "D800D800" to "\uFFFD\uFFFD",
            "D800DC" to "\uFFFD\uFFFD",
            "DC00DC000042" to "\uFFFD\uFFFDB",
        )
        for ((hex, expected) in cases) {
            val bytes = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
            assertEquals(expected, DotNetEncoding.bigEndianUnicode(bytes), hex)
        }
    }

    @Test
    fun normalizationRefusesWhatDotNetRefuses() {
        for (text in listOf("\uD800a", "a\uDC00", "\uFFFE", "\uD800")) {
            assertFailsWith<IllegalArgumentException>(text) { text.strictNormalizedFormC }
            assertFailsWith<IllegalArgumentException>(text) { text.strictNormalizedFormKC }
        }

        assertEquals("\uD83D\uDE00", "\uD83D\uDE00".strictNormalizedFormKC)
        assertEquals("\uFFFF", "\uFFFF".strictNormalizedFormC)
        assertEquals("x\uFDD0", "x\uFDD0".strictNormalizedFormKC)
        assertEquals("fi", "\uFB01".strictNormalizedFormKC)
    }
}
