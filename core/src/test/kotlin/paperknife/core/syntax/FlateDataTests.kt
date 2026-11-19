package paperknife.core.syntax

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Flate data decodes as PaperKnife for Windows decodes it with .NET's ZLibStream, damaged data
 * included; Java's own zlib streams handle some of it differently.
 */
class FlateDataTests {
    private val text = "BT /F1 12 Tf 72 700 Td (Hello) Tj ET\n".repeat(400).toByteArray(Charsets.ISO_8859_1)
    private val deflated = StreamFilters.deflate(text)

    @Test
    fun truncatedDataDecodesToWhatItHolds() {
        val decoded = inflate(deflated.copyOf(deflated.size / 2))!!

        assertTrue(decoded.isNotEmpty() && decoded.size < text.size)
        assertContentEquals(text.copyOf(decoded.size), decoded)

        // Without its checksum the data is all there: .NET doesn't check that the stream ends.
        assertContentEquals(text, inflate(deflated.copyOf(deflated.size - 4)))
    }

    @Test
    fun damagedDataIsRefused() {
        val wrongChecksum = deflated.copyOf()
        wrongChecksum[wrongChecksum.size - 1] = (wrongChecksum[wrongChecksum.size - 1] + 1).toByte()

        assertNull(inflate(wrongChecksum))
        assertNull(inflate(deflated.copyOfRange(2, deflated.size)), "Deflate data without the zlib header isn't zlib data.")

        // A prefix ends before the damage.
        assertContentEquals(text.copyOf(10), StreamFilters.inflatePrefix(wrongChecksum, 0, wrongChecksum.size, 10))
    }

    @Test
    fun dataAfterTheEndIsIgnored() {
        assertContentEquals(text, inflate(deflated + "garbage".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun noDataDeflatesAndInflatesToNothing() {
        assertEquals(0, StreamFilters.deflate(ByteArray(0)).size)
        assertEquals(0, inflate(ByteArray(0))!!.size)
    }

    @Test
    fun dataThatNeedsAPresetDictionaryThrows() {
        // The FDICT flag, then a dictionary's checksum: .NET throws a ZLibException, which nothing catches.
        val withDictionary = byteArrayOf(0x78, 0xBB.toByte(), 0, 0, 0, 1) + deflated.copyOfRange(2, deflated.size)

        assertFailsWith<ZLibException> { inflate(withDictionary) }
    }

    private fun inflate(data: ByteArray): ByteArray? = StreamFilters.decode(data, 0, data.size, listOf("FlateDecode"), emptyList(), null)
}
