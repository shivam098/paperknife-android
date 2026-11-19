package paperknife.core

import com.sun.jna.Memory
import com.sun.jna.Pointer
import paperknife.core.pdfium.FsRectF
import paperknife.core.pdfium.PdfiumLibrary
import paperknife.core.pdfium.PdfiumNative
import paperknife.core.pdfium.PdfiumNative.FPDF_ANNOT
import paperknife.core.pdfium.PdfiumNative.FPDF_ClosePage
import paperknife.core.pdfium.PdfiumNative.FPDF_DeviceToPage
import paperknife.core.pdfium.PdfiumNative.FPDF_GetPageBoundingBox
import paperknife.core.pdfium.PdfiumNative.FPDF_GetPageHeightF
import paperknife.core.pdfium.PdfiumNative.FPDF_GetPageWidthF
import paperknife.core.pdfium.PdfiumNative.FPDF_PAGEOBJ_FORM
import paperknife.core.pdfium.PdfiumNative.FPDF_PAGEOBJ_IMAGE
import paperknife.core.pdfium.PdfiumNative.FPDF_PAGEOBJ_PATH
import paperknife.core.pdfium.PdfiumNative.FPDF_PAGEOBJ_SHADING
import paperknife.core.pdfium.PdfiumNative.FPDF_PAGEOBJ_TEXT
import paperknife.core.pdfium.PdfiumNative.FPDF_PageToDevice
import paperknife.core.pdfium.PdfiumNative.FPDF_RenderPageBitmap
import paperknife.core.pdfium.PdfiumNative.FPDFBitmap_BGRA
import paperknife.core.pdfium.PdfiumNative.FPDFBitmap_CreateEx
import paperknife.core.pdfium.PdfiumNative.FPDFBitmap_Destroy
import paperknife.core.pdfium.PdfiumNative.FPDFBitmap_FillRect
import paperknife.core.pdfium.PdfiumNative.FPDFClipPath_CountPaths
import paperknife.core.pdfium.PdfiumNative.FPDFPage_CountObjects
import paperknife.core.pdfium.PdfiumNative.FPDFPage_GenerateContent
import paperknife.core.pdfium.PdfiumNative.FPDFPage_GetObject
import paperknife.core.pdfium.PdfiumNative.FPDFPage_GetRotation
import paperknife.core.pdfium.PdfiumNative.FPDFPage_RemoveObject
import paperknife.core.pdfium.PdfiumNative.FPDFPage_SetRotation
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_Destroy
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_GetClipPath
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_GetType
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_SetIsActive
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_Transform
import paperknife.core.pdfium.PdfiumNative.FPDFPageObj_TransformClipPath
import paperknife.core.pdfium.PdfiumNative.FPDFText_SetText
import paperknife.core.pdfium.PdfiumNative.FPDFTextObj_GetFont
import paperknife.core.pdfium.PdfiumNative.FPDFTextObj_GetFontSize
import paperknife.core.pdfium.toNativeLong
import paperknife.core.support.isDotNetWhiteSpace

/** A loaded page. Owned by its [PdfDocument] and invalid once the document is disposed. */
class PdfPage internal constructor(
    private val document: PdfDocument,
    handle: Pointer,
    val index: Int,
) {
    private var _handle: Pointer? = handle
    private var contentDirty = false

    /** Displayed width in points (rotation applied). */
    val width: Float
        get() = synchronized(PdfiumLibrary.sync) {
            FPDF_GetPageWidthF(handle)
        }

    /** Displayed height in points (rotation applied). */
    val height: Float
        get() = synchronized(PdfiumLibrary.sync) {
            FPDF_GetPageHeightF(handle)
        }

    /** Clockwise rotation in quarter turns (0-3), from the page's /Rotate entry. */
    val rotation: Int
        get() = synchronized(PdfiumLibrary.sync) {
            maxOf(0, FPDFPage_GetRotation(handle))
        }

    internal val handle: Pointer
        get() {
            val handle = _handle
            if (handle == null || document.isDisposed) {
                throw IllegalStateException("Cannot access a disposed object.\nObject name: 'PdfPage'.")
            }

            return handle
        }

    /** Renders the whole page, with annotations, onto a white background. */
    fun render(pixelWidth: Int, pixelHeight: Int): RenderedBitmap = render(pixelWidth, pixelHeight, 0xFFFFFFFFu)

    /**
     * Renders the page onto a transparent background, so the alpha channel shows what is drawn,
     * in any color, white included.
     */
    internal fun renderOnTransparent(pixelWidth: Int, pixelHeight: Int): RenderedBitmap = render(pixelWidth, pixelHeight, 0u)

    private fun render(pixelWidth: Int, pixelHeight: Int, background: UInt): RenderedBitmap {
        if (pixelWidth < 1) {
            throw IllegalArgumentException("pixelWidth ('$pixelWidth') must be greater than or equal to '1'. (Parameter 'pixelWidth')\nActual value was $pixelWidth.")
        }

        if (pixelHeight < 1) {
            throw IllegalArgumentException("pixelHeight ('$pixelHeight') must be greater than or equal to '1'. (Parameter 'pixelHeight')\nActual value was $pixelHeight.")
        }

        val pixelCount = pixelWidth.toLong() * pixelHeight
        if (pixelCount > MAX_RENDER_PIXELS) {
            throw IllegalArgumentException("pixelWidth ('$pixelCount') must be less than or equal to '$MAX_RENDER_PIXELS'. (Parameter 'pixelWidth')\nActual value was $pixelCount.")
        }

        val stride = pixelWidth * 4
        val pixels = ByteArray(stride * pixelHeight)
        synchronized(PdfiumLibrary.sync) {
            val handle = handle

            // PDFium draws into native memory for the whole call sequence; C# pins the array instead.
            Memory(pixels.size.toLong()).use { buffer ->
                val bitmap = FPDFBitmap_CreateEx(pixelWidth, pixelHeight, FPDFBitmap_BGRA, buffer, stride)
                    ?: throw PdfException(PdfErrorCode.OperationFailed, "There isn't enough memory to draw this page.")

                try {
                    FPDFBitmap_FillRect(bitmap, 0, 0, pixelWidth, pixelHeight, background.toNativeLong())
                    FPDF_RenderPageBitmap(bitmap, handle, 0, 0, pixelWidth, pixelHeight, 0, FPDF_ANNOT)
                } finally {
                    FPDFBitmap_Destroy(bitmap)
                }

                buffer.read(0, pixels, 0, pixels.size)
            }
        }

        return RenderedBitmap(pixelWidth, pixelHeight, stride, pixels)
    }

    fun countObjects(): PageObjectCounts {
        synchronized(PdfiumLibrary.sync) {
            val handle = handle
            var text = 0
            var path = 0
            var image = 0
            var shading = 0
            var form = 0
            var other = 0
            val count = FPDFPage_CountObjects(handle)
            for (i in 0 until count) {
                when (FPDFPageObj_GetType(FPDFPage_GetObject(handle, i))) {
                    FPDF_PAGEOBJ_TEXT -> text++
                    FPDF_PAGEOBJ_PATH -> path++
                    FPDF_PAGEOBJ_IMAGE -> image++
                    FPDF_PAGEOBJ_SHADING -> shading++
                    FPDF_PAGEOBJ_FORM -> form++
                    else -> other++
                }
            }

            return PageObjectCounts(text, path, image, shading, form, other)
        }
    }

    /** All top-level text runs, in drawing order. Text nested in form XObjects is not included. */
    fun getTextRuns(): List<PdfTextRun> {
        synchronized(PdfiumLibrary.sync) {
            return TextLayout.build(handle, index).runs.map { it.run }
        }
    }

    /** The run that contains the page object at [objectIndex], if it is text. */
    fun tryGetTextRun(objectIndex: Int): PdfTextRun? {
        synchronized(PdfiumLibrary.sync) {
            return getRunLayout(objectIndex)?.run
        }
    }

    /** Finds the most specific visible text run under a pixel of a rendering of the given size. */
    fun hitTestText(pixelWidth: Int, pixelHeight: Int, x: Int, y: Int): PdfTextRun? {
        synchronized(PdfiumLibrary.sync) {
            val pageX = DoubleArray(1)
            val pageY = DoubleArray(1)
            if (FPDF_DeviceToPage(handle, 0, 0, pixelWidth, pixelHeight, 0, x, y, pageX, pageY) == 0) {
                return null
            }

            var best: PdfTextRun? = null
            for (run in getTextRuns()) {
                if (run.text.isDotNetWhiteSpace || !run.bounds.inflate(1f).contains(pageX[0], pageY[0])) {
                    continue
                }

                if (best == null || isBetterHit(run, best)) {
                    best = run
                }
            }

            return best
        }
    }

    /** Maps a page-space rectangle to the pixels it covers in a rendering of the given size. */
    fun mapToPixels(rect: PdfRect, pixelWidth: Int, pixelHeight: Int): PixelRect {
        synchronized(PdfiumLibrary.sync) {
            val handle = handle
            val xs = doubleArrayOf(rect.left.toDouble(), rect.left.toDouble(), rect.right.toDouble(), rect.right.toDouble())
            val ys = doubleArrayOf(rect.bottom.toDouble(), rect.top.toDouble(), rect.bottom.toDouble(), rect.top.toDouble())
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            for (i in 0 until 4) {
                val deviceX = IntArray(1)
                val deviceY = IntArray(1)
                FPDF_PageToDevice(handle, 0, 0, pixelWidth, pixelHeight, 0, xs[i], ys[i], deviceX, deviceY)
                minX = minOf(minX, deviceX[0])
                minY = minOf(minY, deviceY[0])
                maxX = maxOf(maxX, deviceX[0])
                maxY = maxOf(maxY, deviceY[0])
            }

            return PixelRect(minX, minY, maxX - minX, maxY - minY)
        }
    }

    internal fun getTextObject(objectIndex: Int): Pointer? {
        val handle = handle
        if (objectIndex < 0 || objectIndex >= FPDFPage_CountObjects(handle)) {
            return null
        }

        val obj = FPDFPage_GetObject(handle, objectIndex)
        return if (obj != null && FPDFPageObj_GetType(obj) == FPDF_PAGEOBJ_TEXT) obj else null
    }

    /** The page's top-level text objects and the runs they form, valid until the page changes. */
    internal fun getTextLayout(): PageTextLayout = TextLayout.build(handle, index)

    /** The run that contains the page object at [objectIndex], with the objects it is made of. */
    internal fun getRunLayout(objectIndex: Int): TextRunLayout? =
        getTextLayout().runs.firstOrNull { it.contains(objectIndex) }

    /** Hides or shows one object in memory, for example to render a page without it. */
    internal fun setObjectActive(objectIndex: Int, active: Boolean): Boolean {
        val handle = handle
        if (objectIndex < 0 || objectIndex >= FPDFPage_CountObjects(handle)) {
            return false
        }

        val obj = FPDFPage_GetObject(handle, objectIndex)
        return obj != null && FPDFPageObj_SetIsActive(obj, if (active) 1 else 0) != 0
    }

    /**
     * Marks one object as modified without changing it (an identity transform), so the next save
     * makes PDFium rewrite the content stream that contains it.
     */
    internal fun markObjectModified(objectIndex: Int): Boolean {
        val handle = handle
        if (objectIndex < 0 || objectIndex >= FPDFPage_CountObjects(handle)) {
            return false
        }

        val obj = FPDFPage_GetObject(handle, objectIndex) ?: return false
        FPDFPageObj_Transform(obj, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        contentDirty = true
        return true
    }

    internal fun setText(textObject: Pointer, text: String): Boolean {
        // PdfiumNative.wideString adds the NUL terminator FPDF_WIDESTRING requires (.NET strings carry one).
        if (FPDFText_SetText(textObject, PdfiumNative.wideString(text)) == 0) {
            return false
        }

        contentDirty = true
        return true
    }

    /** Removes one object from the page and frees it. */
    internal fun removeObject(objectIndex: Int): Boolean {
        val handle = handle
        if (objectIndex < 0 || objectIndex >= FPDFPage_CountObjects(handle)) {
            return false
        }

        val obj = FPDFPage_GetObject(handle, objectIndex)
        if (obj == null || FPDFPage_RemoveObject(handle, obj) == 0) {
            return false
        }

        FPDFPageObj_Destroy(obj)
        contentDirty = true
        return true
    }

    /** The advance width of [text] in a text object's font and size, in its text space units. */
    internal fun measureText(objectIndex: Int, text: String): Double {
        val obj = getTextObject(objectIndex) ?: return Double.NaN
        val size = FloatArray(1)
        FPDFTextObj_GetFontSize(obj, size)
        return TextLayout.measure(FPDFTextObj_GetFont(obj), size[0], text)
    }

    /** Moves one object by a page-space offset. */
    internal fun translateObject(objectIndex: Int, dx: Double, dy: Double): Boolean {
        val handle = handle
        if (objectIndex < 0 || objectIndex >= FPDFPage_CountObjects(handle)) {
            return false
        }

        val obj = FPDFPage_GetObject(handle, objectIndex) ?: return false
        FPDFPageObj_Transform(obj, 1.0, 0.0, 0.0, 1.0, dx, dy)
        contentDirty = true
        return true
    }

    /** The part of the page that is shown: its media box cut to its crop box, in page space. */
    internal fun getVisibleBox(): PdfRect {
        val native = FloatArray(FsRectF.SIZE)
        if (FPDF_GetPageBoundingBox(handle, native) == 0) {
            return PdfRect(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        }

        val box = FsRectF(native)
        return PdfRect(box.left, box.bottom, box.right, box.top)
    }

    /** Whether a page object is clipped by at least one path. */
    internal fun hasClipPath(objectIndex: Int): Boolean {
        val obj = getObjectHandle(objectIndex)
        val clip = if (obj == null) null else FPDFPageObj_GetClipPath(obj)
        return clip != null && FPDFClipPath_CountPaths(clip) > 0
    }

    /** Moves one object's clipping path by a page-space offset, in memory only, for example to render it. */
    internal fun translateClipPath(objectIndex: Int, dx: Double, dy: Double): Boolean {
        val obj = getObjectHandle(objectIndex) ?: return false
        FPDFPageObj_TransformClipPath(obj, 1.0, 0.0, 0.0, 1.0, dx, dy)
        return true
    }

    /** Hides every object except the given ones, in memory, to render them on their own. */
    internal fun showOnly(objectIndexes: Collection<Int>) {
        val handle = handle
        val shown = objectIndexes.toHashSet()
        val count = FPDFPage_CountObjects(handle)
        for (i in 0 until count) {
            FPDFPageObj_SetIsActive(FPDFPage_GetObject(handle, i), if (shown.contains(i)) 1 else 0)
        }
    }

    private fun getObjectHandle(objectIndex: Int): Pointer? {
        val handle = handle
        return if (objectIndex >= 0 && objectIndex < FPDFPage_CountObjects(handle)) FPDFPage_GetObject(handle, objectIndex) else null
    }

    internal fun setRotation(quarterTurns: Int) = FPDFPage_SetRotation(handle, quarterTurns)

    internal fun markContentDirty() {
        contentDirty = true
    }

    internal fun commitContent() {
        val handle = _handle
        if (!contentDirty || handle == null) {
            return
        }

        if (FPDFPage_GenerateContent(handle) == 0) {
            throw PdfException(PdfErrorCode.OperationFailed, "PDFium couldn't rewrite the page content.")
        }

        contentDirty = false
    }

    internal fun close() {
        val handle = _handle
        if (handle != null) {
            FPDF_ClosePage(handle)
            _handle = null
        }
    }

    private companion object {
        const val MAX_RENDER_PIXELS = 120_000_000L

        fun isBetterHit(candidate: PdfTextRun, current: PdfTextRun): Boolean {
            // Visible text wins over invisible OCR layers, then the most specific run, then the topmost one.
            if (candidate.isInvisible != current.isInvisible) {
                return !candidate.isInvisible
            }

            if (candidate.bounds.area != current.bounds.area) {
                return candidate.bounds.area < current.bounds.area
            }

            return candidate.objectIndex > current.objectIndex
        }
    }
}
