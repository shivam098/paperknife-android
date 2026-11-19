package paperknife.core.support

/**
 * .NET's equality and order of `double` values, where Kotlin's differ. C#'s `double.Equals`
 * (which records and tuples use) is `==`, except that NaN equals NaN; Kotlin's data classes
 * compare bits, so they also tell 0.0 from -0.0. `double.CompareTo` (which `List<double>.Sort`
 * uses) puts NaN before every number and 0.0 level with -0.0; Kotlin's `compareTo` puts NaN
 * after every number and -0.0 before 0.0.
 */
object DotNetDouble {
    /** `double.Equals`. */
    fun equals(a: Double, b: Double): Boolean = a == b || (a.isNaN() && b.isNaN())

    /** A hash code consistent with [equals]: 0.0 and -0.0 hash alike, and so do all NaNs. */
    fun hashCode(value: Double): Int = (if (value == 0.0) 0.0 else value).hashCode()

    /** `a.CompareTo(b)`. */
    fun compare(a: Double, b: Double): Int = when {
        a < b -> -1
        a > b -> 1
        a == b -> 0
        a.isNaN() -> if (b.isNaN()) 0 else -1
        else -> 1
    }
}
