package paperknife.core

import java.io.File

/** Documents of the conformance corpus, which some tests use instead of building their own. */
internal object CorpusDocuments {
    val folder = File(System.getProperty("paperknife.root"), "conformance")

    /** RawPdf.SinglePage(RawPdf.BaseContent) in PaperKnife for Windows' tests (the document of the document-text-runs case). */
    fun baseContent(): PdfSnapshot = read("documents/90f66aab2200.pdf")

    /**
     * EncryptedPdf.SinglePage(RawPdf.BaseContent, NoChangesNoCopying, revision: 3) in PaperKnife for
     * Windows' tests (the document of the permissions-text-edits-refused case): RC4, an empty user
     * password, printing allowed, changing, copying and annotating forbidden, assembling allowed.
     */
    fun noChangesNoCopying(): PdfSnapshot = read("documents/5239cd3fd1d2.pdf")

    fun read(path: String): PdfSnapshot = PdfSnapshot.fromBytes(File(folder, path).readBytes())
}
