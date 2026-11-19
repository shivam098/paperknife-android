package paperknife.core

import paperknife.core.fonts.BundledFonts
import paperknife.core.fonts.FontSource
import java.io.File

/** The repository the tests run in, and the fonts PaperKnife ships, which the tests read from third_party/. */
internal object TestFiles {
    /** The repository's root, which Gradle passes in the system property paperknife.root. */
    val root: File by lazy {
        File(System.getProperty("paperknife.root") ?: error("The tests need the system property paperknife.root (see core/build.gradle.kts)."))
    }

    private val fonts: FontSource by lazy {
        // The repository keeps each family in third_party/<family>/fonts; the app, in fonts/<family>.
        val thirdParty = File(root, "third_party")
        FontSource.fromDirectories(
            listOf("amsfonts", "cm-super", "lm").associateWith { family -> File(thirdParty, "$family/fonts") },
            File(thirdParty, "agl/glyphlist.txt"),
        )
    }

    /** Has PaperKnife read its fonts and the Adobe Glyph List from third_party/, as the app reads them from its assets. */
    fun useBundledFonts() {
        synchronized(this) {
            if (BundledFonts.source !== fonts) {
                BundledFonts.source = fonts
            }
        }
    }
}
