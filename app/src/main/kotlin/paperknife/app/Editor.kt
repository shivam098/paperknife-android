package paperknife.app

import android.graphics.Bitmap
import paperknife.core.DocumentSession
import paperknife.core.EditResult
import paperknife.core.EditStatus
import paperknife.core.PdfErrorCode
import paperknife.core.PdfException
import paperknife.core.PdfRect
import paperknife.core.PdfSnapshot
import paperknife.core.PdfTextRun
import paperknife.core.RenderedBitmap
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
 */
class Editor private constructor(private val session: DocumentSession) {
    companion object {
        fun open(bytes: ByteArray, password: String?): Editor = engine {
            Editor(DocumentSession.fromSnapshot(PdfSnapshot.fromBytes(bytes), password))
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

        private fun EditResult.toApp() = EditOutcome(
            when (status) {
                EditStatus.Applied -> EditOutcome.Status.Applied
                EditStatus.NoChange -> EditOutcome.Status.NoChange
                EditStatus.Rejected -> EditOutcome.Status.Rejected
            },
            message, warnings, details, textRun?.toApp(),
        )

        /** PDFium's BGRA pixels, which are Android's ARGB colours read as little-endian integers. */
        private fun RenderedBitmap.toBitmap(): Bitmap {
            val colors = IntArray((stride / 4) * height)
            ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(colors)
            return Bitmap.createBitmap(colors, 0, stride / 4, width, height, Bitmap.Config.ARGB_8888)
        }
    }

    /** The session's state; an empty one once the session is closed, as on Windows. */
    val state: SessionState
        get() = try {
            SessionState(session.pageCount, session.canUndo, session.canRedo, session.isModified, session.signatureCount)
        } catch (e: IllegalStateException) {
            SessionState()
        }

    /** The page's displayed width and height in points. */
    fun pageSize(page: Int): Pair<Float, Float> = engine { session.getPageSize(page).let { it.width to it.height } }

    fun render(page: Int, width: Int, height: Int): PageBitmap = engine {
        val rendered = session.renderPage(page, width, height)
        PageBitmap(rendered.toBitmap(), rendered.width, rendered.height)
    }

    fun textRuns(page: Int): List<TextRun> = engine { session.getTextRuns(page).map { it.toApp() } }

    fun textRun(page: Int, objectIndex: Int): TextRun? = engine { session.getTextRun(page, objectIndex)?.toApp() }

    fun hitTest(page: Int, width: Int, height: Int, x: Int, y: Int): TextRun? =
        engine { session.hitTestText(page, width, height, x, y)?.toApp() }

    /** The rectangle in the pixels of a page drawn at this size, or null when it can't be mapped. */
    fun mapToPixels(page: Int, rect: PageRect, width: Int, height: Int): PixelRect? = engine {
        val mapped = session.mapToPixels(page, PdfRect(rect.left, rect.bottom, rect.right, rect.top), width, height)
        PixelRect(mapped.x, mapped.y, mapped.width, mapped.height)
    }

    fun editBlockers(page: Int): List<String> = engine { session.getTextEditBlockers(page) }

    fun replaceText(page: Int, objectIndex: Int, text: String): EditOutcome = engine { session.replaceText(page, objectIndex, text).toApp() }

    fun rotatePage(page: Int, quarterTurns: Int): EditOutcome = engine { session.rotatePage(page, quarterTurns).toApp() }

    fun deletePage(page: Int): EditOutcome = engine { session.deletePage(page).toApp() }

    fun appendDocument(bytes: ByteArray): EditOutcome = engine { session.appendDocument(PdfSnapshot.fromBytes(bytes)).toApp() }

    fun undo(): Boolean = engine { session.undo() }

    fun redo(): Boolean = engine { session.redo() }

    /**
     * Writes the document to [destination], then records it as saved. If writing or flushing
     * fails, the document stays unsaved.
     */
    fun saveTo(destination: OutputStream): Unit = engine { session.saveTo(destination) }

    fun close() = session.close()
}