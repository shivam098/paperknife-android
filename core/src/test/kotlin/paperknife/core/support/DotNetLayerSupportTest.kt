package paperknife.core.support

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The .NET semantics of the helpers the document layer added. Expected values were computed with
 * .NET (`Encoding.UTF8`, `string.Normalize`, `EqualityComparer<float>.Default`); text is given as
 * UTF-16 code units and bytes in hex.
 */
class DotNetLayerSupportTest {
    @TestFactory
    fun utf8IsDecodedAsDotNetDecodesIt(): List<DynamicTest> = listOf(
        "48 65 6C 6C 6F" to "0048 0065 006C 006C 006F",
        "C3 A9" to "00E9",
        "E2 82 AC" to "20AC",
        "F0 9F 98 80" to "D83D DE00",
        "ED A0 80" to "FFFD FFFD FFFD",
        "C0 80" to "FFFD FFFD",
        "E2 82" to "FFFD",
        "E2 82 41" to "FFFD 0041",
        "F0 90 80" to "FFFD",
        "F4 90 80 80" to "FFFD FFFD FFFD FFFD",
        "82 6C 82 72" to "FFFD 006C FFFD 0072",
        "E9 41" to "FFFD 0041",
        "EF BF BE" to "FFFE",
        "C2" to "FFFD",
        "E0 80 80" to "FFFD FFFD FFFD",
        "F8 88 80 80 80" to "FFFD FFFD FFFD FFFD FFFD",
        "F0 80 80 80" to "FFFD FFFD FFFD FFFD",
        "ED 9F BF" to "D7FF",
        "EE 80 80" to "E000",
        "F4 8F BF BF" to "DBFF DFFF",
        "FF FE" to "FFFD FFFD",
        "EF BB BF 41" to "FEFF 0041",
        "C2 41" to "FFFD 0041",
        "E1 80 C0" to "FFFD FFFD",
        "F1 80 80 E1 80 80" to "FFFD 1000",
        "80" to "FFFD",
        "BF 80 41" to "FFFD FFFD 0041",
        "F5 80 80 80" to "FFFD FFFD FFFD FFFD",
        "E0 A0 80" to "0800",
        "DF BF" to "07FF",
        "C1 BF" to "FFFD FFFD",
    ).map { (bytes, text) ->
        DynamicTest.dynamicTest(bytes) {
            assertEquals(units(text), DotNetUtf8.getString(hex(bytes)))
        }
    }

    @Test
    fun utf8IsDecodedFromAPartOfTheArray() {
        assertEquals("\uFFFDb", DotNetUtf8.getString(hex("61 E2 82 62 63"), 1, 3))
    }

    @Test
    fun utf8IsEncodedAsDotNetEncodesIt() {
        val cases = listOf(
            "0061 D800 0062 DC00 DBFF DFFF DC00 D800" to "61 EF BF BD 62 EF BF BD F4 8F BF BF EF BF BD EF BF BD",
            "00E9 20AC D83D DE00" to "C3 A9 E2 82 AC F0 9F 98 80",
            "0000" to "00",
            "FFFF" to "EF BF BF",
            "007F 0080 07FF 0800" to "7F C2 80 DF BF E0 A0 80",
            "D83D" to "EF BF BD",
        )
        for ((text, bytes) in cases) {
            assertEquals(bytes, DotNetUtf8.getBytes(units(text)).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }, text)
        }
    }

    @Test
    fun normalizationThrowsWhereDotNetDoes() {
        // (text, FormC, FormKC); null where .NET throws ArgumentException.
        val cases = listOf(
            Triple("0065 0301", "00E9", "00E9"),
            Triple("2126", "03A9", "03A9"),
            Triple("0061 D800 0062", null, null),
            Triple("0061 DC00", null, null),
            Triple("0061 FFFE", null, null),
            Triple("D83D DE00", "D83D DE00", "D83D DE00"),
            Triple("0061 FFFF", "0061 FFFF", "0061 FFFF"),
            Triple("0061 D83D", null, null),
            Triple("00B2 2026", "00B2 2026", "0032 002E 002E 002E"),
            Triple("FB01 00A0", "FB01 00A0", "0066 0069 0020"),
        )
        for ((text, formC, formKC) in cases) {
            for ((normalize, expected) in listOf(DotNetNormalization::formC to formC, DotNetNormalization::formKC to formKC)) {
                if (expected == null) {
                    val error = assertFailsWith<IllegalArgumentException>(text) { normalize(units(text)) }
                    assertEquals("String contains invalid Unicode code points. (Parameter 'strInput')", error.message)
                } else {
                    assertEquals(units(expected), normalize(units(text)), text)
                }
            }
        }
    }

    @Test
    fun floatsAreEqualAsInDotNet() {
        assertTrue(DotNetFloat.equals(-0f, 0f))
        assertTrue(DotNetFloat.equals(Float.NaN, Float.fromBits(0x7FC00001)))
        assertFalse(DotNetFloat.equals(1f, Math.nextUp(1f)))
        assertEquals(DotNetFloat.hashCode(0f), DotNetFloat.hashCode(-0f))
        assertTrue(DotNetFloat.sequenceEqual(floatArrayOf(-0f, Float.NaN, 1.5f), floatArrayOf(0f, Float.NaN, 1.5f)))
        assertFalse(DotNetFloat.sequenceEqual(floatArrayOf(1f), floatArrayOf(1f, 1f)))
    }

    private fun hex(bytes: String): ByteArray = bytes.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    private fun units(text: String): String = text.split(' ').map { it.toInt(16).toChar() }.joinToString("")
}
