package paperknife.core

import paperknife.core.fonts.Type1Font
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Editing text in PDFs made with TeX, whose fonts have no space glyph and only the glyphs a
 * document uses. The other tests of PaperKnife for Windows' TexFontTests edit document sessions,
 * which the conformance corpus runs (its tex- cases).
 */
class TexFontTests {
    @Test
    fun type1FontReadsAProgramFullOfJunkQuickly() {
        // Comments full of half-written keys made an earlier reader take time quadratic in their length.
        val (clear, encrypted, trailer) = TexPdf.subset(TexSubset(junk = 2_000_000))
        val data = (clear + encrypted + trailer).toByteArray(Charsets.ISO_8859_1)
        val start = System.nanoTime()

        val font = Type1Font.fromEmbedded(data, clear.length, encrypted.length)

        assertNotNull(font)
        assertNull(font.differenceFrom(TexPdf.full))
        val milliseconds = (System.nanoTime() - start) / 1_000_000
        assertTrue(milliseconds in 0..3000, "It took $milliseconds ms.")
    }
}
