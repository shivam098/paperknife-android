package paperknife.core.conformance

import java.io.File

/**
 * The files of the conformance corpus (conformance/README.md) by their paths in it, such as
 * "cases/edit-replace-line.json" or "documents/90f66aab2200.pdf": the repository's conformance/
 * folder on the JVM, and the instrumented test's assets on Android.
 */
interface CorpusFiles {
    /** The file's bytes, or null when the corpus has no such file. */
    fun read(path: String): ByteArray?

    /** The names of the files in one of the corpus's folders, such as "cases". */
    fun list(folder: String): List<String>

    companion object {
        /** The corpus in a folder, such as conformance/ in the repository. */
        fun fromDirectory(directory: File): CorpusFiles = object : CorpusFiles {
            override fun read(path: String): ByteArray? = File(directory, path).takeIf { file -> file.isFile }?.readBytes()

            override fun list(folder: String): List<String> = File(directory, folder).list().orEmpty().toList()
        }
    }
}
