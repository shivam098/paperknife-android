package paperknife.core.support

/**
 * .NET's equality of floating-point values, where Kotlin's differs. C#'s `float.Equals` (which
 * records, tuples and `SequenceEqual` use) is `==`, except that NaN equals NaN; Kotlin's data
 * classes and `FloatArray.contentEquals` compare bits, so they also tell 0.0 from -0.0.
 */
object DotNetFloat {
    /** `float.Equals`. */
    fun equals(a: Float, b: Float): Boolean = a == b || (a.isNaN() && b.isNaN())

    /** A hash code consistent with [equals]: 0.0 and -0.0 hash alike, and so do all NaNs. */
    fun hashCode(value: Float): Int = (if (value == 0f) 0f else value).hashCode()

    /** `MemoryExtensions.SequenceEqual` of two float spans: the same length and [equals] elements. */
    fun sequenceEqual(a: FloatArray, b: FloatArray): Boolean {
        if (a.size != b.size) {
            return false
        }

        for (i in a.indices) {
            if (!equals(a[i], b[i])) {
                return false
            }
        }

        return true
    }
}
