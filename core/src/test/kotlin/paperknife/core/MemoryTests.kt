package paperknife.core

import java.lang.management.ManagementFactory
import java.lang.ref.Reference
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What PaperKnife keeps in memory while pages are viewed. JUnit runs these tests one at a time, as
 * it runs every test here, so the memory they measure is theirs alone.
 *
 * The measurements and limits are PaperKnife for Windows', on the JVM's heap: the memory in use
 * after full collections, before and after the pages are viewed. A character map takes about twice
 * as much memory on the JVM as in .NET (a map entry, a boxed code and a string of its own: about
 * 100 bytes a code, where .NET needs about 50), so the limits leave less room here, but the
 * shortcomings they guard against would exceed them many times over.
 */
class MemoryTests {
    @Test
    fun viewingPagesWhoseFontsShareACharacterMapKeepsOneCopyOfIt() {
        // Every page has a font of its own, and all of them share one map of 262,144 codes, which
        // takes about 15 MB once read (about 30 MB on the JVM). Fonts used to keep a copy each: as
        // much again per page viewed.
        val retained = retainedAfterViewing(pages(48, sharedMap = true, codes = 2 * RANGE_SIZE))

        assertTrue(retained < 48 * MEGABYTE, "${retained / MEGABYTE} MB stayed in memory.")
    }

    @Test
    fun viewingManyPagesWithLargeCharacterMapsKeepsOnlyRecentPagesFonts() {
        // Every page's font has a map of its own, of 32,768 codes: about 2 MB once read (about 4 MB
        // on the JVM). Only the fonts of the last pages viewed need to stay.
        val retained = retainedAfterViewing(pages(160, sharedMap = false, codes = RANGE_SIZE / 4))

        assertTrue(retained < 96 * MEGABYTE, "${retained / MEGABYTE} MB stayed in memory.")
    }

    /** The memory that stays in use after every page is viewed, while the document is still open. */
    private fun retainedAfterViewing(pdf: PdfSnapshot): Long {
        DocumentSession.fromSnapshot(pdf).use { session ->
            val before = usedAfterFullCollection()
            for (page in 0 until session.pageCount) {
                session.getTextEditBlockers(page)
            }

            val retained = usedAfterFullCollection() - before
            Reference.reachabilityFence(session)
            return retained
        }
    }

    /**
     * C#'s `GC.GetTotalMemory(forceFullCollection: true)`: the heap in use once collections no
     * longer free anything. `System.gc()` runs a full, compacting collection.
     */
    private fun usedAfterFullCollection(): Long {
        val memory = ManagementFactory.getMemoryMXBean()
        var used = Long.MAX_VALUE
        repeat(4) {
            System.gc()
            used = minOf(used, memory.heapMemoryUsage.used)
        }

        return used
    }

    /**
     * Pages that each draw a word in a Helvetica font of their own, whose ToUnicode map covers
     * [codes] codes: one map for all of them, or one map each.
     */
    private fun pages(count: Int, sharedMap: Boolean, codes: Int): PdfSnapshot {
        val map = RawPdf.stream(characterMap(codes))
        val objects = mutableListOf("<< /Type /Catalog /Pages 2 0 R >>", "")
        if (sharedMap) {
            objects.add(map)
        }

        val kids = ArrayList<Int>()
        for (i in 0 until count) {
            val page = objects.size + 1
            kids.add(page)
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents ${page + 1} 0 R /Resources << /Font << /F1 ${page + 2} 0 R >> >> >>")
            objects.add(RawPdf.stream("BT /F1 24 Tf 72 700 Td (Hello) Tj ET"))
            objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding /ToUnicode ${if (sharedMap) 3 else page + 3} 0 R >>")
            if (!sharedMap) {
                objects.add(map)
            }
        }

        objects[1] = "<< /Type /Pages /Kids [${kids.joinToString(" ") { k -> "$k 0 R" }}] /Count $count >>"
        return PdfSnapshot.fromBytes(RawPdf.writeWithTable(objects))
    }

    /** A ToUnicode CMap whose bfranges map [codes] codes, each to a text of its own. */
    private fun characterMap(codes: Int): String {
        val ranges = (codes + RANGE_SIZE - 1) / RANGE_SIZE
        val map = StringBuilder("/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /Large def /CMapType 2 def\n")
            .append("$ranges beginbfrange\n")
        for (start in 0 until codes step RANGE_SIZE) {
            map.append(String.format(Locale.ROOT, "<%06X> <%06X> <4E00>\n", start, minOf(codes, start + RANGE_SIZE) - 1))
        }

        return map.append("endbfrange\nendcmap CMapName currentdict /CMap defineresource pop end end").toString()
    }

    private companion object {
        const val MEGABYTE = 1024L * 1024

        // The most codes one bfrange of a character map may cover for PaperKnife to read it.
        const val RANGE_SIZE = 1 shl 17
    }
}
