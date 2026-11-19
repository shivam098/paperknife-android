package paperknife.app

import android.graphics.Bitmap
import paperknife.core.PdfDocument
import paperknife.core.PdfErrorCode
import paperknife.core.PdfException
import paperknife.core.PdfRect
import paperknife.core.PdfSnapshot
import paperknife.core.PdfTextRun
import paperknife.core.RenderedBitmap
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The state of a document session, copied when a command finishes, so the screen never reads the session while the engine works. */
data class SessionState(
    val pageCount: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isModified: Boolean = false,
    val signatureCount: Int = 0,
)

/** A rectangle in PDF page space: points, origin at the bottom-left, y pointing up (the engine's PdfRect). */
data class PageRect(val left: Float, val bottom: Float, val right: Float, val top: Float) {
    fun inflated(amount: Float) = PageRect(left - amount, bottom - amount, right + amount, top + amount)
}

/** A rectangle in the pixels of a drawn page: origin at the top-left, y pointing down (the engine's PixelRect). */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** A piece of text PaperKnife edits as one: usually a line or a table cell (the engine's PdfTextRun). */
data class TextRun(
    val pageIndex: Int,
    val objectIndex: Int,
    val objectCount: Int,
    val text: String,
    val bounds: PageRect,
    val fontName: String,
    val isFontEmbedded: Boolean,
    val isFontSubset: Boolean,
    val fontSize: Float,
    val isInvisible: Boolean,
)

/** The outcome of an edit or page command (the engine's EditResult). A refused command leaves the document exactly as it was. */
data class EditOutcome(
    val status: Status,
    val message: String,
    val warnings: List<String>,
    val details: List<String>,
    val textRun: TextRun?,
) {
    enum class Status { Applied, NoChange, Rejected }
}

/** A failure of an engine call, with the message to show. */
class EditorException(message: String, val passwordRequired: Boolean = false) : Exception(message)

/** A page drawn for the screen. */
class PageBitmap(val bitmap: Bitmap, val width: Int, val height: Int)

/**
 * PaperKnife's engine as the app uses it: a DocumentSession, ported from PaperKnife for Windows.
 * Calls can take long, so EditorViewModel makes them on its engine thread, one at a time.
 *
 * Until the session is ported, this opens, draws and reads documents with the engine's document
 * layer, and every command that would change a document fails with a message.
 */
class Editor private constructor(private val document: PdfDocument) {
    companion object {
        private const val NOT_YET = "This build of PaperKnife can show PDFs but can't change them yet."

        fun open(bytes: ByteArray, password: String?): Editor = engine {
            Editor(PdfDocument.load(PdfSnapshot.fromBytes(bytes), password))
        }

        /** Makes an engine call, turning the engine's failures into an EditorException with its message. */
        private inline fun <T> engine(call: () -> T): T = try {
            call()
        } catch (e: PdfException) {
            throw EditorException(e.message, passwordRequired = e.errorCode == PdfErrorCode.PasswordRequired)
        }

        private fun PdfTextRun.toApp() = TextRun(
            pageIndex, objectIndex, objectCount, text, PageRect(bounds.left, bounds.bottom, bounds.right, bounds.top),
            fontName, isFontEmbedded, isFontSubset, fontSize, isInvisible,
        )

        /** PDFium's BGRA pixels, which are Android's ARGB colours read as little-endian integers. */
        private fun RenderedBitmap.toBitmap(): Bitmap {
            val colors = IntArray((stride / 4) * height)
            ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(colors)
            return Bitmap.createBitmap(colors, 0, stride / 4, width, height, Bitmap.Config.ARGB_8888)
        }
    }

    val state: SessionState get() = SessionState(pageCount = document.pageCount, signatureCount = document.signatureCount)

    /** The page's displayed width and height in points. */
    fun pageSize(page: Int): Pair<Float, Float> = engine { document.getPage(page).let { it.width to it.height } }

    fun render(page: Int, width: Int, height: Int): PageBitmap = engine {
        val rendered = document.getPage(page).render(width, height)
        PageBitmap(rendered.toBitmap(), rendered.width, rendered.height)
    }

    fun textRuns(page: Int): List<TextRun> = engine { document.getPage(page).getTextRuns().map { it.toApp() } }

    fun textRun(page: Int, objectIndex: Int): TextRun? = engine { document.getPage(page).tryGetTextRun(objectIndex)?.toApp() }

    fun hitTest(page: Int, width: Int, height: Int, x: Int, y: Int): TextRun? =
        engine { document.getPage(page).hitTestText(width, height, x, y)?.toApp() }

    /** The rectangle in the pixels of a page drawn at this size, or null when it can't be mapped. */
    fun mapToPixels(page: Int, rect: PageRect, width: Int, height: Int): PixelRect? = engine {
        val mapped = document.getPage(page).mapToPixels(PdfRect(rect.left, rect.bottom, rect.right, rect.top), width, height)
        PixelRect(mapped.x, mapped.y, mapped.width, mapped.height)
    }

    fun editBlockers(page: Int): List<String> = emptyList()

    fun replaceText(page: Int, objectIndex: Int, text: String): EditOutcome = throw EditorException(NOT_YET)

    fun rotatePage(page: Int, quarterTurns: Int): EditOutcome = throw EditorException(NOT_YET)

    fun deletePage(page: Int): EditOutcome = throw EditorException(NOT_YET)

    fun appendDocument(bytes: ByteArray): EditOutcome = throw EditorException(NOT_YET)

    fun undo(): Boolean = false

    fun redo(): Boolean = false

    /**
     * Writes the document to [destination], then records it as saved, through a complete copy in
     * [scratch] that is deleted afterwards. If writing to the destination fails, the document stays
     * unsaved.
     */
    fun saveTo(destination: OutputStream, scratch: File): Unit = throw EditorException(NOT_YET)

    fun close() = document.close()
}
