package paperknife.app

import android.app.Application
import paperknife.core.fonts.BundledFonts
import paperknife.core.fonts.FontSource

/** Points the engine at the fonts the app ships as assets before any document is opened. */
class PaperKnifeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BundledFonts.source = bundledFonts()
    }

    /** The fonts of fonts/<family>/ and fonts/glyphlist.txt in the assets, as bundleAssets lays them out. */
    private fun bundledFonts(): FontSource {
        val assets = assets
        val fonts = FONT_FAMILIES.flatMap { family ->
            assets.list("fonts/$family").orEmpty().filter { it.endsWith(".pfb") }.map { "$family/$it" }
        }.sorted()
        return FontSource(fonts, { path -> assets.open("fonts/$path") }, { assets.open("fonts/glyphlist.txt") })
    }

    private companion object {
        val FONT_FAMILIES = listOf("amsfonts", "cm-super", "lm")
    }
}
