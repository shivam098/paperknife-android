package paperknife.core

import paperknife.core.pdfium.PdfiumLibrary
import paperknife.core.pdfium.PdfiumNative

enum class PdfErrorCode {
    Unknown,
    InvalidFormat,
    PasswordRequired,
    UnsupportedSecurity,
    PageError,
    OperationFailed,
    Restricted,
}

/** A PDF couldn't be opened, read or written. [message] is user-facing. */
class PdfException(val errorCode: PdfErrorCode, override val message: String) : Exception(message) {
    internal companion object {
        /** Must be called while holding [PdfiumLibrary.sync], right after the failing call (see [PdfiumNative.lastError]). */
        fun fromLastError(): PdfException = when (PdfiumNative.lastError()) {
            PdfiumNative.FPDF_ERR_FILE -> PdfException(PdfErrorCode.InvalidFormat, "The file couldn't be read.")
            PdfiumNative.FPDF_ERR_FORMAT -> PdfException(PdfErrorCode.InvalidFormat, "This file isn't a PDF, or it is damaged.")
            PdfiumNative.FPDF_ERR_PASSWORD -> PdfException(PdfErrorCode.PasswordRequired, "This PDF is protected. Enter its password to open it.")
            PdfiumNative.FPDF_ERR_SECURITY -> PdfException(PdfErrorCode.UnsupportedSecurity, "This PDF uses a security scheme PaperKnife doesn't support.")
            PdfiumNative.FPDF_ERR_PAGE -> PdfException(PdfErrorCode.PageError, "A page in this PDF couldn't be read.")
            else -> PdfException(PdfErrorCode.Unknown, "PDFium couldn't process this file.")
        }
    }
}
