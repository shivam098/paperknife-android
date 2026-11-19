package paperknife.core.pdfium

internal object PdfiumLibrary {
    /**
     * PDFium keeps global state and is not thread-safe, so every native call in the process is
     * serialized on this (reentrant) lock.
     */
    @JvmField
    val sync = Any()

    private var initialized = false

    fun ensureInitialized() {
        synchronized(sync) {
            if (initialized) {
                return
            }

            PdfiumNative.FPDF_InitLibrary()
            initialized = true
        }
    }
}
