package paperknife.core.support

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The .NET semantics of the helpers the session layer added. Expected values were computed with
 * .NET (`EqualityComparer<double>.Default`, `double.CompareTo`, `List<double>.Sort`).
 */
class DotNetSessionSupportTest {
    @Test
    fun doublesAreEqualAsInDotNet() {
        assertTrue(DotNetDouble.equals(-0.0, 0.0))
        assertTrue(DotNetDouble.equals(Double.NaN, Double.fromBits(0x7FF8_0000_0000_0001)))
        assertFalse(DotNetDouble.equals(1.0, Math.nextUp(1.0)))
        assertEquals(DotNetDouble.hashCode(0.0), DotNetDouble.hashCode(-0.0))
        assertEquals(DotNetDouble.hashCode(Double.NaN), DotNetDouble.hashCode(Double.fromBits(0x7FF8_0000_0000_0001)))
    }

    @Test
    fun doublesAreOrderedAsInDotNet() {
        assertEquals(0, DotNetDouble.compare(-0.0, 0.0))
        assertEquals(0, DotNetDouble.compare(Double.NaN, Double.NaN))
        assertEquals(-1, DotNetDouble.compare(Double.NaN, Double.NEGATIVE_INFINITY))
        assertEquals(1, DotNetDouble.compare(Double.NEGATIVE_INFINITY, Double.NaN))
        assertEquals(-1, DotNetDouble.compare(-1.0, -0.5))

        // new List<double> { 2, double.NaN, double.PositiveInfinity, -1, double.NegativeInfinity }.Sort()
        val values = mutableListOf(2.0, Double.NaN, Double.POSITIVE_INFINITY, -1.0, Double.NEGATIVE_INFINITY)
        values.sortWith { a, b -> DotNetDouble.compare(a, b) }
        assertEquals(listOf(Double.NaN, Double.NEGATIVE_INFINITY, -1.0, 2.0, Double.POSITIVE_INFINITY), values)
    }
}
