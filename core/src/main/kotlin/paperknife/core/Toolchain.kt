package paperknife.core

import com.sun.jna.IntegerType
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Proves the toolchain works: PDFium loads and runs through JNA, on the JVM for tests and on
 * Android. The engine itself is a port of PaperKnife for Windows' engine (see docs/PORTING.md).
 */
internal object Toolchain {
    /** C's size_t, which is 32 bits on 32-bit Android and 64 bits elsewhere. */
    class SizeT(value: Long = 0) : IntegerType(Native.SIZE_T_SIZE, value, true) {
        override fun toByte() = toLong().toByte()

        override fun toShort() = toLong().toShort()
    }

    @Suppress("FunctionName")
    private interface Pdfium : Library {
        fun FPDF_InitLibrary()

        fun FPDF_LoadMemDocument64(data: ByteArray, size: SizeT, password: String?): Pointer?

        fun FPDF_GetPageCount(document: Pointer): Int

        fun FPDF_CloseDocument(document: Pointer)
    }

    private val pdfium: Pdfium by lazy {
        Native.load("pdfium", Pdfium::class.java).also { it.FPDF_InitLibrary() }
    }

    /** The page count of a PDF, or null when PDFium can't open it. */
    fun pdfiumPageCount(data: ByteArray): Int? = synchronized(this) {
        val document = pdfium.FPDF_LoadMemDocument64(data, SizeT(data.size.toLong()), null) ?: return null
        try {
            pdfium.FPDF_GetPageCount(document)
        } finally {
            pdfium.FPDF_CloseDocument(document)
        }
    }
}
