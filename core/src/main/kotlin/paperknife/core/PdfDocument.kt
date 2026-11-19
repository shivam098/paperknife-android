package paperknife.core

import com.sun.jna.Memory
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import paperknife.core.pdfium.FpdfFileWrite
import paperknife.core.pdfium.PdfiumLibrary
import paperknife.core.pdfium.PdfiumNative.FPDF_CloseDocument
import paperknife.core.pdfium.PdfiumNative.FPDF_CreateNewDocument
import paperknife.core.pdfium.PdfiumNative.FPDF_GetDocPermissions
import paperknife.core.pdfium.PdfiumNative.FPDF_GetPageCount
import paperknife.core.pdfium.PdfiumNative.FPDF_GetSecurityHandlerRevision
import paperknife.core.pdfium.PdfiumNative.FPDF_GetSignatureCount
import paperknife.core.pdfium.PdfiumNative.FPDF_ImportPagesByIndex
import paperknife.core.pdfium.PdfiumNative.FPDF_LoadMemDocument64
import paperknife.core.pdfium.PdfiumNative.FPDF_LoadPage
import paperknife.core.pdfium.PdfiumNative.FPDF_MovePages
import paperknife.core.pdfium.PdfiumNative.FPDF_NO_INCREMENTAL
import paperknife.core.pdfium.PdfiumNative.FPDF_SaveAsCopy
import paperknife.core.pdfium.PdfiumNative.FPDFPage_Delete
import paperknife.core.pdfium.PdfiumNative.permissionAssemble
import paperknife.core.pdfium.PdfiumNative.permissionCopy
import paperknife.core.pdfium.PdfiumNative.permissionModifyContents
import paperknife.core.pdfium.SizeT
import paperknife.core.pdfium.toNativeLong
import paperknife.core.pdfium.toUInt32
import paperknife.core.support.DotNetUtf8
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A PDF loaded into PDFium. Public members only read; mutations are internal so that every change
 * goes through `DocumentSession`, which verifies it before committing. [close] is C#'s `Dispose`.
 */
class PdfDocument private constructor(
    handle: Pointer,
    /**
     * The native copy of the snapshot the document was loaded from, which PDFium reads in place
     * until the document is closed (C# keeps the snapshot's pinned bytes alive instead).
     */
    private var buffer: Memory?,
) : AutoCloseable {
    private val pages = LinkedHashMap<Int, PdfPage>()
    private var _handle: Pointer? = handle

    val pageCount: Int
        get() = synchronized(PdfiumLibrary.sync) {
            FPDF_GetPageCount(handle)
        }

    /** Number of digital signatures. Editing a signed PDF invalidates its signatures. */
    val signatureCount: Int
        get() = synchronized(PdfiumLibrary.sync) {
            maxOf(0, FPDF_GetSignatureCount(handle))
        }

    /** Whether the author allows changing page content (always true for unprotected PDFs). */
    val allowsContentChanges: Boolean get() = (permissions and permissionModifyContents) != 0u

    /**
     * Whether the author allows inserting, rotating or deleting pages. The separate "assemble"
     * permission only exists from security handler revision 3; before that, it needs "modify".
     */
    val allowsPageChanges: Boolean
        get() = allowsContentChanges || (securityHandlerRevision >= 3 && (permissions and permissionAssemble) != 0u)

    /** Whether the author allows copying this document's pages into another PDF. */
    val allowsCopyingPages: Boolean get() = (permissions and permissionCopy) != 0u && allowsPageChanges

    internal val isDisposed: Boolean get() = _handle == null

    internal val handle: Pointer
        get() = _handle ?: throw IllegalStateException("Cannot access a disposed object.\nObject name: 'PaperKnife.Core.PdfDocument'.")

    private val permissions: UInt
        get() = synchronized(PdfiumLibrary.sync) {
            FPDF_GetDocPermissions(handle).toUInt32()
        }

    private val securityHandlerRevision: Int
        get() = synchronized(PdfiumLibrary.sync) {
            FPDF_GetSecurityHandlerRevision(handle)
        }

    /** Returns the page at [index]. Pages are cached and owned by the document. */
    fun getPage(index: Int): PdfPage {
        synchronized(PdfiumLibrary.sync) {
            if (index < 0) {
                throw IndexOutOfBoundsException("index ('$index') must be a non-negative value. (Parameter 'index')\nActual value was $index.")
            }

            val count = FPDF_GetPageCount(handle)
            if (index >= count) {
                throw IndexOutOfBoundsException("index ('$index') must be less than '$count'. (Parameter 'index')\nActual value was $index.")
            }

            val cached = pages[index]
            if (cached != null) {
                return cached
            }

            val handle = FPDF_LoadPage(_handle, index)
                ?: throw PdfException(PdfErrorCode.PageError, "Page ${index + 1} couldn't be read.")

            val page = PdfPage(this, handle, index)
            pages[index] = page
            return page
        }
    }

    /** Serializes the document, including pending page edits, to a new snapshot. */
    fun save(): PdfSnapshot {
        synchronized(PdfiumLibrary.sync) {
            val handle = handle
            for (page in pages.values) {
                page.commitContent()
            }

            val stream = ByteArrayOutputStream()

            // The struct holds the callback, which holds the stream: all stay strongly referenced until the call returns.
            val writer = FpdfFileWrite()
            writer.version = 1
            writer.writeBlock = object : FpdfFileWrite.WriteBlock {
                override fun invoke(self: Pointer?, data: Pointer?, size: NativeLong): Int = writeBlock(stream, data, size)
            }

            if (FPDF_SaveAsCopy(handle, writer, FPDF_NO_INCREMENTAL.toNativeLong()) == 0 || stream.size() == 0) {
                throw PdfException(PdfErrorCode.OperationFailed, "The PDF couldn't be saved.")
            }

            return PdfSnapshot.fromStream(stream)
        }
    }

    override fun close() {
        synchronized(PdfiumLibrary.sync) {
            val handle = _handle ?: return
            closeAllPages(commit = false)
            FPDF_CloseDocument(handle)
            _handle = null
            buffer?.close()
            buffer = null
        }
    }

    internal fun rotatePage(index: Int, quarterTurns: Int) {
        synchronized(PdfiumLibrary.sync) {
            val page = getPage(index)
            page.setRotation((((page.rotation + quarterTurns) % 4) + 4) % 4)
        }
    }

    internal fun deletePage(index: Int) {
        synchronized(PdfiumLibrary.sync) {
            closeAllPages(commit = true)
            FPDFPage_Delete(handle, index)
        }
    }

    internal fun movePage(fromIndex: Int, toIndex: Int): Boolean {
        synchronized(PdfiumLibrary.sync) {
            closeAllPages(commit = true)
            return FPDF_MovePages(handle, intArrayOf(fromIndex), NativeLong(1), toIndex) != 0
        }
    }

    internal fun importPages(source: PdfDocument, insertAt: Int, pageIndices: List<Int>? = null): Boolean {
        synchronized(PdfiumLibrary.sync) {
            closeAllPages(commit = true)
            val indices = pageIndices?.toIntArray() ?: IntArray(source.pageCount) { it }
            if (indices.isEmpty()) {
                return true
            }

            return FPDF_ImportPagesByIndex(handle, source.handle, indices, NativeLong(indices.size.toLong()), insertAt) != 0
        }
    }

    private fun closeAllPages(commit: Boolean) {
        for (page in pages.values) {
            if (commit) {
                page.commitContent()
            }

            page.close()
        }

        pages.clear()
    }

    companion object {
        fun load(snapshot: PdfSnapshot, password: String? = null): PdfDocument {
            PdfiumLibrary.ensureInitialized()
            synchronized(PdfiumLibrary.sync) {
                val passwordBytes = if (password == null) null else DotNetUtf8.getBytes(password + '\u0000')
                val buffer = snapshot.toNativeMemory()
                val handle = FPDF_LoadMemDocument64(buffer, SizeT(snapshot.length.toLong()), passwordBytes)
                if (handle == null) {
                    val error = PdfException.fromLastError()
                    buffer.close()
                    throw error
                }

                return PdfDocument(handle, buffer)
            }
        }

        internal fun createNew(): PdfDocument {
            PdfiumLibrary.ensureInitialized()
            synchronized(PdfiumLibrary.sync) {
                val handle = FPDF_CreateNewDocument()
                    ?: throw PdfException(PdfErrorCode.OperationFailed, "A new PDF couldn't be created.")

                return PdfDocument(handle, null)
            }
        }

        /** C#'s `WriteBlock`, which PDFium calls with every block of the file it saves. */
        private fun writeBlock(stream: OutputStream, data: Pointer?, size: NativeLong): Int {
            try {
                val length = Math.toIntExact(if (NativeLong.SIZE == 4) size.toLong() and 0xFFFF_FFFFL else size.toLong())
                if (length > 0) {
                    stream.write(data!!.getByteArray(0, length))
                }

                return 1
            } catch (_: Throwable) {
                // Exceptions must not cross into native code; PDFium treats 0 as a failed write.
                return 0
            }
        }
    }
}
