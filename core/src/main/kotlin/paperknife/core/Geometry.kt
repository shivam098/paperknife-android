package paperknife.core

import paperknife.core.support.DotNetFloat

/**
 * A rectangle in PDF page space: points (1/72 inch), origin at the bottom-left, y pointing up.
 * Equal like C#'s record struct: by value, with `float.Equals`.
 */
data class PdfRect(val left: Float, val bottom: Float, val right: Float, val top: Float) {
    val width: Float get() = right - left

    val height: Float get() = top - bottom

    val area: Double get() = maxOf(0f, width) * maxOf(0f, height).toDouble()

    fun contains(x: Double, y: Double): Boolean = x >= left && x <= right && y >= bottom && y <= top

    fun intersects(other: PdfRect): Boolean =
        left < other.right && other.left < right && bottom < other.top && other.bottom < top

    fun union(other: PdfRect): PdfRect = PdfRect(
        minOf(left, other.left),
        minOf(bottom, other.bottom),
        maxOf(right, other.right),
        maxOf(top, other.top),
    )

    fun inflate(amount: Float): PdfRect = PdfRect(left - amount, bottom - amount, right + amount, top + amount)

    override fun equals(other: Any?): Boolean =
        other is PdfRect && DotNetFloat.equals(left, other.left) && DotNetFloat.equals(bottom, other.bottom)
            && DotNetFloat.equals(right, other.right) && DotNetFloat.equals(top, other.top)

    override fun hashCode(): Int {
        var hash = DotNetFloat.hashCode(left)
        hash = (hash * 31) + DotNetFloat.hashCode(bottom)
        hash = (hash * 31) + DotNetFloat.hashCode(right)
        return (hash * 31) + DotNetFloat.hashCode(top)
    }
}

/** A rectangle in rendered-bitmap pixels: origin at the top-left, y pointing down. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width

    val bottom: Int get() = y + height

    fun contains(x: Int, y: Int): Boolean = x >= this.x && x < right && y >= this.y && y < bottom

    fun inflate(amount: Int): PixelRect = PixelRect(x - amount, y - amount, width + (2 * amount), height + (2 * amount))
}

/**
 * Displayed page size in points, after the page's rotation is applied. Equal like C#'s record
 * struct: by value, with `float.Equals`.
 */
data class PageSize(val width: Float, val height: Float) {
    override fun equals(other: Any?): Boolean =
        other is PageSize && DotNetFloat.equals(width, other.width) && DotNetFloat.equals(height, other.height)

    override fun hashCode(): Int = (DotNetFloat.hashCode(width) * 31) + DotNetFloat.hashCode(height)
}
