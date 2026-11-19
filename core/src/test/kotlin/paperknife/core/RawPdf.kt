package paperknife.core

import paperknife.core.syntax.StreamFilters

/**
 * Hand-written PDFs (Latin-1) for content and file structures PDFium can't create itself, such as
 * inline images, shadings, character spacing, Type 3 fonts and cross-reference streams.
 * Every page has these fonts:
 * - `/F1`: Helvetica.
 * - `/F2`: Helvetica in which code 66 also draws "A", with a wider advance (1000) than code 65
 *   (667), so a glyph's Unicode value doesn't tell its width.
 * - `/F3`: a Type 3 font whose glyphs "a", "b" and "c" are boxes 500, 600 and 700 units wide.
 *
 * PaperKnife for Windows' version returns a PdfSnapshot, a type of the PDFium layer; this one
 * returns the document's bytes.
 */
internal object RawPdf {
    const val BASE_CONTENT =
        "BT /F1 24 Tf 72 700 Td (Hello World) Tj ET\n" +
            "BT /F1 12 Tf 72 660 Td (This line must not move) Tj ET\n" +
            "0.86 0.16 0.16 rg 72 500 200 100 re f\n"

    const val SHADED_AREA = "q 300 500 200 100 re W n /Sh1 sh Q\n"

    const val INLINE_IMAGE =
        "q 60 0 0 60 400 300 cm BI /W 2 /H 2 /CS /RGB /BPC 8 ID " +
            "\u00FF\u0000\u0000\u0000\u00FF\u0000\u0000\u0000\u00FF\u00FF\u00FF\u0000\nEI Q\n"

    const val CHARACTER_SPACING = "BT /F1 14 Tf 4 Tc 72 300 Td (Spaced out letters) Tj ET\n"

    const val KERNED_TEXT = "BT /F1 18 Tf 72 250 Td [(Ke) -300 (rned text)] TJ ET\n"

    const val TYPE3_TEXT = "BT /F3 24 Tf 72 400 Td (abc) Tj ET\n"

    /** "ab ca" set the way TeX sets text: kerning between a and b, and a gap instead of a space. */
    const val GAPPED_TEXT = "BT /F3 24 Tf 72 400 Td [(a) 30 (b) -400 (ca)] TJ ET\n"

    private val shared = listOf(
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [300 0 500 0] /Extend [true true] " +
            "/Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >> >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FirstChar 65 /LastChar 68 /Widths [667 1000 722 722] " +
            "/Encoding << /Type /Encoding /BaseEncoding /WinAnsiEncoding /Differences [66 /A] >> >>",
        "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 700 700] /FontMatrix [0.001 0 0 0.001 0 0] " +
            "/CharProcs << /a 7 0 R /b 8 0 R /c 9 0 R >> /Encoding << /Type /Encoding /Differences [97 /a /b /c] >> " +
            "/FirstChar 97 /LastChar 99 /Widths [500 600 700] /ToUnicode 10 0 R /Resources << >> >>",
        stream("500 0 0 0 450 700 d1 0 0 450 700 re f"),
        stream("600 0 0 0 550 700 d1 0 0 550 350 re f"),
        stream("700 0 0 0 650 700 d1 0 350 650 350 re f"),
        stream(
            "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /T3 def /CMapType 2 def\n" +
                "1 begincodespacerange <00> <FF> endcodespacerange\n1 beginbfrange <61> <63> <0061> endbfrange\n" +
                "endcmap CMapName currentdict /CMap defineresource pop end end",
        ),
    )

    /** One page drawing [content], with a cross-reference table. */
    fun singlePage(content: String): ByteArray = build(listOf(listOf(content)))

    /**
     * A document whose pages draw the given content streams. With [shareContent], the second page
     * draws the first page's first stream instead of its own. With [useObjectStreams],
     * dictionaries are stored in an object stream and the cross-reference data in a compressed
     * stream with a PNG predictor.
     */
    fun build(pages: List<List<String>>, shareContent: Boolean = false, useObjectStreams: Boolean = false): ByteArray {
        // Objects 1 and 2 are the catalog and page tree; 3 to 10 are shared; pages follow.
        val objects = mutableListOf("<< /Type /Catalog /Pages 2 0 R >>", "")
        objects.addAll(shared)
        val kids = ArrayList<Int>()
        var firstStream = 0
        for (contents in pages) {
            val pageNumber = objects.size + 1
            kids.add(pageNumber)
            objects.add("")
            var streams = contents.map { content ->
                objects.add(stream(content))
                objects.size
            }

            if (firstStream == 0) {
                firstStream = streams[0]
            } else if (shareContent) {
                streams = listOf(firstStream)
            }

            val contentsEntry = if (streams.size == 1) "${streams[0]} 0 R" else "[${streams.joinToString(" ") { s -> "$s 0 R" }}]"
            objects[pageNumber - 1] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Contents $contentsEntry /Resources << /Font << /F1 3 0 R /F2 5 0 R /F3 6 0 R >> /Shading << /Sh1 4 0 R >> >> >>"
        }

        objects[1] = "<< /Type /Pages /Kids [${kids.joinToString(" ") { k -> "$k 0 R" }}] /Count ${kids.size} >>"
        return if (useObjectStreams) writeWithStreams(objects) else writeWithTable(objects)
    }

    fun stream(data: String): String = "<< /Length ${data.length} >>\nstream\n$data\nendstream"

    /** Writes numbered objects (the first is the catalog) with a cross-reference table. */
    fun writeWithTable(objects: List<String>): ByteArray {
        val pdf = StringBuilder("%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n")
        val offsets = ArrayList<Int>()
        for (i in objects.indices) {
            offsets.add(pdf.length)
            pdf.append("${i + 1} 0 obj\n${objects[i]}\nendobj\n")
        }

        val xref = pdf.length
        pdf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) {
            pdf.append(offset.toString().padStart(10, '0')).append(" 00000 n \n")
        }

        pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return pdf.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** Stores every dictionary in one object stream and writes a cross-reference stream (PDF 1.5). */
    private fun writeWithStreams(objects: List<String>): ByteArray {
        val compressed = (1..objects.size).filter { n -> !objects[n - 1].contains("stream") }
        val objectStream = objects.size + 1
        val xrefStream = objects.size + 2
        val header = StringBuilder()
        val bodies = StringBuilder()
        for (number in compressed) {
            header.append("$number ${bodies.length} ")
            bodies.append(objects[number - 1]).append('\n')
        }

        val packed = deflate(header.toString() + bodies)
        val pdf = StringBuilder("%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n")
        val offsets = HashMap<Int, Int>()
        for (number in 1..objects.size) {
            if (!compressed.contains(number)) {
                offsets[number] = pdf.length
                pdf.append("$number 0 obj\n${objects[number - 1]}\nendobj\n")
            }
        }

        offsets[objectStream] = pdf.length
        pdf.append(
            "$objectStream 0 obj\n<< /Type /ObjStm /N ${compressed.size} /First ${header.length} /Filter /FlateDecode " +
                "/Length ${packed.length} >>\nstream\n$packed\nendstream\nendobj\n",
        )
        offsets[xrefStream] = pdf.length

        // Rows of [type, offset (4 bytes), field 3 (2 bytes)], PNG "Up"-filtered.
        fun row(type: Int, second: Int, third: Int): ByteArray = byteArrayOf(
            type.toByte(), (second shr 24).toByte(), (second shr 16).toByte(), (second shr 8).toByte(), second.toByte(),
            (third shr 8).toByte(), third.toByte(),
        )

        val rows = ArrayList<ByteArray>()
        for (number in 0..xrefStream) {
            val index = compressed.indexOf(number)
            rows.add(if (number == 0) row(0, 0, 65535) else if (index >= 0) row(2, objectStream, index) else row(1, offsets.getValue(number), 0))
        }

        val filtered = StringBuilder()
        var previous = ByteArray(7)
        for (row in rows) {
            filtered.append(2.toChar())
            for (i in row.indices) {
                filtered.append(((row[i] - previous[i]) and 0xFF).toChar())
            }

            previous = row
        }

        val data = deflate(filtered.toString())
        pdf.append(
            "$xrefStream 0 obj\n<< /Type /XRef /Size ${xrefStream + 1} /Root 1 0 R /W [1 4 2] /Filter /FlateDecode " +
                "/DecodeParms << /Predictor 12 /Columns 7 >> /Length ${data.length} >>\nstream\n$data\nendstream\nendobj\n" +
                "startxref\n${offsets.getValue(xrefStream)}\n%%EOF\n",
        )
        return pdf.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun deflate(text: String): String = String(StreamFilters.deflate(text.toByteArray(Charsets.ISO_8859_1)), Charsets.ISO_8859_1)
}
