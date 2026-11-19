package paperknife.core

import paperknife.core.pdfium.PdfiumLibrary

/** Whole-document tools that create new PDFs and never modify their inputs. */
object PdfTools {
    /** Combines documents, in order, into a new PDF. */
    fun merge(documents: List<PdfSnapshot>): PdfSnapshot {
        if (documents.isEmpty()) {
            throw IllegalArgumentException("Choose at least one PDF to merge. (Parameter 'documents')")
        }

        synchronized(PdfiumLibrary.sync) {
            PdfDocument.createNew().use { output ->
                for (snapshot in documents) {
                    PdfDocument.load(snapshot).use { source ->
                        throwIfCopyingRestricted(source)
                        if (!output.importPages(source, output.pageCount)) {
                            throw PdfException(PdfErrorCode.OperationFailed, "A document couldn't be merged.")
                        }
                    }
                }

                return output.save()
            }
        }
    }

    /** Copies the given pages, in the given order, into a new PDF. */
    fun extractPages(document: PdfSnapshot, pageIndices: List<Int>, password: String? = null): PdfSnapshot {
        if (pageIndices.isEmpty()) {
            throw IllegalArgumentException("Choose at least one page. (Parameter 'pageIndices')")
        }

        synchronized(PdfiumLibrary.sync) {
            PdfDocument.load(document, password).use { source ->
                throwIfCopyingRestricted(source)
                val pageCount = source.pageCount
                for (index in pageIndices) {
                    if (index < 0) {
                        throw IndexOutOfBoundsException("pageIndices ('$index') must be a non-negative value. (Parameter 'pageIndices')\nActual value was $index.")
                    }

                    if (index >= pageCount) {
                        throw IndexOutOfBoundsException("pageIndices ('$index') must be less than '$pageCount'. (Parameter 'pageIndices')\nActual value was $index.")
                    }
                }

                PdfDocument.createNew().use { output ->
                    if (!output.importPages(source, 0, pageIndices)) {
                        throw PdfException(PdfErrorCode.OperationFailed, "The pages couldn't be extracted.")
                    }

                    return output.save()
                }
            }
        }
    }

    // Copying pages into a new, unprotected PDF would strip the author's restrictions.
    private fun throwIfCopyingRestricted(source: PdfDocument) {
        if (!source.allowsCopyingPages) {
            throw PdfException(PdfErrorCode.Restricted, "The author of this PDF restricted copying its pages.")
        }
    }
}
