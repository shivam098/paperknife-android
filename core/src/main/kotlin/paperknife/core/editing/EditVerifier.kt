package paperknife.core.editing

import paperknife.core.RenderedBitmap
import paperknife.core.support.DotNetMath
import kotlin.math.abs
import kotlin.math.sqrt

/** A rendering size in pixels (C#'s `(int Width, int Height)`). */
internal data class PixelSize(val width: Int, val height: Int)

/** Pixel comparison used to prove that an edit didn't change anything else on the page. */
internal object EditVerifier {
    /** Per-channel difference treated as anti-aliasing noise rather than a real change. */
    const val channelTolerance = 24

    private const val targetPixelsPerPoint = 2.0
    private const val maxCheckPixels = 8_000_000.0

    /** Rendering size for verification: 144 dpi, scaled down for very large pages. */
    fun checkSize(widthPoints: Float, heightPoints: Float): PixelSize {
        var scale = targetPixelsPerPoint
        val area = widthPoints * heightPoints.toDouble()
        if (area * scale * scale > maxCheckPixels) {
            scale = sqrt(maxCheckPixels / area)
        }

        return PixelSize(maxOf(1, DotNetMath.round(widthPoints * scale).toInt()), maxOf(1, DotNetMath.round(heightPoints * scale).toInt()))
    }

    fun countChangedPixels(before: RenderedBitmap, after: RenderedBitmap): Int = countChanged(before, after, coverage = false)

    /**
     * Counts pixels whose coverage differs: the alpha channel of renderings on a transparent
     * background, which shows content of any color.
     */
    fun countChangedCoverage(before: RenderedBitmap, after: RenderedBitmap): Int = countChanged(before, after, coverage = true)

    private fun countChanged(before: RenderedBitmap, after: RenderedBitmap, coverage: Boolean): Int {
        if (before.width != after.width || before.height != after.height) {
            return before.width * before.height
        }

        val a = before.pixels
        val b = after.pixels
        var changed = 0
        for (y in 0 until before.height) {
            val row = y * before.stride
            for (x in 0 until before.width) {
                val i = row + (x * 4)
                val differs = if (coverage) {
                    abs(channel(a, i + 3) - channel(b, i + 3)) > channelTolerance
                } else {
                    abs(channel(a, i) - channel(b, i)) > channelTolerance
                        || abs(channel(a, i + 1) - channel(b, i + 1)) > channelTolerance
                        || abs(channel(a, i + 2) - channel(b, i + 2)) > channelTolerance
                }

                if (differs) {
                    changed++
                }
            }
        }

        return changed
    }

    /** A byte of a bitmap as C# reads it, 0 to 255. */
    private fun channel(pixels: ByteArray, index: Int): Int = pixels[index].toInt() and 0xFF
}
