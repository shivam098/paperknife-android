package paperknife.core.support

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The .NET semantics the port relies on. Expected values were computed with .NET 10, the runtime
 * of PaperKnife for Windows; doubles are given by their bits.
 */
class DotNetSupportTest {
    @Test
    fun numbersAreFormattedAsCosWriterFormatsThem() {
        // (bits of the value, Math.Round(value, 10).ToString("0.##########") with "-0" as "0")
        val cases = listOf(
            0L to "0",
            Long.MIN_VALUE to "0",
            4607182418800017408L to "1",
            -4616189618054758400L to "-1",
            4602678819172646912L to "0.5",
            4634766966517661696L to "72",
            4645611352945393140L to "392.432",
            4639296148043988992L to "144.7270812988",
            4640296369691557888L to "173.155090332",
            4442235333156365461L to "0",
            4452789957460213179L to "0",
            4452789957461760604L to "0.0000000001",
            4742964961033578167L to "1234567890.12346",
            4817745636528479851L to "123456789012346",
            4831355200913801216L to "1000000000000000",
            4846369599423283195L to "9999999999999990",
            4846369599423283200L to "10000000000000000",
            4847542438873900484L to "12345678901234600",
            4906019910204099648L to "100000000000000000000",
            -4317352126650676160L to "-100000000000000000000",
            4607182418800017409L to "1",
            4599075939470750516L to "0.3",
            -4600232419437613253L to "-12.3456789012",
            4741671816366391296L to "1000000000",
            4613205983301625446L to "2.675",
            4607184670599831093L to "1.0005",
            4614256656552045848L to "3.1415926536",
            4599676419421066581L to "0.3333333333",
            4604180019048437077L to "0.6666666667",
            -4619192017806338731L to "-0.6666666667",
            4636737291354639806L to "100",
            4544867299706393481L to "0.000071",
            4548686775598975835L to "0.0001234568",
            118622047889322841L to "0",
            -9104749988965452967L to "0",
            4629888066921343654L to "33.3333333333",
            4648098325102120141L to "555.6",
            4602678819172646910L to "0.5",
            4668012723080811475L to "12345.6789012346",
            4539760997215258826L to "0.0000314159",
        )
        for ((bits, expected) in cases) {
            val value = Double.fromBits(bits)
            val text = DotNetMath.formatUpTo10Decimals(DotNetMath.round(value, 10))
            assertEquals(expected, if (text == "-0") "0" else text, "$value")
        }
    }

    @Test
    fun roundingMatchesMathRound() {
        // (value, Math.Round(v), Math.Round(v, AwayFromZero), Math.Round(v, 2), Math.Round(v, 2, AwayFromZero)), as bits
        val cases = listOf(
            longArrayOf(4602678819172646912, 0, 4607182418800017408, 4602678819172646912, 4602678819172646912),
            longArrayOf(4609434218613702656, 4611686018427387904, 4611686018427387904, 4609434218613702656, 4609434218613702656),
            longArrayOf(4612811918334230528, 4611686018427387904, 4613937818241073152, 4612811918334230528, 4612811918334230528),
            longArrayOf(-4620693217682128896, Long.MIN_VALUE, -4616189618054758400, -4620693217682128896, -4620693217682128896),
            longArrayOf(-4613937818241073152, -4611686018427387904, -4611686018427387904, -4613937818241073152, -4613937818241073152),
            longArrayOf(-4610560118520545280, -4611686018427387904, -4609434218613702656, -4610560118520545280, -4610560118520545280),
            longArrayOf(4602678819172646911, 0, 0, 4602678819172646912, 4602678819172646912),
            longArrayOf(4613205983301625446, 4613937818241073152, 4613937818241073152, 4613217242300693873, 4613217242300693873),
            longArrayOf(4607204936798154260, 4607182418800017408, 4607182418800017408, 4607182418800017408, 4607182418800017408),
            longArrayOf(-4616167100056621548, -4616189618054758400, -4616189618054758400, -4616189618054758400, -4616189618054758400),
            longArrayOf(4653144502051863213, 4653146402887565312, 4653146402887565312, 4653144511727565537, 4653144511727565537),
            longArrayOf(4593671619917905920, 0, 0, 4593311331947716280, 4593851763903000740),
            longArrayOf(4600427019358961664, 0, 0, 4600517091351509074, 4600517091351509074),
            longArrayOf(4861130398305394688, 4861130398305394688, 4861130398305394688, 4861130398305394688, 4861130398305394688),
            longArrayOf(-2251799813685248, -2251799813685248, -2251799813685248, -2251799813685248, -2251799813685248),
            longArrayOf(9218868437227405312, 9218868437227405312, 9218868437227405312, 9218868437227405312, 9218868437227405312),
        )
        for ((input, toEven, awayFromZero, twoDigits, twoDigitsAway) in cases.map { it.toList() }) {
            val value = Double.fromBits(input)
            assertEquals(toEven, DotNetMath.round(value).toRawBits(), "$value")
            assertEquals(awayFromZero, DotNetMath.roundAwayFromZero(value).toRawBits(), "$value")
            assertEquals(twoDigits, DotNetMath.round(value, 2).toRawBits(), "$value")
            assertEquals(twoDigitsAway, DotNetMath.round(value, 2, awayFromZero = true).toRawBits(), "$value")
        }
    }

    @Test
    fun castsSaturateAsInDotNet() {
        // (value as bits, (int)v, (long)v, (int)(float)v): Kotlin's own conversions behave alike.
        val cases = listOf(
            longArrayOf(4615964438073389875, 3, 3, 3),
            longArrayOf(-4607407598781385933, -3, -3, -3),
            longArrayOf(4602678819172646912, 0, 0, 0),
            longArrayOf(-4620693217682128896, 0, 0, 0),
            longArrayOf(4746794007246405632, 2147483647, 2147483647, 2147483647),
            longArrayOf(4746794007248502784, 2147483647, 2147483648, 2147483647),
            longArrayOf(-4476578029604175872, -2147483648, -2147483649, -2147483648),
            longArrayOf(4756540486875873280, 2147483647, 10000000000, 2147483647),
            longArrayOf(-4466831549978902528, -2147483648, -10000000000, -2147483648),
            longArrayOf(-2251799813685248, 0, 0, 0),
            longArrayOf(9218868437227405312, 2147483647, Long.MAX_VALUE, 2147483647),
            longArrayOf(-4503599627370496, -2147483648, Long.MIN_VALUE, -2147483648),
        )
        for ((input, int32, int64, fromFloat) in cases.map { it.toList() }) {
            val value = Double.fromBits(input)
            assertEquals(int32, value.toInt().toLong(), "$value")
            assertEquals(int64, value.toLong(), "$value")
            assertEquals(fromFloat, value.toFloat().toInt().toLong(), "$value")
        }
    }

    @Test
    fun charactersAreClassifiedAsInDotNet() {
        assertTrue(DotNetChar.isWhiteSpace('\u0085'))
        assertTrue(DotNetChar.isWhiteSpace('\u3000'))
        assertFalse(DotNetChar.isWhiteSpace('\u200B'))
        assertFalse(DotNetChar.isWhiteSpace('\u001C'), "Kotlin's isWhitespace counts U+001C; C# doesn't.")
        assertTrue(DotNetChar.isControl('\u007F'))
        assertEquals(DotNetUnicodeCategory.Surrogate, DotNetChar.category('\uD83D'))
        assertEquals(DotNetUnicodeCategory.NonSpacingMark, DotNetChar.category('\u0301'))
        assertEquals(DotNetUnicodeCategory.LineSeparator, DotNetChar.category('\u2028'))
        assertEquals(DotNetUnicodeCategory.Format, DotNetChar.category('\u00AD'))
        assertEquals("\uD83D\uDE00", DotNetChar.convertFromUtf32(0x1F600))
        assertNull(DotNetChar.convertFromUtf32(0xD800))
        assertEquals("text", " \u00A0text\u2003\u0085".dotNetTrim())
        assertEquals("ell", "hello".substringOfLength(1, 3))
    }

    @Test
    fun integersParseAsInDotNet() {
        assertEquals(0xE9, DotNetParse.hexInt32("00E9"))
        assertNull(DotNetParse.hexInt32("-123"))
        assertNull(DotNetParse.hexInt32(""))
        assertEquals(-1, DotNetParse.hexInt32("FFFFFFFF"))
        assertNull(DotNetParse.hexInt32("100000000"))
        assertEquals(-42, DotNetParse.int32(" -42 "))
        assertEquals(7, DotNetParse.int32("+7"))
        assertNull(DotNetParse.int32("2147483648"))
        assertEquals(Int.MIN_VALUE, DotNetParse.int32("-2147483648"))
        assertNull(DotNetParse.int32("1.5"))
    }

    @Test
    fun dictionaryReusesFreedPlacesAsCSharpDoes() {
        val dictionary = DotNetDictionary<String, Int>()
        dictionary["a"] = 1
        dictionary["b"] = 2
        dictionary["c"] = 3
        dictionary.remove("b")
        dictionary["d"] = 4
        dictionary["a"] = 5
        assertEquals(listOf("a", "d", "c"), dictionary.keys)
        assertEquals(listOf(5, 4, 3), dictionary.values)
        assertFalse(dictionary.tryAdd("a", 9))

        val set = DotNetHashSet<Int>()
        listOf(1, 2, 3).forEach { set.add(it) }
        set.remove(1)
        set.add(4)
        assertEquals(listOf(4, 2, 3), set.toList())
    }
}
