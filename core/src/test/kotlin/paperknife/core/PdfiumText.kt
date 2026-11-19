package paperknife.core

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.lang.ref.Reference

/**
 * The text PDFium extracts from a page, for tests that check that PDFium reads what PaperKnife
 * writes, where PaperKnife for Windows' tests open a DocumentSession (which the PDFium layer
 * ports). Calls hold the lock [Toolchain] takes, as PDFium isn't thread-safe.
 */
internal object PdfiumText {
    @Suppress("FunctionName")
    private interface Pdfium : Library {
        fun FPDF_InitLibrary()

        fun FPDF_LoadMemDocument64(data: Pointer, size: Toolchain.SizeT, password: String?): Pointer?

        fun FPDF_CloseDocument(document: Pointer)

        fun FPDF_LoadPage(document: Pointer, index: Int): Pointer?

        fun FPDF_ClosePage(page: Pointer)

        fun FPDFText_LoadPage(page: Pointer): Pointer?

        fun FPDFText_ClosePage(textPage: Pointer)

        fun FPDFText_CountChars(textPage: Pointer): Int

        fun FPDFText_GetText(textPage: Pointer, startIndex: Int, count: Int, result: ByteArray): Int
    }

    private val pdfium: Pdfium by lazy {
        Native.load("pdfium", Pdfium::class.java).also { library -> library.FPDF_InitLibrary() }
    }

    /** The text of a page as PDFium extracts it, or null when PDFium can't open the document or the page. */
    fun pageText(data: ByteArray, pageIndex: Int): String? = synchronized(Toolchain) {
        // PDFium reads the document from this memory until it is closed.
        val memory = Memory(data.size.toLong())
        memory.write(0, data, 0, data.size)
        try {
            val document = pdfium.FPDF_LoadMemDocument64(memory, Toolchain.SizeT(data.size.toLong()), null) ?: return null
            try {
                val page = pdfium.FPDF_LoadPage(document, pageIndex) ?: return null
                try {
                    val text = pdfium.FPDFText_LoadPage(page) ?: return null
                    try {
                        val count = pdfium.FPDFText_CountChars(text)
                        val buffer = ByteArray((count + 1) * 2)
                        val written = pdfium.FPDFText_GetText(text, 0, count, buffer)

                        // UTF-16LE, with a terminating NUL that the count includes.
                        String(buffer, 0, maxOf(0, written - 1) * 2, Charsets.UTF_16LE)
                    } finally {
                        pdfium.FPDFText_ClosePage(text)
                    }
                } finally {
                    pdfium.FPDF_ClosePage(page)
                }
            } finally {
                pdfium.FPDF_CloseDocument(document)
            }
        } finally {
            Reference.reachabilityFence(memory)
        }
    }
}
