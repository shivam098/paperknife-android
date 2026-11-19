package paperknife.core

import paperknife.core.support.DotNetFloat

/**
 * An opaque page rendering in 32-bit BGRA (alpha is always 255, so it is also premultiplied).
 * [pixels] holds [height] rows of [stride] bytes; don't change them (C# exposes them read-only).
 */
class RenderedBitmap internal constructor(
    val width: Int,
    val height: Int,
    val stride: Int,
    val pixels: ByteArray,
)

/**
 * A piece of text as PaperKnife edits it: one or more consecutive top-level text objects that sit
 * next to each other on one baseline and look the same, usually a line or a table cell. Most
 * PDFs draw such text with one object, but some draw every character or word separately. Equal
 * like C#'s record: by value, with `float.Equals`.
 *
 * @param objectIndex Index of the run's first page object; the run spans [objectCount] consecutive objects.
 * @param text The text, without trailing whitespace.
 */
data class PdfTextRun(
    val pageIndex: Int,
    val objectIndex: Int,
    val objectCount: Int,
    val text: String,
    val bounds: PdfRect,
    val fontName: String,
    val isFontEmbedded: Boolean,
    val isFontSubset: Boolean,
    val fontSize: Float,
    val isInvisible: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is PdfTextRun && pageIndex == other.pageIndex && objectIndex == other.objectIndex && objectCount == other.objectCount
            && text == other.text && bounds == other.bounds && fontName == other.fontName && isFontEmbedded == other.isFontEmbedded
            && isFontSubset == other.isFontSubset && DotNetFloat.equals(fontSize, other.fontSize) && isInvisible == other.isInvisible

    override fun hashCode(): Int {
        var hash = pageIndex
        hash = (hash * 31) + objectIndex
        hash = (hash * 31) + objectCount
        hash = (hash * 31) + text.hashCode()
        hash = (hash * 31) + bounds.hashCode()
        hash = (hash * 31) + fontName.hashCode()
        hash = (hash * 31) + isFontEmbedded.hashCode()
        hash = (hash * 31) + isFontSubset.hashCode()
        hash = (hash * 31) + DotNetFloat.hashCode(fontSize)
        return (hash * 31) + isInvisible.hashCode()
    }
}

/** How many top-level objects of each kind a page contains. */
data class PageObjectCounts(val text: Int, val path: Int, val image: Int, val shading: Int, val form: Int, val other: Int) {
    override fun toString(): String =
        "$text text, $path path, $image image, $shading shading, $form form, $other other"
}
