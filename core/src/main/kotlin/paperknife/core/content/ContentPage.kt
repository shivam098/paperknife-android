package paperknife.core.content

import paperknife.core.syntax.CosArray
import paperknife.core.syntax.CosDict
import paperknife.core.syntax.CosRef
import paperknife.core.syntax.CosStream
import paperknife.core.syntax.PdfFile
import paperknife.core.syntax.PdfPageNode
import paperknife.core.syntax.StreamFilters
import java.io.ByteArrayOutputStream

/** A page's decoded content and the text-showing operations in it, ready for content edits. */
internal class ContentPage(
    val file: PdfFile,
    val page: PdfPageNode,
    /** The page's content streams; an edit writes the whole content into the first. */
    val streams: List<CosRef>,
    /** The decoded content streams, joined by spaces as PDFium joins them. */
    val content: ByteArray,
    val ops: List<ContentOp>,
    /** The text layout using only widths from font dictionaries. */
    val text: ContentTextLayout,
) {
    companion object {
        /** The page's content, or null when PaperKnife can't read it with certainty. */
        fun tryLoad(file: PdfFile, pages: List<PdfPageNode>, pageIndex: Int): ContentPage? {
            val page = pages[pageIndex]
            val streams = file.getContentStreams(page.dict) ?: return null
            if (streams.map { s -> s.number }.distinct().size != streams.size) {
                return null
            }

            // Writing a new version of a stream that another page also draws would change that page too.
            val own = streams.map { s -> s.number }.toHashSet()
            for (i in pages.indices) {
                if (i != pageIndex && contentNumbers(file, pages[i].dict).any { number -> own.contains(number) }) {
                    return null
                }
            }

            // PDFium joins a page's content streams with one space; anything else would let a
            // comment at the end of one stream end differently than PDFium reads it.
            val content = ByteArrayOutputStream()
            for (reference in streams) {
                val stream = file.getObject(reference.number) as? CosStream ?: return null
                val data = file.decodeStream(stream) ?: return null
                if (content.size().toLong() + data.size > StreamFilters.MAX_DECODED_LENGTH) {
                    return null
                }

                content.write(data)
                content.write(' '.code)
            }

            val bytes = content.toByteArray()
            val ops = ContentParser.parse(bytes) ?: return null
            return ContentPage(
                file = file,
                page = page,
                streams = streams,
                content = bytes,
                ops = ops,
                text = TextShowSimulator.run(ops, file, page.resources),
            )
        }

        private fun contentNumbers(file: PdfFile, page: CosDict): Sequence<Int> {
            var contents = page["Contents"]
            if (contents is CosRef) {
                val indirect = file.getObject(contents.number)
                if (indirect is CosArray) {
                    contents = indirect
                }
            }

            return when (contents) {
                is CosRef -> sequenceOf(contents.number)
                is CosArray -> contents.items.asSequence().filterIsInstance<CosRef>().map { r -> r.number }
                else -> emptySequence()
            }
        }
    }
}
