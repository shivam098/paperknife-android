package paperknife.core.fonts

import paperknife.core.content.Encodings
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Where [BundledFonts] reads the fonts PaperKnife ships and the Adobe Glyph List. PaperKnife for
 * Windows compiles some in and ships the others in a folder next to the app; this app ships them as
 * assets, which aren't files (see docs/PORTING.md, rule 11), so it sets [BundledFonts.source] when
 * it starts, before it opens documents.
 *
 * @property fonts The fonts' paths relative to the fonts folder, such as "amsfonts/cmr10.pfb",
 *   "cm-super/sfrm1000.pfb" or "lm/lmr10.pfb". They are read in ordinal order, so where two fonts
 *   give themselves the same name, the first in that order counts, as on Windows.
 * @param fontOpener Opens a font by its path, or gives null when it can't.
 * @param glyphListOpener Opens glyphlist.txt, or gives null when there is none.
 */
class FontSource(
    val fonts: List<String>,
    private val fontOpener: (path: String) -> InputStream?,
    private val glyphListOpener: () -> InputStream?,
) {
    /** Opens a font of [fonts]; null or an IOException when it can't be read. */
    fun openFont(path: String): InputStream? = fontOpener(path)

    /** Opens the Adobe Glyph List; null or an IOException when it can't be read. */
    fun openGlyphList(): InputStream? = glyphListOpener()

    companion object {
        /** No fonts and no glyph list: what PaperKnife has until the app sets a source. */
        val none = FontSource(emptyList(), { null }, { null })

        /**
         * The fonts in folders: the .pfb files of each family's folder (such as "amsfonts" to
         * third_party/amsfonts/fonts in the repository, or to fonts/amsfonts in the app's layout),
         * as "<family>/<file name>", and the glyph list from its file.
         */
        fun fromDirectories(families: Map<String, File>, glyphList: File): FontSource {
            val files = HashMap<String, File>()
            for ((family, directory) in families) {
                for (file in directory.listFiles().orEmpty()) {
                    if (file.isFile && file.name.endsWith(".pfb")) {
                        files["$family/${file.name}"] = file
                    }
                }
            }

            return FontSource(files.keys.sorted(), { path -> files[path]?.inputStream() }, { glyphList.inputStream() })
        }
    }
}

/**
 * The full fonts PaperKnife ships to draw new letters in subset fonts of PDFs made with TeX, the
 * versions TeX distributions embed, all unmodified: the Type 1 Computer Modern fonts of the
 * American Mathematical Society (amsfonts 3.04, SIL Open Font License 1.1), Latin Modern (lm
 * 2.004, GUST Font License) and CM-Super (0.3.4, GNU GPL with a font exception), which the app
 * ships as assets and reads through [source]. See third_party/amsfonts, third_party/lm and
 * third_party/cm-super.
 */
object BundledFonts {
    @Volatile
    private var current = FontSource.none

    @Volatile
    private var sources: Map<String, () -> ByteArray?>? = null
    private val cache = ConcurrentHashMap<String, CachedFont>()

    /**
     * Where the fonts and the Adobe Glyph List come from. The app sets it once, when it starts;
     * until then PaperKnife has neither. Setting it forgets what was read from the previous one.
     */
    var source: FontSource
        get() = current
        set(value) {
            synchronized(this) {
                current = value
                sources = null
                cache.clear()
            }

            Encodings.forgetGlyphList()
        }

    /**
     * The full font with this PostScript name (for example "CMBX10", "LMRoman10-Regular" or
     * "SFRM1000"), or null when PaperKnife doesn't ship it.
     */
    internal fun findType1(postScriptName: String): Type1Font? {
        val read = sources()[postScriptName] ?: return null
        return cache.computeIfAbsent(postScriptName) { name ->
            val font = read()?.let { data -> Type1Font.fromPfb(data) }
            CachedFont(if (font != null && font.fontName == name) font else null)
        }.font
    }

    private fun sources(): Map<String, () -> ByteArray?> =
        sources ?: synchronized(this) { sources ?: findSources(current).also { found -> sources = found } }

    /**
     * Every font by the name its program gives itself, which isn't always its file name (Latin
     * Modern's lmr10.pfb is LMRoman10-Regular). A name from a PDF is only ever looked up here,
     * never turned into a path.
     */
    private fun findSources(source: FontSource): Map<String, () -> ByteArray?> {
        val sources = HashMap<String, () -> ByteArray?>()
        fun add(open: () -> InputStream?) {
            val header = read(open, headerOnly = true) ?: return
            val name = Type1Font.readFontName(header) ?: return
            sources.putIfAbsent(name) { read(open, headerOnly = false) }
        }

        for (path in source.fonts.filter { path -> path.endsWith(".pfb") }.sorted()) {
            add { source.openFont(path) }
        }

        return sources
    }

    /**
     * A PFB file, or with [headerOnly] its first, cleartext part; null when it can't be read. Like
     * PaperKnife for Windows, it refuses files larger than [Type1Font.MAX_PROGRAM] + 64 bytes,
     * though only when reading them whole: a source's streams needn't know their length.
     */
    private fun read(open: () -> InputStream?, headerOnly: Boolean): ByteArray? {
        try {
            val stream = open() ?: return null
            stream.use {
                if (!headerOnly) {
                    val data = readAtMost(stream, Type1Font.MAX_PROGRAM + 64 + 1)
                    return if (data.size > Type1Font.MAX_PROGRAM + 64) null else data
                }

                val segment = ByteArray(6)
                readExactly(stream, segment)
                val length = (segment[2].toInt() and 0xFF) or ((segment[3].toInt() and 0xFF) shl 8) or
                    ((segment[4].toInt() and 0xFF) shl 16) or ((segment[5].toInt() and 0xFF) shl 24)
                if ((segment[0].toInt() and 0xFF) != 0x80 || segment[1].toInt() != 1 || length <= 0 || length > Type1Font.MAX_CLEARTEXT) {
                    return null
                }

                val cleartext = ByteArray(length)
                readExactly(stream, cleartext)
                return cleartext
            }
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        }
    }

    private fun readExactly(stream: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = stream.read(buffer, read, buffer.size - read)
            if (n <= 0) {
                throw EOFException("Unable to read beyond the end of the stream.")
            }

            read += n
        }
    }

    private fun readAtMost(stream: InputStream, limit: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(81920)
        while (buffer.size() < limit) {
            val n = stream.read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
            if (n <= 0) {
                break
            }

            buffer.write(chunk, 0, n)
        }

        return buffer.toByteArray()
    }

    /** A font read, or null when it couldn't be, as ConcurrentHashMap can't hold null. */
    private class CachedFont(val font: Type1Font?)
}
