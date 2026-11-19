package paperknife.core

import java.io.File
import java.util.UUID

/** Folders for the files tests write: in core/build/test-folders rather than the system's temporary folder. */
internal object TestFolders {
    /** A new, empty folder whose name starts with [prefix] (C#'s `Directory.CreateTempSubdirectory`). */
    fun create(prefix: String): File {
        val folder = File(TestFiles.root, "core/build/test-folders/$prefix${UUID.randomUUID().toString().replace("-", "")}")
        check(folder.mkdirs()) { "$folder couldn't be created." }
        return folder
    }
}
